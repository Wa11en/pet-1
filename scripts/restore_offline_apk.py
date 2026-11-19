#!/usr/bin/env python3
"""Restore a signed APK without rebuilding it or accessing signing keys."""

import argparse
import hashlib
from pathlib import Path
import re
import zipfile

ARCHIVE = "Private-Diary-0.3.0.zip"
APK = "Private-Diary-0.3.0-offline.apk"
MAX_PART_BYTES = 80 * 1024 * 1024
BLOCK_BYTES = 1024 * 1024
ASSET_SHA256 = {
    "assets/model/model_int8.onnx":
        "4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c",
    "assets/model/tokenizer.json":
        "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
}


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


def restore(source, destination):
    parts = checksums(source / "SHA256SUMS-parts.txt")
    expected_parts = [f"{ARCHIVE}.part-{i:03d}" for i in range(len(parts))]
    if set(parts) != set(expected_parts):
        raise ValueError("Archive parts must start at 000 and be consecutive")
    archive_sha = checksums(source / "SHA256SUMS-archive.txt")
    apk_sha = checksums(source / "SHA256SUMS-apk.txt")
    if set(archive_sha) != {ARCHIVE} or set(apk_sha) != {APK}:
        raise ValueError("Unexpected archive or APK name in checksum manifests")

    destination.mkdir(parents=True, exist_ok=True)
    archive_path = destination / ARCHIVE
    archive_digest = hashlib.sha256()
    with archive_path.open("xb") as output:
        for name in expected_parts:
            part = source / name
            if not 0 < part.stat().st_size <= MAX_PART_BYTES:
                raise ValueError(f"Invalid archive part size: {name}")
            part_digest = hashlib.sha256()
            with part.open("rb") as stream:
                for block in iter(lambda: stream.read(BLOCK_BYTES), b""):
                    part_digest.update(block)
                    archive_digest.update(block)
                    output.write(block)
            require_digest(part_digest, parts[name], name)
    require_digest(archive_digest, archive_sha[ARCHIVE], ARCHIVE)

    apk_path = destination / APK
    apk_digest = hashlib.sha256()
    with zipfile.ZipFile(archive_path) as archive:
        if archive.namelist() != [APK]:
            raise ValueError("The delivery archive must contain only the named APK")
        # Copy the exact member to a fixed destination; never extract member paths.
        with archive.open(APK) as stream, apk_path.open("xb") as output:
            for block in iter(lambda: stream.read(BLOCK_BYTES), b""):
                apk_digest.update(block)
                output.write(block)
    require_digest(apk_digest, apk_sha[APK], APK)

    with zipfile.ZipFile(apk_path) as apk:
        for name, expected in ASSET_SHA256.items():
            if apk.namelist().count(name) != 1:
                raise ValueError(f"Missing or duplicated bundled model asset: {name}")
            digest = hashlib.sha256()
            with apk.open(name) as stream:
                for block in iter(lambda: stream.read(BLOCK_BYTES), b""):
                    digest.update(block)
            require_digest(digest, expected, name)
    archive_path.unlink()
    print(f"Verified signed offline APK: {apk_path.name} ({apk_path.stat().st_size} bytes)")
    print(f"SHA-256: {apk_digest.hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    arguments = parser.parse_args()
    try:
        restore(arguments.source, arguments.destination)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"APK verification failed: {error}\n")
