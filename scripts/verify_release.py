#!/usr/bin/env python3
"""Offline checks for the exact APK/AAB, using only Python, a JDK and Android SDK tools.

The protobuf reader follows AAPT2 Resources.proto and bundletool config.proto. It
only implements the wire types used by those schemas and rejects malformed input.
It does not replace testing the generated APK splits on an actual 16-KB device.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "http://schemas.android.com/apk/res/android"
MODEL_HASHES = {
    "model_int8.onnx": "4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c",
    "tokenizer.json": "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
}
DEBUG_CERTIFICATE = "01eff5e414f45af7746c77732f65afa5af85800487653c4dfbacda93e2e14c81"
BANNED_DEX_PREFIXES = (
    b"ru/privatediary/debug/StorePreviewActivity",
    b"com/google/android/gms/ads/", b"com/yandex/mobile/ads/", b"com/my/target/",
    b"com/applovin/", b"com/unity3d/ads/", b"com/facebook/ads/", b"com/vungle/",
    b"com/ironsource/", b"com/google/firebase/analytics/", b"com/google/firebase/crashlytics/",
    b"io/appmetrica/", b"com/yandex/metrica/", b"io/sentry/", b"com/bugsnag/",
    b"com/microsoft/appcenter/", b"okhttp3/", b"retrofit2/", b"io/ktor/client/", b"com/android/volley/",
)
MAX_MANIFEST_BYTES = 2 * 1024 * 1024


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for count in range(10):
        require(offset < len(data), "Truncated protobuf varint")
        byte = data[offset]
        offset += 1
        require(count < 9 or byte <= 1, "Oversized protobuf varint")
        value |= (byte & 127) << (count * 7)
        if not byte & 128:
            return value, offset
    raise ValueError("Oversized protobuf varint")


def protobuf_fields(data: bytes) -> dict[int, list[int | bytes]]:
    fields: dict[int, list[int | bytes]] = {}
    offset = 0
    while offset < len(data):
        key, offset = varint(data, offset)
        field, wire = key >> 3, key & 7
        require(field > 0, "Invalid protobuf field")
        if wire == 0:
            value, offset = varint(data, offset)
        else:
            if wire == 2:
                length, offset = varint(data, offset)
            elif wire in (1, 5):
                length = 8 if wire == 1 else 4
            else:
                raise ValueError("Unsupported protobuf wire type")
            require(length <= len(data) - offset, "Truncated protobuf field")
            value = data[offset:offset + length]
            offset += length
        fields.setdefault(field, []).append(value)
    return fields


def pb_bytes(fields: dict, number: int, default: bytes = b"") -> bytes:
    value = fields.get(number, [default])[-1]
    require(isinstance(value, bytes), "Wrong protobuf field type")
    return value


def pb_string(fields: dict, number: int, default: str = "") -> str:
    return pb_bytes(fields, number, default.encode()).decode("utf-8", errors="strict")


def compiled_attribute(item: bytes) -> str:
    values = protobuf_fields(item)
    for field in (2, 3):  # Item.str / raw_str, both containing String.value.
        if field in values:
            return pb_string(protobuf_fields(pb_bytes(values, field)), 1)
    if 7 in values:  # Item.prim
        primitive = protobuf_fields(pb_bytes(values, 7))
        if 8 in primitive:
            boolean = primitive[8][-1]
            require(boolean in (0, 1), "Invalid compiled boolean")
            return "true" if boolean else "false"
        for field in (6, 7):
            if field in primitive:
                value = primitive[field][-1]
                require(isinstance(value, int), "Invalid compiled integer")
                return str(value)
    return "@compiled-reference"


def aab_manifest(data: bytes, depth: int = 0) -> ET.Element:
    require(len(data) <= MAX_MANIFEST_BYTES and depth <= 64, "Oversized or deeply nested manifest")
    node = protobuf_fields(data)
    require(1 in node, "Manifest node does not contain an element")
    element = protobuf_fields(pb_bytes(node, 1))  # XmlNode.element = 1.
    name = pb_string(element, 3)
    require(bool(name), "Manifest element name is empty")
    namespace = pb_string(element, 2)
    result = ET.Element(f"{{{namespace}}}{name}" if namespace else name)
    for encoded in element.get(4, []):
        require(isinstance(encoded, bytes), "Invalid manifest attribute")
        attribute = protobuf_fields(encoded)
        attr_name, attr_namespace = pb_string(attribute, 2), pb_string(attribute, 1)
        require(bool(attr_name), "Empty manifest attribute")
        key = f"{{{attr_namespace}}}{attr_name}" if attr_namespace else attr_name
        require(key not in result.attrib, "Duplicate manifest attribute")
        value = pb_string(attribute, 3)
        if not value and 6 in attribute:
            value = compiled_attribute(pb_bytes(attribute, 6))
        result.set(key, value)
    for child in element.get(5, []):
        require(isinstance(child, bytes), "Invalid manifest child")
        if 1 in protobuf_fields(child):
            result.append(aab_manifest(child, depth + 1))
    return result


def aapt_manifest(text: str) -> ET.Element:
    stack: list[tuple[int, ET.Element]] = []
    root = None
    for line in text.splitlines():
        node = re.match(r"^(\s*)E: ([\w.:-]+)", line)
        if node:
            depth, element = len(node[1]), ET.Element(node[2])
            while stack and stack[-1][0] >= depth:
                stack.pop()
            if stack:
                stack[-1][1].append(element)
            else:
                require(root is None, "Multiple manifest roots")
                root = element
            stack.append((depth, element))
            continue
        attribute = re.match(r"^\s*A: (?:(\w+):)?([\w.-]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$", line)
        if attribute and stack:
            prefix, name, raw = attribute.groups()
            require(prefix in (None, "android"), "Unknown manifest attribute namespace")
            quoted = re.match(r'"(?:[^"\\]|\\.)*"', raw)
            if quoted:
                value = json.loads(quoted[0])
            else:
                typed = re.fullmatch(r"\(type (0x[0-9a-fA-F]+)\)(0x[0-9a-fA-F]+)", raw)
                if typed:
                    integer = int(typed[2], 16)
                    value = ("true" if integer else "false") if int(typed[1], 16) == 18 else str(integer)
                else:
                    value = raw
            key = f"{{{ANDROID}}}{name}" if prefix else name
            require(key not in stack[-1][1].attrib, "Duplicate manifest attribute")
            stack[-1][1].set(key, value)
    require(root is not None and root.tag == "manifest", "Could not read Android manifest")
    return root


def android_attr(element: ET.Element, name: str, default: str = "") -> str:
    return element.get(f"{{{ANDROID}}}{name}", default)


def check_manifest(root: ET.Element, expected_package: str) -> dict:
    require(root.tag == "manifest" and root.get("package") == expected_package, "Unexpected application ID")
    permissions = []
    for element in root.iter():
        if element.tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            permission = android_attr(element, "name")
            require(bool(permission) and not permission.startswith(("@", "0x")), "Unresolved permission name")
            require(permission != "android.permission.INTERNET", "INTERNET permission is forbidden")
            permissions.append(permission)
        if element.tag in ("activity", "activity-alias", "service", "receiver", "instrumentation"):
            component = android_attr(element, "name")
            require(not re.search(r"(?:preview|screenshot|androidtest).*activity|androidx\.test\.", component, re.I),
                    "Test or store-preview component found in production manifest")
        if element.tag == "instrumentation":
            raise ValueError("Instrumentation must not be present in the production artifact")
    application = root.find("application")
    require(application is not None, "Application element is missing")
    for flag in ("debuggable", "testOnly", "allowBackup"):
        value = android_attr(application, flag, "false")
        require(value in ("false", "0"), f"android:{flag} must be false")
    sdk = root.find("uses-sdk")
    require(sdk is not None, "SDK declaration is missing")
    minimum = int(android_attr(sdk, "minSdkVersion"))
    target = int(android_attr(sdk, "targetSdkVersion"))
    require(minimum == 26 and target >= 35, "Unexpected minSdk or obsolete targetSdk")
    return {"applicationId": expected_package, "versionName": android_attr(root, "versionName"),
            "versionCode": int(android_attr(root, "versionCode")), "minSdk": minimum, "targetSdk": target,
            "permissions": sorted(permissions), "debuggable": False, "testOnly": False, "allowBackup": False}


def elf_alignment(data: bytes, name: str) -> dict:
    require(data[:4] == b"\x7fELF" and len(data) >= 64, f"Invalid native ELF: {name}")
    elf_class, byte_order = data[4], data[5]
    require(elf_class in (1, 2) and byte_order in (1, 2), f"Unsupported ELF: {name}")
    endian = "<" if byte_order == 1 else ">"
    if elf_class == 2:
        offset = struct.unpack_from(endian + "Q", data, 32)[0]
        entry_size, count = struct.unpack_from(endian + "HH", data, 54)
        format_, minimum = endian + "IIQQQQQQ", 56
    else:
        offset = struct.unpack_from(endian + "I", data, 28)[0]
        entry_size, count = struct.unpack_from(endian + "HH", data, 42)
        format_, minimum = endian + "IIIIIIII", 32
    require(entry_size >= minimum and count > 0 and offset + entry_size * count <= len(data), f"Invalid ELF program headers: {name}")
    alignments = []
    for index in range(count):
        header = struct.unpack_from(format_, data, offset + index * entry_size)
        if header[0] != 1:
            continue
        file_offset, address, alignment = (header[2], header[3], header[7]) if elf_class == 2 else (header[1], header[2], header[7])
        if elf_class == 2:
            require(alignment >= 16384 and alignment & (alignment - 1) == 0 and (address - file_offset) % 16384 == 0,
                    f"64-bit native library is not aligned for 16-KB pages: {name}")
        alignments.append(alignment)
    require(bool(alignments), f"Native ELF has no loadable segments: {name}")
    return {"name": name, "bits": 64 if elf_class == 2 else 32, "loadAlignments": alignments}


def check_bundle_alignment(data: bytes) -> None:
    config = protobuf_fields(data)
    optimizations = protobuf_fields(pb_bytes(config, 2))
    native = protobuf_fields(pb_bytes(optimizations, 2))
    require(native.get(1, [0])[-1] == 1 and native.get(2, [0])[-1] in (2, 3),
            "Bundle must request PAGE_ALIGNMENT_16K or stronger for uncompressed native libraries")


def sdk_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = [path / name for path in (Path(sdk) / "build-tools").glob("*") if (path / name).is_file()]
        if candidates:
            def version(path: Path):
                return tuple(int(value) for value in re.findall(r"\d+", path.parent.name))
            return str(max(candidates, key=version))
    found = shutil.which(name)
    require(found is not None, f"Android SDK tool not found: {name}")
    return found


def run(command: list[str], check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            env={**os.environ, "LC_ALL": "C", "LANG": "C"}, timeout=180)
    if check:
        require(result.returncode == 0, f"{Path(command[0]).name} failed: {result.stdout[-1800:]}")
    return result


def reject_debug_certificate(digest: str, description: str) -> None:
    require(digest.lower() != DEBUG_CERTIFICATE and "androiddebug" not in re.sub(r"[^a-z]", "", description.lower()),
            "Android Debug signing certificate is forbidden for a store release")


def apk_has_signing_block(path: Path) -> bool:
    with path.open("rb") as source:
        source.seek(max(0, path.stat().st_size - 65557))
        tail = source.read()
        eocd = tail.rfind(b"PK\x05\x06")
        require(eocd >= 0 and len(tail) - eocd >= 22, "ZIP end record is missing")
        central = struct.unpack_from("<I", tail, eocd + 16)[0]
        if central < 16:
            return False
        source.seek(central - 16)
        return source.read(16) == b"APK Sig Block 42"


def signature(path: Path, archive: zipfile.ZipFile, bundle: bool, allow_unsigned: bool) -> dict:
    jar_signatures = any(re.fullmatch(r"META-INF/[^/]+\.(?:SF|RSA|DSA|EC)", name, re.I) for name in archive.namelist())
    if bundle:
        if not jar_signatures:
            require(allow_unsigned, "Unsigned AAB: use the owner's release key before upload")
            return {"verified": False, "unsignedCandidate": True}
        verified = run(["jarsigner", "-verify", "-verbose", str(path)])
        require("jar verified." in verified.stdout.lower() and "unsigned entries" not in verified.stdout.lower(),
                "AAB signature does not cover the complete artifact")
        certificate = run(["keytool", "-printcert", "-jarfile", str(path)])
        pem = run(["keytool", "-printcert", "-jarfile", str(path), "-rfc"])
        match = re.search(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", pem.stdout, re.S)
        require(match is not None, "AAB signing certificate is missing")
        digest = hashlib.sha256(ssl.PEM_cert_to_DER_cert(match[0])).hexdigest()
        reject_debug_certificate(digest, certificate.stdout)
        return {"verified": True, "unsignedCandidate": False, "certificateSha256": digest}
    checked = run([sdk_tool("apksigner"), "verify", "--print-certs", str(path)], check=False)
    if checked.returncode:
        require(allow_unsigned and not jar_signatures and not apk_has_signing_block(path), "Unsigned or invalid APK signing certificate")
        return {"verified": False, "unsignedCandidate": True}
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", checked.stdout)
    require(len(digests) == 1, "Expected exactly one APK signing certificate")
    reject_debug_certificate(digests[0], checked.stdout)
    return {"verified": True, "unsignedCandidate": False, "certificateSha256": digests[0].lower()}


def verify(path: Path, allow_unsigned: bool = False, expected_package: str = "ru.privatediary") -> dict:
    require(path.suffix.lower() in (".apk", ".aab") and path.is_file(), "Provide an APK or AAB file")
    bundle = path.suffix.lower() == ".aab"
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Archive contains duplicate entries")
        if bundle:
            manifest_info = archive.getinfo("base/manifest/AndroidManifest.xml")
            require(manifest_info.file_size <= MAX_MANIFEST_BYTES, "Manifest is too large")
            root = aab_manifest(archive.read(manifest_info))
            check_bundle_alignment(archive.read("BundleConfig.pb"))
        else:
            root = aapt_manifest(run([sdk_tool("aapt"), "dump", "xmltree", str(path), "AndroidManifest.xml"]).stdout)
            run([sdk_tool("zipalign"), "-c", "-P", "16", "4", str(path)])
        manifest = check_manifest(root, expected_package)
        model_prefix = "base/assets/model/" if bundle else "assets/model/"
        for name, expected in MODEL_HASHES.items():
            with archive.open(model_prefix + name) as source:
                require(hashlib.file_digest(source, "sha256").hexdigest() == expected, f"Offline model SHA-256 mismatch: {name}")
        native = []
        dex_count = 0
        for name in names:
            if re.fullmatch(r"(?:base/dex/)?classes\d*\.dex", name):
                info = archive.getinfo(name)
                require(info.file_size <= 64 * 1024 * 1024, "DEX file is unexpectedly large")
                data = archive.read(info)
                for prefix in BANNED_DEX_PREFIXES:
                    require(prefix not in data and prefix.replace(b"/", b".") not in data,
                            f"Forbidden advertising/analytics/network SDK signature in {name}: {prefix.decode()}")
                dex_count += 1
            if re.fullmatch(r"(?:base/)?lib/[^/]+/[^/]+\.so", name):
                native.append(elf_alignment(archive.read(name), name))
        require(dex_count > 0 and bool(native), "App bytecode or native libraries are missing")
        signed = signature(path, archive, bundle, allow_unsigned)
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"artifact": path.name, "bytes": path.stat().st_size, "sha256": digest,
            "format": "aab" if bundle else "apk", "manifest": manifest,
            "signature": signed, "technicalChecksPassed": True,
            "candidateOnly": signed["unsignedCandidate"], "nativeLibraries": native,
            "limitations": ["Store seller/account approval is a separate check.",
                            "Test installed APK splits on real devices, including a 16-KB-page device.",
                            "Known SDK bytecode signatures are checked; this is not a complete third-party security audit."]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--allow-unsigned-candidate", action="store_true",
                        help="Verify an unsigned review candidate; does not make it store-uploadable")
    parser.add_argument("--application-id", default="ru.privatediary")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.artifact, args.allow_unsigned_candidate, args.application_id)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as failure:
        print(f"Release verification failed: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
