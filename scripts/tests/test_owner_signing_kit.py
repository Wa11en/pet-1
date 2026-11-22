"""Verify the owner signing archive's integrity and public-file boundary."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("owner_signing_kit", ROOT / "scripts/package_owner_signing_kit.py")
kit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kit)


class OwnerSigningKitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.apk = self.root / "app-store-release-unsigned.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"tiny unsigned APK fixture")
        self.jar = self.root / "sdk/lib/apksigner.jar"
        self.jar.parent.mkdir(parents=True)
        self.jar.write_bytes(b"verified signer fixture")
        self.notice = self.root / "sdk/NOTICE.txt"
        self.notice.write_bytes(b"Full SDK NOTICE fixture, including non-UTF-8: \xff\r\n")
        self.report_path = self.root / "verification.json"
        self.output = Path(self.temp.name) / "kit.zip"
        self.config = {"schemaVersion": 2, "applicationId": "ru.privatediary", "versionName": "1.0.0",
                       "versionCode": 11, "minSdk": 26, "submissionReady": False,
                       "signing": {"ownerHasKeystoreBackup": False}}
        self.write("release/release-config.json", self.config)
        for name in kit.PUBLIC_FILES:
            self.write("release/signing-kit/" + name, b"Public signing instructions\n")
        self.report = {"artifact": self.apk.name, "distribution": "store", "format": "apk",
                       "bytes": self.apk.stat().st_size, "sha256": hashlib.sha256(self.apk.read_bytes()).hexdigest(),
                       "technicalChecksPassed": True, "candidateOnly": True,
                       "signature": {"verified": False, "unsignedCandidate": True},
                       "manifest": {"variant": "store", "applicationId": "ru.privatediary",
                                    "versionName": "1.0.0", "versionCode": 11, "minSdk": 26, "targetSdk": 35,
                                    "debuggable": False, "testOnly": False, "allowBackup": False,
                                    "permissions": ["android.permission.INTERNET"],
                                    "usesCleartextTraffic": False, "advertisingProcess": ":ads",
                                    "automaticAdInitialization": False, "advertisingProcessHasSeparateUid": False,
                                    "advertisingComponents": ["ru.privatediary.monetization.ads.StoreAdActivity"]},
                       "buildSettings": {"present": True, "distribution": "store", "networkFeatures": True,
                                         "yanAdUnitId": "", "paymentBackendUrl": "", "sellerLegalName": "",
                                         "proTermsUrl": "", "proRefundUrl": "", "proLicensePublicKeySha256": None},
                       "nativeLibraries": [{"name": "lib/arm64-v8a/libsample.so", "bits": 64,
                                            "loadAlignments": [16384]}]}
        self.save_report()
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(kit, "APKSIGNER_JAR_SHA256", hashlib.sha256(self.jar.read_bytes()).hexdigest()).start()
        mock.patch.object(kit, "ANDROID_SDK_NOTICE_SHA256", hashlib.sha256(self.notice.read_bytes()).hexdigest()).start()

    def write(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(json.dumps(value).encode() if isinstance(value, dict) else value)
        return path

    def save_report(self):
        self.report_path.write_text(json.dumps(self.report), encoding="utf-8")

    def build(self, output=None):
        return kit.build_kit(self.root, self.apk, self.jar, self.notice, self.report_path, output or self.output)

    def test_complete_kit_preserves_exact_unsigned_apk_tool_notice_and_honest_status(self):
        result = self.build()
        with zipfile.ZipFile(self.output) as archive:
            manifest = json.loads(archive.read("release-manifest.json"))
            expected = set(kit.PUBLIC_FILES) | {manifest["unsignedApkFile"], "tools/apksigner.jar",
                       "tools/NOTICE-Android-SDK.txt", "release-manifest.json", "verification-report.json", "SOURCE.txt"}
            self.assertEqual(expected, set(archive.namelist()))
            self.assertEqual(self.apk.read_bytes(), archive.read(manifest["unsignedApkFile"]))
            self.assertEqual(self.jar.read_bytes(), archive.read("tools/apksigner.jar"))
            self.assertEqual(self.notice.read_bytes(), archive.read("tools/NOTICE-Android-SDK.txt"))
            self.assertEqual(self.report["sha256"], manifest["unsignedApkSha256"])
            self.assertEqual(kit.APKSIGNER_JAR_SHA256, manifest["apksignerJarSha256"])
            self.assertEqual(("1.0.0", 11, 26), (manifest["versionName"], manifest["versionCode"], manifest["minSdk"]))
            self.assertTrue(manifest["unsignedCandidate"])
            self.assertFalse(manifest["productionSigned"])
            self.assertFalse(manifest["submissionReady"])
            self.assertFalse(manifest["publishedToStore"])
            self.assertIn(b"not recorded", archive.read("SOURCE.txt"))
        digest = hashlib.sha256(self.output.read_bytes()).hexdigest()
        self.assertEqual(digest, result["sha256"])
        self.assertEqual(f"{digest}  kit.zip\n", self.output.with_suffix(".zip.sha256").read_text())

    def test_tampered_apk_fails_without_replacing_existing_output(self):
        self.output.write_bytes(b"previous release archive")
        self.apk.write_bytes(self.apk.read_bytes() + b"tampering")
        # Preserve size in the report to exercise the content check itself.
        self.report["bytes"] = self.apk.stat().st_size
        self.save_report()
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.build()
        self.assertEqual(b"previous release archive", self.output.read_bytes())

    def test_unverified_signed_or_nonstore_reports_fail(self):
        original = copy.deepcopy(self.report)
        cases = [("technicalChecksPassed", False), ("candidateOnly", False),
                 ("distribution", "offline"), ("format", "aab"), ("bytes", 1), ("artifact", "other.apk")]
        for field, value in cases:
            with self.subTest(field=field):
                self.report = copy.deepcopy(original)
                self.report[field] = value
                self.save_report()
                with self.assertRaises(ValueError):
                    self.build()
        for signature in ({"verified": True, "unsignedCandidate": True},
                          {"verified": False, "unsignedCandidate": False},
                          {"verified": False, "unsignedCandidate": True, "certificateSha256": "a" * 64}):
            self.report = copy.deepcopy(original)
            self.report["signature"] = signature
            self.save_report()
            with self.assertRaisesRegex(ValueError, "signature"):
                self.build()

    def test_manifest_and_build_config_must_match_non_debug_store_release(self):
        original = copy.deepcopy(self.report)
        for field, value in (("applicationId", "other.app"), ("variant", "offline"), ("versionName", "0.9.0"),
                             ("versionCode", 10), ("minSdk", 25), ("targetSdk", 34),
                             ("debuggable", True), ("testOnly", True), ("allowBackup", True),
                             ("usesCleartextTraffic", True), ("automaticAdInitialization", True),
                             ("advertisingProcessHasSeparateUid", True), ("advertisingProcess", "main"),
                             ("permissions", []), ("advertisingComponents", [])):
            with self.subTest(field=field):
                self.report = copy.deepcopy(original)
                self.report["manifest"][field] = value
                self.save_report()
                with self.assertRaises(ValueError):
                    self.build()
        for field, value in (("present", False), ("distribution", "offline"), ("networkFeatures", False)):
            self.report = copy.deepcopy(original)
            self.report["buildSettings"][field] = value
            self.save_report()
            with self.assertRaisesRegex(ValueError, "BuildConfig"):
                self.build()

    def test_contradictory_native_library_verification_is_rejected(self):
        original = copy.deepcopy(self.report)
        for native in ([], [{"name": "lib/arm64-v8a/libsample.so", "bits": 64, "loadAlignments": [4096]}],
                       [{"name": "lib/arm64-v8a/libsample.so", "bits": 64, "loadAlignments": [24576]}]):
            self.report = copy.deepcopy(original)
            self.report["nativeLibraries"] = native
            self.save_report()
            with self.assertRaisesRegex(ValueError, "native-library"):
                self.build()

    def test_unknown_signer_or_modified_notice_is_rejected(self):
        self.jar.write_bytes(b"untrusted signing tool")
        with self.assertRaisesRegex(ValueError, "apksigner.jar SHA-256"):
            self.build()
        self.jar.write_bytes(b"verified signer fixture")
        self.notice.write_bytes(self.notice.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "NOTICE SHA-256"):
            self.build()

    def test_keys_passwords_databases_backups_and_extra_report_fields_are_excluded(self):
        secret = b"PRIVATE_SECRET_MUST_NEVER_BE_PACKAGED"
        for name in ("owner.jks", "owner.p12", ".env", "password.txt", "diary.db", "backup.zip",
                     "release/signing-kit/owner.keystore", "release/signing-kit/tools/credentials.json"):
            self.write(name, secret)
        self.report["privateNotes"] = secret.decode()
        self.report["buildSettings"]["paymentBackendUrl"] = "https://example.test/?token=" + secret.decode()
        self.report["buildSettings"]["sellerLegalName"] = secret.decode()
        self.save_report()
        self.build()
        with zipfile.ZipFile(self.output) as archive:
            for name in archive.namelist():
                self.assertNotIn(secret, archive.read(name))
            public_report = json.loads(archive.read("verification-report.json"))
            self.assertTrue(public_report["buildSettings"]["paymentBackendUrlConfigured"])
            self.assertNotIn("paymentBackendUrl", public_report["buildSettings"])

    def test_output_and_sidecar_cannot_replace_inputs_or_hardlink_aliases(self):
        source = self.root / "release/signing-kit/README.ru.md"
        for path in (self.apk, self.jar, self.notice, self.report_path, source):
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.build(path)
        alias = self.root / "alias.zip"
        os.link(self.apk, alias)
        with self.assertRaisesRegex(ValueError, "replace a signing-kit input"):
            self.build(alias)
        # A sidecar collision must be caught even when the ZIP path itself is safe.
        target = self.root / "release.zip"
        sidecar = target.with_suffix(".zip.sha256")
        os.link(self.jar, sidecar)
        with self.assertRaisesRegex(ValueError, "replace a signing-kit input"):
            self.build(target)

    def test_symlink_inputs_parents_and_output_spellings_are_rejected(self):
        alias = self.root / "alias.apk"
        alias.symlink_to(self.apk)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            kit.build_kit(self.root, alias, self.jar, self.notice, self.report_path, self.output)
        link = self.root / "linked"
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            self.build(link / ".." / "alias-output.zip")
        self.output.with_suffix(".zip.sha256").symlink_to(self.jar)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            self.build()
        self.output.with_suffix(".zip.sha256").unlink()
        helper = self.root / "release/signing-kit/sign-release.sh"
        helper.unlink()
        helper.symlink_to(self.root / "release/signing-kit/README.ru.md")
        with self.assertRaisesRegex(ValueError, "Symlink"):
            self.build()

    def test_output_spelling_with_parent_segments_cannot_hide_input_collision(self):
        alias = self.root / "public.zip"
        os.link(self.apk, alias)
        (self.root / "unused").mkdir()
        with self.assertRaisesRegex(ValueError, "replace a signing-kit input"):
            self.build(self.root / "unused/../public.zip")

    def test_archive_is_deterministic_sorted_and_fixed_permissions(self):
        self.build()
        original = self.output.read_bytes()
        helper = self.root / "release/signing-kit/sign-release.sh"
        helper.chmod(0o777)
        os.utime(helper, (1, 1))
        self.build()
        self.assertEqual(original, self.output.read_bytes())
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(sorted(archive.namelist()), archive.namelist())
            for entry in archive.infolist():
                self.assertEqual(kit.ZIP_TIMESTAMP, entry.date_time)
                self.assertEqual(0o644, (entry.external_attr >> 16) & 0o777)

    def test_large_files_are_never_read_whole_and_stream_mutation_is_rejected(self):
        original_read_bytes = Path.read_bytes

        def read_bytes(path):
            if path in (self.apk, self.jar, self.notice):
                self.fail("Large binary input was read into memory")
            return original_read_bytes(path)

        with mock.patch.object(Path, "read_bytes", read_bytes):
            self.build()
        original_copy = kit.shutil.copyfileobj

        def change_stream(source, destination, length):
            # Changes after the initial validation hash must never reach a release archive.
            if source.stream.name == str(self.apk):
                with self.apk.open("ab") as altered:
                    altered.write(b"changed during packaging")
            return original_copy(source, destination, length)

        before = self.output.read_bytes()
        with mock.patch.object(kit.shutil, "copyfileobj", change_stream):
            with self.assertRaisesRegex(ValueError, "changed while packaging"):
                self.build()
        self.assertEqual(before, self.output.read_bytes())

    def test_safe_version_and_default_output_are_derived_from_config(self):
        result = kit.build_kit(self.root, self.apk, self.jar, self.notice, self.report_path)
        self.assertEqual(self.root / "release/artifacts/Private-Diary-1.0.0-Release-Signing-Kit.zip", Path(result["output"]))
        self.config["versionName"] = "../../owner"
        self.write("release/release-config.json", self.config)
        with self.assertRaisesRegex(ValueError, "versionName"):
            self.build()
        # Helpers carry this release's identity; a future config must update them too.
        self.config["versionName"] = self.report["manifest"]["versionName"] = "2.0.0"
        self.config["versionCode"] = self.report["manifest"]["versionCode"] = 50
        self.write("release/release-config.json", self.config)
        self.save_report()
        with self.assertRaisesRegex(ValueError, "not supported by the bundled signing helpers"):
            self.build()


if __name__ == "__main__":
    unittest.main()
