#!/usr/bin/env python3
"""Restore a signed APK without rebuilding it or accessing signing keys."""

import argparse
import bisect
import hashlib
import io
import operator
from pathlib import Path
import re
import zipfile

MAX_PART_BYTES = 80 * 1024 * 1024
BLOCK_BYTES = 1024 * 1024
ASSET_SHA256 = {
    "assets/model/model_int8.onnx":
        "4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c",
    "assets/model/tokenizer.json":
        "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
}


class ConcatenatedParts(io.RawIOBase):
    """Seekable view over archive parts; never writes a combined archive to disk."""

    def __init__(self, paths):
        super().__init__()
        self.paths = tuple(paths)
        self.offsets = [0]
        for path in self.paths:
            self.offsets.append(self.offsets[-1] + path.stat().st_size)
        self.position = 0
        self.active_index = -1
        self.stream = None

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        self._checkClosed()
        return self.position

    def seek(self, offset, whence=0):
        self._checkClosed()
        offset = operator.index(offset)
        if whence == 0:
            position = offset
        elif whence == 1:
            position = self.position + offset
        elif whence == 2:
            position = self.offsets[-1] + offset
        else:
            raise ValueError("Invalid seek origin")
        if position < 0:
            raise ValueError("Cannot seek before the start of the archive")
        self.position = position
        return position

    def read(self, size=-1):
        self._checkClosed()
        remaining = max(0, self.offsets[-1] - self.position)
        if size is not None:
            size = operator.index(size)
            if size >= 0:
                remaining = min(remaining, size)
        chunks = []
        while remaining:
            index = bisect.bisect_right(self.offsets, self.position) - 1
            if self.active_index != index:
                if self.stream is not None:
                    self.stream.close()
                self.stream = self.paths[index].open("rb")
                self.active_index = index
            self.stream.seek(self.position - self.offsets[index])
            count = min(remaining, self.offsets[index + 1] - self.position)
            block = self.stream.read(count)
            if not block:
                raise OSError("Archive part changed or was truncated during restoration")
            self.position += len(block)
            remaining -= len(block)
            chunks.append(block)
        return b"".join(chunks)

    def readinto(self, buffer):
        view = memoryview(buffer).cast("B")
        data = self.read(len(view))
        view[:len(data)] = data
        return len(data)

    def close(self):
        if self.stream is not None:
            self.stream.close()
            self.stream = None
        super().close()


def checksums(path):
    entries = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *]([^/\\]+)", line)
        if not match or match[2] in entries or match[2] in (".", ".."):
            raise ValueError(f"Invalid checksum entry in {path.name}")
        entries[match[2]] = match[1].lower()
    if not entries:
        raise ValueError(f"Empty checksum manifest: {path.name}")
    return entries


def require_digest(actual, expected, name):
    if actual.hexdigest() != expected:
        raise ValueError(f"SHA-256 mismatch: {name}")


def restore(source, destination, version="0.3.0"):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Version must contain three numeric components, for example 0.4.0")
    archive_name = f"Private-Diary-{version}.zip"
    apk_name = f"Private-Diary-{version}-offline.apk"
    parts = checksums(source / "SHA256SUMS-parts.txt")
    expected_parts = [f"{archive_name}.part-{i:03d}" for i in range(len(parts))]
    if set(parts) != set(expected_parts):
        raise ValueError("Archive parts must start at 000 and be consecutive")
    archive_sha = checksums(source / "SHA256SUMS-archive.txt")
    apk_sha = checksums(source / "SHA256SUMS-apk.txt")
    if set(archive_sha) != {archive_name} or set(apk_sha) != {apk_name}:
        raise ValueError("Unexpected archive or APK name in checksum manifests")

    archive_digest = hashlib.sha256()
    for name in expected_parts:
        part = source / name
        if not 0 < part.stat().st_size <= MAX_PART_BYTES:
            raise ValueError(f"Invalid archive part size: {name}")
        part_digest = hashlib.sha256()
        with part.open("rb") as stream:
            for block in iter(lambda: stream.read(BLOCK_BYTES), b""):
                part_digest.update(block)
                archive_digest.update(block)
        require_digest(part_digest, parts[name], name)
    require_digest(archive_digest, archive_sha[archive_name], archive_name)

    destination.mkdir(parents=True, exist_ok=True)
    apk_path = destination / apk_name
    apk_digest = hashlib.sha256()
    with ConcatenatedParts([source / name for name in expected_parts]) as combined, zipfile.ZipFile(combined) as archive:
        if archive.namelist() != [apk_name]:
            raise ValueError("The delivery archive must contain only the named APK")
        # Copy the exact member to a fixed destination; never extract member paths.
        with archive.open(apk_name) as stream, apk_path.open("xb") as output:
            for block in iter(lambda: stream.read(BLOCK_BYTES), b""):
                apk_digest.update(block)
                output.write(block)
    require_digest(apk_digest, apk_sha[apk_name], apk_name)

    expected_assets = dict(ASSET_SHA256)
    with zipfile.ZipFile(apk_path) as apk:
        for name, expected in expected_assets.items():
            if apk.namelist().count(name) != 1:
                raise ValueError(f"Missing or duplicated bundled model asset: {name}")
            digest = hashlib.sha256()
            with apk.open(name) as stream:
                for block in iter(lambda: stream.read(BLOCK_BYTES), b""):
                    digest.update(block)
            require_digest(digest, expected, name)
    print(f"Verified signed offline APK: {apk_path.name} ({apk_path.stat().st_size} bytes)")
    print(f"SHA-256: {apk_digest.hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--version", default="0.3.0", help="Release version; defaults to 0.3.0 for compatibility")
    arguments = parser.parse_args()
    try:
        restore(arguments.source, arguments.destination, arguments.version)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"APK verification failed: {error}\n")
