"""Security and format regressions for the release-artifact checker; no Android runtime required."""
import importlib.util
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "verify_release.py"
SPEC = importlib.util.spec_from_file_location("verify_release", SCRIPT)
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


def integer(value):
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out + bytes([value]))


def field(number, value):
    if isinstance(value, int):
        return integer(number << 3) + integer(value)
    if isinstance(value, str):
        value = value.encode()
    return integer((number << 3) | 2) + integer(len(value)) + value


def attribute(name, value, android=True, compiled=False):
    result = field(2, name)
    if android:
        result += field(1, VERIFY.ANDROID)
    if compiled:
        # XmlAttribute.compiled_item -> Item.prim -> Primitive.boolean_value.
        result += field(6, field(7, field(8, int(value))))
    else:
        result += field(3, value)
    return result


def element(name, attributes=(), children=()):
    payload = field(3, name)
    payload += b"".join(field(4, value) for value in attributes)
    payload += b"".join(field(5, value) for value in children)
    return field(1, payload)  # XmlNode.element.


def manifest_xml():
    return ET.fromstring(f'''<manifest xmlns:android="{VERIFY.ANDROID}" package="ru.privatediary"
        android:versionName="1.0.0-rc1" android:versionCode="9">
        <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37" />
        <uses-permission android:name="android.permission.USE_BIOMETRIC" />
        <application android:allowBackup="false"><activity android:name=".MainActivity" /></application>
        </manifest>''')


def elf64(alignment=16384, file_offset=0, address=0):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, file_offset, address, 0, 0, 0, alignment)
    return bytes(data)


class ManifestTests(unittest.TestCase):
    def test_production_manifest_retains_expected_sdk_and_package(self):
        result = VERIFY.check_manifest(manifest_xml(), "ru.privatediary")
        self.assertEqual(37, result["targetSdk"])
        self.assertEqual(26, result["minSdk"])
        self.assertEqual(9, result["versionCode"])
        self.assertFalse(result["debuggable"])

    def test_every_android_permission_declaration_variant_rejects_internet(self):
        for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            with self.subTest(tag=tag):
                root = manifest_xml()
                ET.SubElement(root, tag, {f"{{{VERIFY.ANDROID}}}name": "android.permission.INTERNET"})
                with self.assertRaisesRegex(ValueError, "INTERNET"):
                    VERIFY.check_manifest(root, "ru.privatediary")

    def test_debug_testonly_and_backup_cannot_be_enabled(self):
        for flag in ("debuggable", "testOnly", "allowBackup"):
            root = manifest_xml()
            root.find("application").set(f"{{{VERIFY.ANDROID}}}{flag}", "true")
            with self.subTest(flag=flag), self.assertRaisesRegex(ValueError, flag):
                VERIFY.check_manifest(root, "ru.privatediary")

    def test_test_renderer_and_instrumentation_do_not_leak_into_release(self):
        root = manifest_xml()
        ET.SubElement(root.find("application"), "activity", {f"{{{VERIFY.ANDROID}}}name": ".StoreScreenshotActivity"})
        with self.assertRaisesRegex(ValueError, "preview"):
            VERIFY.check_manifest(root, "ru.privatediary")
        root = manifest_xml()
        ET.SubElement(root, "instrumentation", {f"{{{VERIFY.ANDROID}}}name": "custom.Runner"})
        with self.assertRaisesRegex(ValueError, "Instrumentation"):
            VERIFY.check_manifest(root, "ru.privatediary")

    def test_actual_aapt_text_flags_and_integers_are_decoded(self):
        tree = '''N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=1)
    A: package="ru.privatediary" (Raw: "ru.privatediary")
    A: android:versionCode(0x0101021b)=(type 0x10)0x9
    A: android:versionName(0x0101021c)="1.0.0-rc1" (Raw: "1.0.0-rc1")
    E: uses-sdk (line=2)
      A: android:minSdkVersion(0x0101020c)=(type 0x10)0x1a
      A: android:targetSdkVersion(0x01010270)=(type 0x10)0x25
    E: application (line=3)
      A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff
      A: android:allowBackup(0x01010280)=(type 0x12)0x0'''
        root = VERIFY.aapt_manifest(tree)
        with self.assertRaisesRegex(ValueError, "debuggable"):
            VERIFY.check_manifest(root, "ru.privatediary")
        root.find("application").set(f"{{{VERIFY.ANDROID}}}debuggable", "false")
        self.assertEqual(9, VERIFY.check_manifest(root, "ru.privatediary")["versionCode"])

    def test_aab_compiled_boolean_cannot_hide_debuggable_flag(self):
        encoded = element("manifest", [attribute("package", "ru.privatediary", android=False)], [
            element("application", [attribute("debuggable", True, compiled=True)]),
        ])
        root = VERIFY.aab_manifest(encoded)
        self.assertEqual("true", VERIFY.android_attr(root.find("application"), "debuggable"))
        with self.assertRaisesRegex(ValueError, "debuggable"):
            VERIFY.check_manifest(root, "ru.privatediary")

    def test_duplicate_aab_attributes_and_malformed_wire_are_rejected(self):
        encoded = element("manifest", [attribute("package", "a", android=False), attribute("package", "b", android=False)])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            VERIFY.aab_manifest(encoded)
        for malformed in (b"\x80", b"\x00", b"\x0a\x08a", b"\x0b", b"\xff" * 10):
            with self.subTest(data=malformed), self.assertRaises(ValueError):
                VERIFY.protobuf_fields(malformed)


class BinaryChecksTests(unittest.TestCase):
    def test_16kb_64bit_library_and_bad_4kb_variant(self):
        self.assertEqual(64, VERIFY.elf_alignment(elf64(), "arm64-v8a/libexample.so")["bits"])
        with self.assertRaisesRegex(ValueError, "16-KB"):
            VERIFY.elf_alignment(elf64(4096), "arm64-v8a/libexample.so")
        with self.assertRaisesRegex(ValueError, "16-KB"):
            VERIFY.elf_alignment(elf64(file_offset=4096, address=0), "arm64-v8a/libexample.so")

    def test_native_headers_must_be_within_the_file(self):
        damaged = bytearray(elf64())
        struct.pack_into("<H", damaged, 56, 100)
        with self.assertRaisesRegex(ValueError, "headers"):
            VERIFY.elf_alignment(bytes(damaged), "lib.so")

    def test_bundle_requires_explicit_16kb_or_stronger_alignment(self):
        for alignment in (2, 3):
            VERIFY.check_bundle_alignment(field(2, field(2, field(1, 1) + field(2, alignment))))
        for alignment in (0, 1):
            with self.assertRaisesRegex(ValueError, "PAGE_ALIGNMENT"):
                VERIFY.check_bundle_alignment(field(2, field(2, field(1, 1) + field(2, alignment))))
        with self.assertRaisesRegex(ValueError, "PAGE_ALIGNMENT"):
            VERIFY.check_bundle_alignment(field(2, field(2, field(2, 2))))

    def test_debug_certificate_is_rejected_by_hash_or_certificate_name(self):
        with self.assertRaisesRegex(ValueError, "Android Debug"):
            VERIFY.reject_debug_certificate(VERIFY.DEBUG_CERTIFICATE, "")
        with self.assertRaisesRegex(ValueError, "Android Debug"):
            VERIFY.reject_debug_certificate("a" * 64, "Owner: CN=Android Debug,O=Android,C=US")
        VERIFY.reject_debug_certificate("b" * 64, "Owner: CN=Private Diary")


class LocalKeySafetyTests(unittest.TestCase):
    def test_key_creation_rejects_repository_paths_before_running_keytool(self):
        target = SCRIPT.parent / "must-not-create-this-keystore.jks"
        self.assertFalse(target.exists())
        result = subprocess.run(["bash", str(SCRIPT.with_name("create_release_key.sh")), str(target)],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("outside", result.stdout)
        self.assertFalse(target.exists())

    def test_key_creation_cannot_replace_an_existing_external_file(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "existing.jks"
            path.write_bytes(b"private existing content")
            result = subprocess.run(["bash", str(SCRIPT.with_name("create_release_key.sh")), str(path)],
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(b"private existing content", path.read_bytes())


if __name__ == "__main__":
    unittest.main()
