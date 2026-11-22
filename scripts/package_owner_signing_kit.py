#!/usr/bin/env python3
"""Package the verified unsigned store APK and public tools for owner-side signing."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APKSIGNER_JAR_SHA256 = "00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7"
ANDROID_SDK_NOTICE_SHA256 = "14ad8a3f18a62e674e865b5e06b5847a6c97848d33f8cdc8e842eaf9bed994c3"
ZIP_TIMESTAMP = (2026, 11, 22, 0, 0, 0)
CHUNK_SIZE = 1024 * 1024
PUBLIC_FILES = (
    "README.ru.md",
    "Sign-Windows.cmd",
    "sign-release.ps1",
    "sign-release.sh",
    "tools/LICENSE-apksigner.txt",
    "tools/README.md",
)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _path(value: Path, *, directory: bool = False) -> Path:
    # Inspect the spelling before resolve removes '..' or follows a symlink.
    original = Path(value).absolute()
    _require(not any(item.is_symlink() for item in (original, *original.parents)),
             f"Symlink paths are not signing-kit inputs or outputs: {value}")
    path = original.resolve(strict=True)
    _require(path.is_dir() if directory else stat.S_ISREG(path.stat().st_mode),
             f"Expected an existing {'directory' if directory else 'regular file'}: {value}")
    return path


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(CHUNK_SIZE), b""):
            digest.update(block)
    return digest.hexdigest()


def _read_json(path: Path) -> dict:
    _require(path.stat().st_size <= 2 * CHUNK_SIZE, f"JSON input is too large: {path.name}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid UTF-8 JSON: {path.name}") from error
    _require(isinstance(value, dict), f"JSON must contain an object: {path.name}")
    return value


def _json_bytes(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _validate_report(report: dict, config: dict, apk: Path, apk_hash: str) -> dict:
    version = config.get("versionName")
    _require(isinstance(version, str) and
             re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,79}", version) is not None,
             "Invalid release versionName")
    _require(type(config.get("versionCode")) is int and config["versionCode"] > 0,
             "Invalid release versionCode")
    _require(isinstance(config.get("applicationId"), str) and
             re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", config["applicationId"]) is not None,
             "Invalid release applicationId")
    minimum = config.get("minSdk", 26)
    _require(type(minimum) is int and minimum == 26, "Unexpected configured minSdk")
    _require((config["applicationId"], version, config["versionCode"], minimum) ==
             ("ru.privatediary", "1.0.0", 11, 26),
             "Release identity is not supported by the bundled signing helpers")
    _require(report.get("technicalChecksPassed") is True and report.get("candidateOnly") is True,
             "A successful unsigned-candidate verification report is required")
    signature = report.get("signature")
    _require(isinstance(signature, dict) and signature.get("unsignedCandidate") is True and
             signature.get("verified") is False and not signature.get("certificateSha256"),
             "Report signature must describe an unsigned candidate")
    _require(report.get("format") == "apk" and report.get("distribution") == "store",
             "Only the verified store APK can be packaged")
    _require(report.get("artifact") == apk.name, "Report artifact filename differs from the APK input")
    _require(type(report.get("bytes")) is int and report["bytes"] == apk.stat().st_size,
             "Report APK byte count mismatch")
    _require(report.get("sha256") == apk_hash, "Report APK SHA-256 mismatch")
    manifest = report.get("manifest")
    _require(isinstance(manifest, dict), "Report manifest is missing")
    expected = {"variant": "store", "applicationId": config["applicationId"],
                "versionName": version, "versionCode": config["versionCode"], "minSdk": minimum}
    for field, value in expected.items():
        _require(type(manifest.get(field)) is type(value) and manifest[field] == value,
                 f"Report manifest {field} differs from release configuration")
    for field in ("debuggable", "testOnly", "allowBackup"):
        _require(manifest.get(field) is False, f"Report manifest {field} must be false")
    for field in ("usesCleartextTraffic", "automaticAdInitialization", "advertisingProcessHasSeparateUid"):
        _require(manifest.get(field) is False, f"Report manifest {field} must be false")
    _require(manifest.get("advertisingProcess") == ":ads", "Unexpected advertising process")
    _require(type(manifest.get("targetSdk")) is int and manifest["targetSdk"] >= 35,
             "Report targetSdk is missing or obsolete")
    settings = report.get("buildSettings")
    _require(isinstance(settings, dict) and settings.get("present") is True and
             settings.get("distribution") == "store" and settings.get("networkFeatures") is True,
             "Report must contain the store BuildConfig settings")
    # Owner setup may legitimately be absent: diary functions remain available.
    # Never export backend URLs, seller fields, ad IDs or key material from a report.
    public_settings = {"present": True, "distribution": "store", "networkFeatures": True}
    for field in ("yanAdUnitId", "paymentBackendUrl", "sellerLegalName", "proTermsUrl", "proRefundUrl"):
        _require(isinstance(settings.get(field, ""), str), f"Invalid BuildConfig field: {field}")
        public_settings[field + "Configured"] = bool(settings.get(field, "").strip())
    license_hash = settings.get("proLicensePublicKeySha256")
    _require(license_hash is None or
             (isinstance(license_hash, str) and re.fullmatch(r"[0-9a-f]{64}", license_hash) is not None),
             "Invalid BuildConfig public-key fingerprint")
    public_settings["proLicensePublicKeyConfigured"] = license_hash is not None
    # Explicit fields keep paths, arbitrary report additions and private setup out.
    public_manifest = {**expected, "targetSdk": manifest["targetSdk"],
                       "debuggable": False, "testOnly": False, "allowBackup": False}
    for field in ("usesCleartextTraffic", "automaticAdInitialization", "advertisingProcessHasSeparateUid"):
        if field in manifest:
            _require(type(manifest[field]) is bool, f"Invalid report manifest field: {field}")
            public_manifest[field] = manifest[field]
    for field in ("permissions", "advertisingComponents"):
        _require(isinstance(manifest.get(field), list) and 0 < len(manifest[field]) <= 256 and
                 all(isinstance(item, str) and re.fullmatch(r"[A-Za-z0-9_.$]+", item)
                     for item in manifest[field]), f"Invalid report manifest field: {field}")
        public_manifest[field] = manifest[field]
    _require("android.permission.INTERNET" in manifest["permissions"] and
             not any(item == "com.google.android.gms.permission.AD_ID" or
                     item.startswith("android.permission.ACCESS_ADSERVICES") for item in manifest["permissions"]),
             "Unexpected store APK permissions")
    public_manifest["advertisingProcess"] = ":ads"
    native = report.get("nativeLibraries", [])
    _require(isinstance(native, list) and 0 < len(native) <= 256, "Invalid native-library report")
    public_native = []
    for library in native:
        _require(isinstance(library, dict) and isinstance(library.get("name"), str) and
                 re.fullmatch(r"lib/[A-Za-z0-9_-]+/[A-Za-z0-9_.+-]+\.so", library["name"]) is not None and
                 type(library.get("bits")) is int and library["bits"] in (32, 64) and
                 isinstance(library.get("loadAlignments"), list) and bool(library["loadAlignments"]) and
                 all(type(item) is int and item > 0 for item in library["loadAlignments"]),
                 "Invalid native-library report")
        _require(library["bits"] != 64 or
                 all(item >= 16384 and item & (item - 1) == 0 for item in library["loadAlignments"]),
                 "64-bit native-library report lacks 16-KB alignment")
        public_native.append({field: library[field] for field in ("name", "bits", "loadAlignments")})
    return {"artifact": f"Private-Diary-{version}-store-release-unsigned.apk", "format": "apk",
            "distribution": "store", "bytes": report["bytes"], "sha256": apk_hash,
            "technicalChecksPassed": True, "candidateOnly": True,
            "signature": {"verified": False, "unsignedCandidate": True},
            "manifest": public_manifest, "buildSettings": public_settings,
            "nativeLibraries": public_native,
            "limitations": ["Unsigned APK: owner signing and installed-device testing are still required.",
                            "Technical checks do not establish owner configuration or RuStore submission readiness.",
                            "Owner configuration values and unrelated report fields are omitted from this public copy."]}


def _source(root: Path) -> bytes:
    revision = "not recorded"
    try:
        result = subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", "HEAD"],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=10)
        candidate = result.stdout.strip()
        if result.returncode == 0 and re.fullmatch(r"[0-9a-f]{40}(?:[0-9a-f]{24})?", candidate):
            revision = candidate
    except (OSError, subprocess.TimeoutExpired):
        pass
    return ("Private Diary / Личный дневник\n"
            f"Source Git revision: {revision}\n"
            "Artifact intent: unsigned store release prepared for owner-side signing.\n"
            "No production signature or RuStore publication is claimed.\n").encode("utf-8")


class _HashingReader:
    def __init__(self, stream):
        self.stream = stream
        self.digest = hashlib.sha256()

    def read(self, size: int = -1) -> bytes:
        data = self.stream.read(size)
        self.digest.update(data)
        return data


def _output_path(value: Path, inputs: set[Path]) -> Path:
    original = Path(value).absolute()
    _require(not any(item.is_symlink() for item in (original, *original.parents)),
             "Output and its directories must not be symlinks")
    output = original.resolve()
    _require(output.suffix.lower() == ".zip", "Signing-kit output must end in .zip")
    for destination in (output, output.with_suffix(output.suffix + ".sha256")):
        _require(not any(item.is_symlink() for item in (destination, *destination.parents)),
                 "Output and its directories must not be symlinks")
        _require(not destination.exists() or destination.is_file(), "Output must be a regular file")
        _require(all(destination != item and
                     not (destination.exists() and os.path.samefile(destination, item)) for item in inputs),
                 "Output must not replace a signing-kit input")
    return output


def build_kit(root: Path, apk: Path, apksigner_jar: Path, notice: Path,
              report: Path, output: Path | None = None) -> dict:
    """Validate public inputs, then write a deterministic ZIP and SHA-256 sidecar.

    Large APK/JAR/NOTICE inputs are hashed and copied in bounded chunks. Only the
    exact allowlist is included; neighboring keys, passwords and diaries are ignored.
    """
    root = _path(root, directory=True)
    apk, apksigner_jar, notice, report = (_path(path) for path in (apk, apksigner_jar, notice, report))
    _require(apk.suffix.lower() == ".apk" and zipfile.is_zipfile(apk), "Provide a regular APK ZIP file")
    config_path = _path(root / "release/release-config.json")
    helpers = {name: _path(root / "release/signing-kit" / name) for name in PUBLIC_FILES}
    apk_hash = _sha256(apk)
    _require(_sha256(apksigner_jar) == APKSIGNER_JAR_SHA256, "Unrecognized apksigner.jar SHA-256")
    _require(_sha256(notice) == ANDROID_SDK_NOTICE_SHA256, "Android SDK NOTICE SHA-256 mismatch")
    config = _read_json(config_path)
    public_report = _validate_report(_read_json(report), config, apk, apk_hash)
    version = config["versionName"]
    manifest = {"schemaVersion": 1, "applicationId": config["applicationId"], "versionName": version,
                "versionCode": config["versionCode"], "minSdk": config.get("minSdk", 26),
                "unsignedApkFile": public_report["artifact"], "unsignedApkSha256": apk_hash,
                "apksignerJarFile": "tools/apksigner.jar", "apksignerJarSha256": APKSIGNER_JAR_SHA256,
                "androidSdkNoticeFile": "tools/NOTICE-Android-SDK.txt",
                "androidSdkNoticeSha256": ANDROID_SDK_NOTICE_SHA256,
                "artifactIntent": "unsigned_store_release_for_owner_signing", "unsignedCandidate": True,
                "productionSigned": False, "submissionReady": False, "publishedToStore": False}
    files = {**helpers, manifest["unsignedApkFile"]: apk,
             "tools/apksigner.jar": apksigner_jar, "tools/NOTICE-Android-SDK.txt": notice}
    expected_hashes = {manifest["unsignedApkFile"]: apk_hash, "tools/apksigner.jar": APKSIGNER_JAR_SHA256,
                       "tools/NOTICE-Android-SDK.txt": ANDROID_SDK_NOTICE_SHA256}
    generated = {"release-manifest.json": _json_bytes(manifest),
                 "verification-report.json": _json_bytes(public_report), "SOURCE.txt": _source(root)}
    inputs = {apk, apksigner_jar, notice, report, config_path, *helpers.values()}
    output = _output_path(output or root / f"release/artifacts/Private-Diary-{version}-Release-Signing-Kit.zip", inputs)
    output.parent.mkdir(parents=True, exist_ok=True)
    checksum_output = output.with_suffix(output.suffix + ".sha256")
    temporary = checksum_temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".owner-signing-kit-", suffix=".zip", delete=False) as stream:
            temporary = Path(stream.name)
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(set(files) | set(generated)):
                info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                if name in generated:
                    archive.writestr(info, generated[name], compresslevel=9)
                else:
                    with files[name].open("rb") as source, archive.open(info, "w", force_zip64=True) as destination:
                        reader = _HashingReader(source)
                        shutil.copyfileobj(reader, destination, length=CHUNK_SIZE)
                    _require(name not in expected_hashes or reader.digest.hexdigest() == expected_hashes[name],
                             f"Input changed while packaging: {name}")
        digest = _sha256(temporary)
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".owner-signing-kit-", suffix=".sha256", delete=False) as stream:
            checksum_temporary = Path(stream.name)
            stream.write(f"{digest}  {output.name}\n".encode("utf-8"))
        os.replace(temporary, output)
        os.replace(checksum_temporary, checksum_output)
    finally:
        for path in (temporary, checksum_temporary):
            if path is not None:
                path.unlink(missing_ok=True)
    return {"output": str(output), "sha256": digest, "checksumOutput": str(checksum_output),
            "fileCount": len(files) + len(generated), "manifest": manifest,
            "productionSigned": False, "submissionReady": False, "publishedToStore": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--apksigner-jar", type=Path, required=True)
    parser.add_argument("--notice", type=Path, required=True)
    parser.add_argument("--verification-report", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(build_kit(args.root, args.apk, args.apksigner_jar, args.notice,
                                   args.verification_report, args.output), ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f"Owner signing-kit packaging failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
