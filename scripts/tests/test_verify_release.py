"""Security and format regressions for the release-artifact checker; no Android runtime required."""
import importlib.util
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile

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


def dex_fixture(classes=(b"ru/privatediary/MainActivity",), references=()):
    descriptors = [b"L" + name + b";" for name in classes] + list(references)
    count, class_count = len(descriptors), len(classes)
    string_offset, type_offset = 112, 112 + 4 * count
    class_offset = type_offset + 4 * count
    result = bytearray(class_offset + 32 * class_count)
    result[:8] = b"dex\n035\0"
    struct.pack_into("<II", result, 36, 112, 0x12345678)
    struct.pack_into("<IIII", result, 56, count, string_offset, count, type_offset)
    struct.pack_into("<II", result, 96, class_count, class_offset)
    for index, descriptor in enumerate(descriptors):
        struct.pack_into("<I", result, string_offset + index * 4, len(result))
        struct.pack_into("<I", result, type_offset + index * 4, index)
        result.extend(integer(len(descriptor)) + descriptor + b"\0")
    for index in range(class_count):
        struct.pack_into("<I", result, class_offset + index * 32, index)
    struct.pack_into("<I", result, 32, len(result))
    return bytes(result)


def build_config_dex(fields=None, encoded_values=None, duplicate_class=False):
    """Small DEX with real field IDs, delta-encoded class data and static values."""
    if fields is None:
        fields = [("DISTRIBUTION", "Ljava/lang/String;", "store"), ("NETWORK_FEATURES", "Z", True)]
    fields = sorted(fields)
    owner = "Lru/privatediary/BuildConfig;"
    types = sorted({owner, *(descriptor for _, descriptor, _ in fields)})
    strings = sorted({*types, *(name for name, _, _ in fields),
                      *(value for _, _, value in fields if isinstance(value, str))})
    string_offset = 112
    type_offset = string_offset + len(strings) * 4
    field_offset = type_offset + len(types) * 4
    class_offset = field_offset + len(fields) * 8
    class_count = 2 if duplicate_class else 1
    result = bytearray(class_offset + class_count * 32)
    result[:8] = b"dex\n035\0"
    struct.pack_into("<II", result, 36, 112, 0x12345678)
    struct.pack_into("<IIII", result, 56, len(strings), string_offset, len(types), type_offset)
    struct.pack_into("<II", result, 80, len(fields), field_offset if fields else 0)
    struct.pack_into("<II", result, 96, class_count, class_offset)
    for index, value in enumerate(strings):
        struct.pack_into("<I", result, string_offset + index * 4, len(result))
        units = value.encode("utf-16-le")
        result.extend(integer(len(units) // 2))
        for position in range(0, len(units), 2):
            unit = int.from_bytes(units[position:position + 2], "little")
            if 0 < unit < 128:
                result.append(unit)
            elif unit < 2048:
                result.extend((0xc0 | unit >> 6, 0x80 | unit & 63))
            else:
                result.extend((0xe0 | unit >> 12, 0x80 | (unit >> 6) & 63, 0x80 | unit & 63))
        result.append(0)
    for index, descriptor in enumerate(types):
        struct.pack_into("<I", result, type_offset + index * 4, strings.index(descriptor))
    for index, (name, descriptor, _) in enumerate(fields):
        struct.pack_into("<HHI", result, field_offset + index * 8, types.index(owner), types.index(descriptor), strings.index(name))
    class_data = len(result)
    result.extend(integer(len(fields)) + b"\0\0\0")
    for index in range(len(fields)):
        result.extend(integer(0 if index == 0 else 1) + integer(0x19))  # public static final.
    static_values = len(result)
    if encoded_values is None:
        encoded_values = []
        for _, descriptor, value in fields:
            if value is None:
                encoded_values.append(b"\x1e")
            elif descriptor == "Z":
                encoded_values.append(bytes([0x1f | int(value) << 5]))
            elif descriptor == "Ljava/lang/String;":
                index = strings.index(value)
                width = max(1, (index.bit_length() + 7) // 8)
                encoded_values.append(bytes([0x17 | (width - 1) << 5]) + index.to_bytes(width, "little"))
            else:
                kinds = {"B": (0x00, 1), "S": (0x02, 2), "C": (0x03, 2), "I": (0x04, 4), "J": (0x06, 8)}
                kind, width = kinds[descriptor]
                encoded_values.append(bytes([kind | (width - 1) << 5]) + value.to_bytes(width, "little", signed=descriptor != "C"))
    result.extend(integer(len(encoded_values)) + b"".join(encoded_values))
    for index in range(class_count):
        struct.pack_into("<I", result, class_offset + index * 32, types.index(owner))
        struct.pack_into("<II", result, class_offset + index * 32 + 24, class_data, static_values)
    struct.pack_into("<I", result, 32, len(result))
    return bytes(result)


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


def store_manifest_xml():
    root = manifest_xml()
    ET.SubElement(root, "uses-permission", {f"{{{VERIFY.ANDROID}}}name": "android.permission.INTERNET"})
    application = root.find("application")
    application.set(f"{{{VERIFY.ANDROID}}}usesCleartextTraffic", "false")
    # Independent fixture of the audited Mobile Ads 8.6.0 merged manifest. Do not
    # build this from the verifier's allowlist: additions must require a review.
    components = (
        ("activity", "ru.privatediary.monetization.ads.StoreAdActivity", False),
        ("activity", "com.yandex.mobile.ads.common.AdActivity", False),
        ("provider", "com.yandex.mobile.ads.core.initializer.YandexAdsInitializeProvider", True),
        ("provider", "io.appmetrica.analytics.internal.PreloadInfoContentProvider", True),
        ("provider", "com.yandex.mobile.ads.features.debugpanel.data.local.DebugPanelFileProvider", True),
        ("activity", "com.yandex.mobile.ads.features.debugpanel.ui.IntegrationInspectorActivity", True),
        ("service", "io.appmetrica.analytics.internal.AppMetricaService", False),
        ("activity", "com.google.android.gms.common.api.GoogleApiActivity", True),
    )
    for tag, name, disabled in components:
        attributes = {f"{{{VERIFY.ANDROID}}}name": name, f"{{{VERIFY.ANDROID}}}process": ":ads",
                      f"{{{VERIFY.ANDROID}}}exported": "false"}
        if disabled:
            attributes[f"{{{VERIFY.ANDROID}}}enabled"] = "false"
        ET.SubElement(application, tag, attributes)
    ET.SubElement(application, "meta-data", {
        f"{{{VERIFY.ANDROID}}}name": "com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION",
        f"{{{VERIFY.ANDROID}}}value": "false",
    })
    return root


def ad_components(root):
    return [item for item in root.find("application") if VERIFY.android_attr(item, "process") == ":ads"]


def manifest_protobuf(root):
    attrs = [attribute(name.removeprefix(f"{{{VERIFY.ANDROID}}}"), value,
                       android=name.startswith(f"{{{VERIFY.ANDROID}}}")) for name, value in root.attrib.items()]
    return element(root.tag, attrs, [manifest_protobuf(child) for child in root])


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
        self.assertEqual("offline", result["variant"])

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


class StoreManifestTests(unittest.TestCase):
    def test_store_requires_the_exact_audited_component_set_in_an_ad_process(self):
        result = VERIFY.check_manifest(store_manifest_xml(), "ru.privatediary", "store")
        self.assertEqual("store", result["variant"])
        self.assertEqual(8, len(result["advertisingComponents"]))
        self.assertEqual(":ads", result["advertisingProcess"])
        self.assertFalse(result["automaticAdInitialization"])
        self.assertFalse(result["advertisingProcessHasSeparateUid"])
        self.assertFalse(result["usesCleartextTraffic"])

    def test_store_requires_internet_and_explicit_https_only_policy(self):
        root = store_manifest_xml()
        for permission in list(root.findall("uses-permission")):
            if VERIFY.android_attr(permission, "name") == "android.permission.INTERNET":
                root.remove(permission)
        with self.assertRaisesRegex(ValueError, "must declare INTERNET"):
            VERIFY.check_manifest(root, "ru.privatediary", "store")
        for value in (None, "true", "@bool/safe_network"):
            root = store_manifest_xml()
            if value is None:
                del root.find("application").attrib[f"{{{VERIFY.ANDROID}}}usesCleartextTraffic"]
            else:
                root.find("application").set(f"{{{VERIFY.ANDROID}}}usesCleartextTraffic", value)
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "usesCleartextTraffic"):
                VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_advertising_id_and_all_adservices_permissions_are_forbidden(self):
        for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            for permission in ("com.google.android.gms.permission.AD_ID", "android.permission.ACCESS_ADSERVICES_AD_ID",
                               "android.permission.ACCESS_ADSERVICES_ATTRIBUTION", "android.permission.ACCESS_ADSERVICES_TOPICS",
                               "android.permission.ACCESS_ADSERVICES_CUSTOM_AUDIENCE"):
                for variant in ("offline", "store"):
                    root = store_manifest_xml() if variant == "store" else manifest_xml()
                    ET.SubElement(root, tag, {f"{{{VERIFY.ANDROID}}}name": permission})
                    with self.subTest(tag=tag, permission=permission, variant=variant), self.assertRaisesRegex(ValueError, "AdServices"):
                        VERIFY.check_manifest(root, "ru.privatediary", variant)

    def test_no_ad_component_can_move_to_the_diary_or_another_process(self):
        for index in range(8):
            for process in (None, "ru.privatediary", ":another"):
                root = store_manifest_xml()
                component = ad_components(root)[index]
                if process is None:
                    del component.attrib[f"{{{VERIFY.ANDROID}}}process"]
                else:
                    component.set(f"{{{VERIFY.ANDROID}}}process", process)
                with self.subTest(component=VERIFY.android_attr(component, "name"), process=process), self.assertRaisesRegex(ValueError, "outside :ads"):
                    VERIFY.check_manifest(root, "ru.privatediary", "store")
        root = store_manifest_xml()
        root.find("application").set(f"{{{VERIFY.ANDROID}}}process", ":ads")
        with self.assertRaisesRegex(ValueError, "main process"):
            VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_no_ad_component_can_be_exported_or_rely_on_export_defaults(self):
        for index in range(8):
            for exported in (None, "true"):
                root = store_manifest_xml()
                component = ad_components(root)[index]
                if exported is None:
                    del component.attrib[f"{{{VERIFY.ANDROID}}}exported"]
                else:
                    component.set(f"{{{VERIFY.ANDROID}}}exported", exported)
                with self.subTest(index=index, exported=exported), self.assertRaisesRegex(ValueError, "Exported"):
                    VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_initialization_providers_inspector_and_gms_ui_must_stay_disabled(self):
        disabled = [VERIFY.android_attr(item, "name") for item in ad_components(store_manifest_xml())
                    if VERIFY.android_attr(item, "enabled") == "false"]
        self.assertEqual(5, len(disabled))
        for name in disabled:
            for state in (None, "true"):
                root = store_manifest_xml()
                component = next(item for item in ad_components(root) if VERIFY.android_attr(item, "name") == name)
                if state is None:
                    del component.attrib[f"{{{VERIFY.ANDROID}}}enabled"]
                else:
                    component.set(f"{{{VERIFY.ANDROID}}}enabled", state)
                with self.subTest(name=name, state=state), self.assertRaisesRegex(ValueError, "enabled state"):
                    VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_new_missing_duplicate_and_wrong_kind_sdk_components_require_review(self):
        for index in range(8):
            root = store_manifest_xml()
            root.find("application").remove(ad_components(root)[index])
            with self.subTest(missing=index), self.assertRaisesRegex(ValueError, "incomplete"):
                VERIFY.check_manifest(root, "ru.privatediary", "store")
        for name, process in (("com.yandex.mobile.ads.NewService", ""),
                              ("io.appmetrica.analytics.NewProvider", ":ads"),
                              ("ru.privatediary.monetization.ads.OtherHost", ""),
                              ("com.google.firebase.messaging.MessagingService", ""),
                              ("example.UnknownAdService", ":ads")):
            root = store_manifest_xml()
            ET.SubElement(root.find("application"), "service", {
                f"{{{VERIFY.ANDROID}}}name": name, f"{{{VERIFY.ANDROID}}}process": process})
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Unexpected"):
                VERIFY.check_manifest(root, "ru.privatediary", "store")
        root = store_manifest_xml()
        first = ad_components(root)[0]
        ET.SubElement(root.find("application"), first.tag, first.attrib)
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            VERIFY.check_manifest(root, "ru.privatediary", "store")
        root = store_manifest_xml()
        ad_components(root)[0].tag = "service"
        with self.assertRaisesRegex(ValueError, "type"):
            VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_sdk_activity_aliases_cannot_bypass_the_component_policy(self):
        root = store_manifest_xml()
        ET.SubElement(root.find("application"), "activity-alias", {
            f"{{{VERIFY.ANDROID}}}name": ".AdAlias",
            f"{{{VERIFY.ANDROID}}}targetActivity": ".monetization.ads.StoreAdActivity",
            f"{{{VERIFY.ANDROID}}}exported": "true",
        })
        with self.assertRaisesRegex(ValueError, "aliases"):
            VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_automatic_initialization_requires_one_explicit_false_metadata_value(self):
        for value in (None, "true", "@bool/false"):
            root = store_manifest_xml()
            metadata = root.find("application/meta-data")
            if value is None:
                root.find("application").remove(metadata)
            else:
                metadata.set(f"{{{VERIFY.ANDROID}}}value", value)
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "automatic initialization"):
                VERIFY.check_manifest(root, "ru.privatediary", "store")
        root = store_manifest_xml()
        ET.SubElement(root.find("application"), "meta-data", root.find("application/meta-data").attrib)
        with self.assertRaisesRegex(ValueError, "automatic initialization"):
            VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_sdk_package_discovery_queries_cannot_return(self):
        root = store_manifest_xml()
        ET.SubElement(root, "queries")
        with self.assertRaisesRegex(ValueError, "queries"):
            VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_store_debug_backup_and_preview_are_still_forbidden(self):
        for flag in ("debuggable", "testOnly", "allowBackup"):
            root = store_manifest_xml()
            root.find("application").set(f"{{{VERIFY.ANDROID}}}{flag}", "true")
            with self.subTest(flag=flag), self.assertRaisesRegex(ValueError, flag):
                VERIFY.check_manifest(root, "ru.privatediary", "store")
        root = store_manifest_xml()
        ET.SubElement(root.find("application"), "activity", {f"{{{VERIFY.ANDROID}}}name": ".StoreScreenshotActivity"})
        with self.assertRaisesRegex(ValueError, "preview"):
            VERIFY.check_manifest(root, "ru.privatediary", "store")

    def test_offline_manifest_never_accepts_ad_components_even_without_internet(self):
        for source in ad_components(store_manifest_xml()):
            root = manifest_xml()
            ET.SubElement(root.find("application"), source.tag, source.attrib)
            with self.subTest(name=VERIFY.android_attr(source, "name")), self.assertRaisesRegex(ValueError, "offline manifest"):
                VERIFY.check_manifest(root, "ru.privatediary")


class BuildSettingsTests(unittest.TestCase):
    def test_extracts_actual_static_literals_and_only_hashes_the_public_key(self):
        key = "synthetic public key\n"
        fields = [
            ("DEBUG", "Z", False), ("VERSION_CODE", "I", 42),
            ("DISTRIBUTION", "Ljava/lang/String;", "store"), ("NETWORK_FEATURES", "Z", True),
            ("YAN_AD_UNIT_ID", "Ljava/lang/String;", "R-M-123456-1"),
            ("PAYMENT_BACKEND_URL", "Ljava/lang/String;", "https://pay.example.org"),
            ("PRO_LICENSE_PUBLIC_KEY", "Ljava/lang/String;", key),
            ("SELLER_LEGAL_NAME", "Ljava/lang/String;", "ООО Дневник 📘\0"),
            ("PRO_TERMS_URL", "Ljava/lang/String;", "https://example.org/terms"),
            ("PRO_REFUND_URL", "Ljava/lang/String;", "https://example.org/refund"),
        ]
        settings = VERIFY.extract_build_settings(build_config_dex(fields))
        self.assertEqual({
            "present": True, "distribution": "store", "networkFeatures": True,
            "yanAdUnitId": "R-M-123456-1", "paymentBackendUrl": "https://pay.example.org",
            "proLicensePublicKeySha256": hashlib.sha256(key.encode()).hexdigest(),
            "sellerLegalName": "ООО Дневник 📘\0", "proTermsUrl": "https://example.org/terms",
            "proRefundUrl": "https://example.org/refund",
        }, settings)
        self.assertNotIn(key, repr(settings))
        self.assertNotIn("PRO_LICENSE_PUBLIC_KEY", settings)

    def test_absent_class_and_missing_literals_are_distinct(self):
        self.assertIsNone(VERIFY.extract_build_settings(dex_fixture()))
        settings = VERIFY.extract_build_settings(build_config_dex(encoded_values=[]))
        self.assertTrue(settings["present"])
        self.assertFalse(settings["networkFeatures"])
        for name in ("distribution", "yanAdUnitId", "paymentBackendUrl", "sellerLegalName", "proTermsUrl", "proRefundUrl"):
            self.assertEqual("", settings[name])
        self.assertIsNone(settings["proLicensePublicKeySha256"])
        settings = VERIFY.extract_build_settings(build_config_dex([("PRO_LICENSE_PUBLIC_KEY", "Ljava/lang/String;", " \n ")]))
        self.assertIsNone(settings["proLicensePublicKeySha256"])

    def test_common_primitive_encodings_and_null_strings_are_bounded(self):
        fields = [("A_BYTE", "B", -2), ("B_SHORT", "S", -300), ("C_CHAR", "C", 0xffff),
                  ("D_INT", "I", -50000), ("E_LONG", "J", -(1 << 40)),
                  ("F_FLOAT", "F", 0), ("G_DOUBLE", "D", 0), ("PRO_LICENSE_PUBLIC_KEY", "Ljava/lang/String;", None)]
        values = [b"\x00\xfe", b"\x22\xd4\xfe", b"\x23\xff\xff", b"\x64\xb0\x3c\xff\xff",
                  b"\xe6" + (-(1 << 40)).to_bytes(8, "little", signed=True), b"\x10\x3f", b"\x11\x3f", b"\x1e"]
        self.assertIsNone(VERIFY.extract_build_settings(build_config_dex(fields, values))["proLicensePublicKeySha256"])

    def test_malformed_member_tables_and_indices_are_rejected(self):
        original = build_config_dex()
        field_offset = struct.unpack_from("<I", original, 84)[0]
        class_offset = struct.unpack_from("<I", original, 100)[0]
        class_data, static_values = struct.unpack_from("<II", original, class_offset + 24)
        cases = [
            (84, "<I", len(original) + 4, "member table"),
            (field_offset, "<H", 0xffff, "field owner"),
            (field_offset + 2, "<H", 0xffff, "field type index"),
            (field_offset + 4, "<I", 0xffffffff, "string index"),
            (class_offset + 24, "<I", len(original), "class data offset"),
            (class_offset + 28, "<I", len(original), "static values offset"),
            (class_data, "<B", 127, "member counts"),
            (class_data + 4, "<B", 127, "field index"),
            (class_data + 6, "<B", 0, "Duplicate.*member"),
            (class_data + 5, "<B", 1, "static field flags"),
            (static_values, "<B", 127, "Too many"),
            (static_values + 2, "<B", 255, "string index"),
        ]
        for offset, format_, value, error in cases:
            data = bytearray(original)
            struct.pack_into(format_, data, offset, value)
            with self.subTest(error=error), self.assertRaisesRegex(ValueError, error):
                VERIFY.extract_build_settings(bytes(data))

    def test_malformed_value_arguments_lengths_and_types_are_rejected(self):
        for value in (b"\x97\0\0\0", b"\xff", b"\x3e", b"\x17", b"\x04\0", b"\x1c"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                VERIFY.extract_build_settings(build_config_dex([("DISTRIBUTION", "Ljava/lang/String;", "store")], [value]))
        data = bytearray(build_config_dex())
        class_offset = struct.unpack_from("<I", data, 100)[0]
        class_data = struct.unpack_from("<I", data, class_offset + 24)[0]
        data[class_data:class_data + 5] = b"\xff" * 5
        with self.assertRaisesRegex(ValueError, "ULEB128"):
            VERIFY.extract_build_settings(bytes(data))
        with self.assertRaisesRegex(ValueError, "settings field type"):
            VERIFY.extract_build_settings(build_config_dex([("NETWORK_FEATURES", "I", 1)]))

    def test_duplicate_build_config_and_duplicate_field_names_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate.*BuildConfig"):
            VERIFY.extract_build_settings(build_config_dex(duplicate_class=True))
        with self.assertRaisesRegex(ValueError, "Duplicate.*field name"):
            VERIFY.extract_build_settings(build_config_dex([("DISTRIBUTION", "Ljava/lang/String;", "store")] * 2))

    def test_malformed_modified_utf8_and_string_lengths_are_rejected(self):
        original = build_config_dex([("DISTRIBUTION", "Ljava/lang/String;", "store")])
        strings, string_offset = struct.unpack_from("<II", original, 56)
        target = next(struct.unpack_from("<I", original, string_offset + index * 4)[0] for index in range(strings)
                      if original[struct.unpack_from("<I", original, string_offset + index * 4)[0]:].startswith(b"\x05store\0"))
        for offset, value in ((target, 6), (target + 1, 0xf0), (target + 2, 0)):
            data = bytearray(original)
            data[offset] = value
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                VERIFY.extract_build_settings(bytes(data))


class BinaryChecksTests(unittest.TestCase):
    def test_store_bytecode_only_exempts_reviewed_transitive_classes(self):
        for class_name in [p + b"Example" for p in VERIFY.STORE_ALLOWED_DEX_PREFIXES] + list(VERIFY.STORE_ALLOWED_DEX_CLASSES):
            with self.subTest(class_name=class_name):
                data = dex_fixture((class_name,))
                VERIFY.check_dex(data, "classes.dex", "store")
                with self.assertRaisesRegex(ValueError, "Forbidden"):
                    VERIFY.check_dex(data, "classes.dex", "offline")
        for prefix in (b"com/google/firebase/", b"io/sentry/", b"com/bugsnag/", b"com/google/android/gms/ads/",
                       b"com/applovin/", b"com/my/target/", b"com/yandex/metrica/", b"retrofit2/",
                       b"io/ktor/client/", b"com/android/volley/"):
            for variant in ("offline", "store"):
                with self.subTest(prefix=prefix, variant=variant), self.assertRaisesRegex(ValueError, "Forbidden"):
                    VERIFY.check_dex(dex_fixture((prefix + b"Example",)), "classes2.dex", variant)

    def test_optional_class_names_do_not_count_as_installed_libraries(self):
        data = dex_fixture(references=(b"Lcom/google/firebase/analytics/FirebaseAnalytics;", b"com.facebook.ads.AdView"))
        VERIFY.check_dex(data, "classes.dex", "offline")
        VERIFY.check_dex(data, "classes.dex", "store")

    def test_malformed_dex_cannot_skip_the_sdk_guard(self):
        for data in (b"dex\n035\0", dex_fixture()[:115], b"invalid" * 40):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                VERIFY.check_dex(data, "classes.dex", "store")
        data = bytearray(dex_fixture())
        struct.pack_into("<I", data, 100, len(data) + 4)
        with self.assertRaisesRegex(ValueError, "outside file"):
            VERIFY.check_dex(bytes(data), "classes.dex")

    def test_synthetic_bundle_report_tracks_the_requested_distribution_and_shared_uid_limit(self):
        model = b"synthetic tiny model fixture"
        hashes = {name: hashlib.sha256(model).hexdigest() for name in ("model_int8.onnx", "tokenizer.json")}
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(VERIFY, "MODEL_HASHES", hashes):
            for variant, root in (("offline", manifest_xml()), ("store", store_manifest_xml())):
                path = Path(folder) / f"{variant}.aab"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("base/manifest/AndroidManifest.xml", manifest_protobuf(root))
                    archive.writestr("BundleConfig.pb", field(2, field(2, field(1, 1) + field(2, 2))))
                    for name in hashes:
                        archive.writestr("base/assets/model/" + name, model)
                    archive.writestr("base/dex/classes.dex", dex_fixture())
                    archive.writestr("base/lib/arm64-v8a/libexample.so", elf64())
                report = VERIFY.verify(path, allow_unsigned=True, variant=variant)
                self.assertEqual(variant, report["distribution"])
                self.assertEqual(variant, report["manifest"]["variant"])
                self.assertTrue(report["technicalChecksPassed"])
                self.assertTrue(report["candidateOnly"])
                self.assertEqual({"present": False}, report["buildSettings"])
                if variant == "store":
                    self.assertTrue(any("same" in text or "shares" in text for text in report["limitations"]))
                    with self.assertRaisesRegex(ValueError, "INTERNET|SDK component in offline"):
                        VERIFY.verify(path, allow_unsigned=True)  # Offline is the fail-closed default.

    def test_apk_and_bundle_report_build_settings_from_later_dex_and_reject_duplicates(self):
        model = b"synthetic tiny model fixture"
        hashes = {name: hashlib.sha256(model).hexdigest() for name in ("model_int8.onnx", "tokenizer.json")}
        bytecode = build_config_dex()
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(VERIFY, "MODEL_HASHES", hashes), \
                mock.patch.object(VERIFY, "signature", return_value={"verified": False, "unsignedCandidate": True}), \
                mock.patch.object(VERIFY, "sdk_tool", side_effect=lambda name: name), \
                mock.patch.object(VERIFY, "run", return_value=subprocess.CompletedProcess([], 0, "")), \
                mock.patch.object(VERIFY, "aapt_manifest", return_value=store_manifest_xml()):
            for extension in ("apk", "aab"):
                with self.subTest(format=extension):
                    bundle = extension == "aab"
                    path = Path(folder) / f"store.{extension}"
                    prefix = "base/" if bundle else ""
                    dex_prefix = "base/dex/" if bundle else ""
                    with zipfile.ZipFile(path, "w") as archive:
                        if bundle:
                            archive.writestr("base/manifest/AndroidManifest.xml", manifest_protobuf(store_manifest_xml()))
                            archive.writestr("BundleConfig.pb", field(2, field(2, field(1, 1) + field(2, 2))))
                        for name in hashes:
                            archive.writestr(prefix + "assets/model/" + name, model)
                        archive.writestr(dex_prefix + "classes.dex", dex_fixture())
                        archive.writestr(dex_prefix + "classes2.dex", bytecode)
                        archive.writestr(prefix + "lib/arm64-v8a/libexample.so", elf64())
                    report = VERIFY.verify(path, allow_unsigned=True, variant="store")
                    self.assertEqual(VERIFY.extract_build_settings(bytecode), report["buildSettings"])
                    with zipfile.ZipFile(path, "a") as archive:
                        # Feature-module DEX is scanned too, rather than only base/.
                        archive.writestr("feature/dex/classes.dex" if bundle else "classes3.dex", bytecode)
                    with self.assertRaisesRegex(ValueError, "Duplicate.*BuildConfig across DEX"):
                        VERIFY.verify(path, allow_unsigned=True, variant="store")

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
