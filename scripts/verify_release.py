#!/usr/bin/env python3
"""Local checks for the exact offline/store APK/AAB, using Python, a JDK and Android SDK tools.

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
    b"com/ironsource/", b"com/google/firebase/",
    b"io/appmetrica/", b"com/yandex/metrica/", b"io/sentry/", b"com/bugsnag/",
    b"com/microsoft/appcenter/", b"okhttp3/", b"retrofit2/", b"io/ktor/client/", b"com/android/volley/",
)
# Reviewed transitive classes of Mobile Ads 8.6.0 / AppMetrica 8.6.0.
# Optional SDK names in reflection strings are not installed class definitions.
STORE_ALLOWED_DEX_PREFIXES = (b"com/yandex/mobile/ads/", b"io/appmetrica/", b"okhttp3/",
                              b"com/google/android/gms/ads/identifier/")
STORE_ALLOWED_DEX_CLASSES = {b"com/google/firebase/FirebaseException", b"com/google/firebase/FirebaseApiNotAvailableException",
                             b"com/google/firebase/FirebaseExceptionMapper"}
# name -> (component kind, must be disabled). Includes the app-owned ad host.
STORE_AD_COMPONENTS = {
    "ru.privatediary.monetization.ads.StoreAdActivity": ("activity", False),
    "com.yandex.mobile.ads.common.AdActivity": ("activity", False),
    "com.yandex.mobile.ads.core.initializer.YandexAdsInitializeProvider": ("provider", True),
    "io.appmetrica.analytics.internal.PreloadInfoContentProvider": ("provider", True),
    "com.yandex.mobile.ads.features.debugpanel.data.local.DebugPanelFileProvider": ("provider", True),
    "com.yandex.mobile.ads.features.debugpanel.ui.IntegrationInspectorActivity": ("activity", True),
    "io.appmetrica.analytics.internal.AppMetricaService": ("service", False),
    "com.google.android.gms.common.api.GoogleApiActivity": ("activity", True),
}
SDK_COMPONENT_PREFIXES = tuple(prefix.decode().replace("/", ".") for prefix in BANNED_DEX_PREFIXES) + (
    "com.yandex.", "com.monetization.ads.", "yads.", "com.google.android.gms.",
    "ru.privatediary.monetization.ads.",
)
AUTOMATIC_AD_INITIALIZATION = "com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION"
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")
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


def component_name(name: str, package: str) -> str:
    if name.startswith("."):
        return package + name
    return name if "." in name or not name else package + "." + name


def check_store_ad_components(application: ET.Element, package: str) -> list[str]:
    require(android_attr(application, "process") in ("", package), "The diary application must retain its main process")
    seen = set()
    for component in application.iter():
        if component.tag not in COMPONENT_TAGS:
            continue
        name = component_name(android_attr(component, "name"), package)
        target = component_name(android_attr(component, "targetActivity"), package)
        require(not target.startswith(SDK_COMPONENT_PREFIXES), "Advertising component aliases are forbidden")
        in_ad_process = android_attr(component, "process") == ":ads"
        if not name.startswith(SDK_COMPONENT_PREFIXES) and not in_ad_process:
            continue
        require(name in STORE_AD_COMPONENTS, f"Unexpected advertising SDK component: {name}")
        require(name not in seen, f"Duplicate advertising SDK component: {name}")
        kind, disabled = STORE_AD_COMPONENTS[name]
        require(component.tag == kind, f"Unexpected advertising component type: {name}")
        require(in_ad_process, f"Advertising SDK component outside :ads: {name}")
        require(android_attr(component, "exported") in ("false", "0"), f"Exported advertising SDK component: {name}")
        enabled = android_attr(component, "enabled", "true")
        require(enabled in (("false", "0") if disabled else ("true", "1")),
                f"Advertising SDK component has unexpected enabled state: {name}")
        seen.add(name)
    require(seen == set(STORE_AD_COMPONENTS), "The store advertising component allowlist is incomplete")
    automatic = [item for item in application.iter("meta-data")
                 if android_attr(item, "name") == AUTOMATIC_AD_INITIALIZATION]
    require(len(automatic) == 1 and android_attr(automatic[0], "value") in ("false", "0"),
            "Advertising SDK automatic initialization must be explicitly disabled")
    return sorted(seen)


def check_manifest(root: ET.Element, expected_package: str, variant: str = "offline") -> dict:
    require(variant in ("offline", "store"), "Unknown distribution variant")
    require(root.tag == "manifest" and root.get("package") == expected_package, "Unexpected application ID")
    permissions = []
    for element in root.iter():
        if element.tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            permission = android_attr(element, "name")
            require(bool(permission) and not permission.startswith(("@", "0x")), "Unresolved permission name")
            require(variant == "store" or permission != "android.permission.INTERNET", "INTERNET permission is forbidden")
            require(permission != "com.google.android.gms.permission.AD_ID" and
                    not permission.startswith("android.permission.ACCESS_ADSERVICES"),
                    "Advertising identifier and AdServices permissions are forbidden")
            permissions.append(permission)
        if element.tag in (*COMPONENT_TAGS, "instrumentation"):
            component = component_name(android_attr(element, "name"), expected_package)
            require(not re.search(r"(?:preview|screenshot|androidtest).*activity|androidx\.test\.", component, re.I),
                    "Test or store-preview component found in production manifest")
            if variant == "offline":
                target = component_name(android_attr(element, "targetActivity"), expected_package)
                require(not component.startswith(SDK_COMPONENT_PREFIXES) and not target.startswith(SDK_COMPONENT_PREFIXES),
                        f"Advertising/analytics/network SDK component in offline manifest: {component}")
        if element.tag == "instrumentation":
            raise ValueError("Instrumentation must not be present in the production artifact")
    application = root.find("application")
    require(application is not None, "Application element is missing")
    for flag in ("debuggable", "testOnly", "allowBackup"):
        value = android_attr(application, flag, "false")
        require(value in ("false", "0"), f"android:{flag} must be false")
    ad_components = []
    if variant == "store":
        require("android.permission.INTERNET" in permissions, "The store variant must declare INTERNET")
        require(android_attr(application, "usesCleartextTraffic") in ("false", "0"),
                "Store usesCleartextTraffic must be explicitly false")
        require(root.find("queries") is None, "Installed-app discovery queries are forbidden in the store variant")
        ad_components = check_store_ad_components(application, expected_package)
    sdk = root.find("uses-sdk")
    require(sdk is not None, "SDK declaration is missing")
    minimum = int(android_attr(sdk, "minSdkVersion"))
    target = int(android_attr(sdk, "targetSdkVersion"))
    require(minimum == 26 and target >= 35, "Unexpected minSdk or obsolete targetSdk")
    result = {"applicationId": expected_package, "variant": variant, "versionName": android_attr(root, "versionName"),
            "versionCode": int(android_attr(root, "versionCode")), "minSdk": minimum, "targetSdk": target,
            "permissions": sorted(permissions), "debuggable": False, "testOnly": False, "allowBackup": False}
    if variant == "store":
        result.update(usesCleartextTraffic=False, advertisingProcess=":ads", advertisingComponents=ad_components,
                      automaticAdInitialization=False, advertisingProcessHasSeparateUid=False)
    return result


def dex_classes(data: bytes) -> list[bytes]:
    """Read declared DEX classes, excluding references and SDK-presence probes."""
    require(len(data) >= 112 and data[:4] == b"dex\n" and data[4:8] in
            (b"035\0", b"037\0", b"038\0", b"039\0", b"040\0"), "Invalid or unsupported DEX header")
    require(struct.unpack_from("<III", data, 32) == (len(data), 112, 0x12345678), "Invalid DEX size/endian header")
    strings, string_offset, types, type_offset = struct.unpack_from("<IIII", data, 56)
    classes, class_offset = struct.unpack_from("<II", data, 96)
    require(0 < classes <= types <= strings, "Invalid DEX table counts")
    for count, offset, size in ((strings, string_offset, 4), (types, type_offset, 4), (classes, class_offset, 32)):
        require(offset >= 112 and offset % 4 == 0 and count <= (len(data) - offset) // size, "DEX table outside file")
    result = []
    for index in range(classes):
        type_index = struct.unpack_from("<I", data, class_offset + index * 32)[0]
        require(type_index < types, "Invalid DEX class type index")
        string_index = struct.unpack_from("<I", data, type_offset + type_index * 4)[0]
        require(string_index < strings, "Invalid DEX descriptor index")
        start = struct.unpack_from("<I", data, string_offset + string_index * 4)[0]
        require(112 <= start < len(data), "Invalid DEX string offset")
        _, start = varint(data, start)
        end = data.find(b"\0", start, min(len(data), start + 65536))
        require(end > start and data[start:start + 1] == b"L" and data[end - 1:end] == b";", "Invalid DEX class descriptor")
        result.append(data[start + 1:end - 1])
    return result


class _DexBuildConfigReader:
    """Bounded reader for BuildConfig's encoded static literals, not a DEX audit."""

    def __init__(self, data: bytes):
        self.data = data
        self.strings, self.string_offset, self.types, self.type_offset = struct.unpack_from("<IIII", data, 56)
        self.protos, proto_offset, self.fields, self.field_offset, self.methods, self.method_offset = struct.unpack_from(
            "<IIIIII", data, 72)
        for count, offset, size in ((self.protos, proto_offset, 12), (self.fields, self.field_offset, 8),
                                    (self.methods, self.method_offset, 8)):
            require((count == 0 and offset == 0) or
                    (count > 0 and offset >= 112 and offset % 4 == 0 and count <= (len(data) - offset) // size),
                    "DEX member table outside file")
        self.string_cache: dict[int, str] = {}

    def uleb(self, offset: int) -> tuple[int, int]:
        value = 0
        for count in range(5):
            require(0 <= offset < len(self.data), "Truncated DEX ULEB128")
            byte = self.data[offset]
            offset += 1
            require(count < 4 or byte <= 15, "Oversized DEX ULEB128")
            value |= (byte & 127) << (count * 7)
            if not byte & 128:
                return value, offset
        raise ValueError("Oversized DEX ULEB128")

    def string(self, index: int) -> str:
        require(0 <= index < self.strings, "Invalid DEX string index")
        if index in self.string_cache:
            return self.string_cache[index]
        offset = struct.unpack_from("<I", self.data, self.string_offset + index * 4)[0]
        require(112 <= offset < len(self.data), "Invalid DEX string offset")
        length, offset = self.uleb(offset)
        require(length <= 1024 * 1024, "DEX string is unexpectedly large")
        # DEX uses modified UTF-8 over UTF-16 code units, including C0 80 for NUL
        # and two three-byte surrogate encodings for supplementary characters.
        units = bytearray()
        for _ in range(length):
            require(offset < len(self.data), "Truncated DEX string")
            first = self.data[offset]
            offset += 1
            if 0 < first < 128:
                unit = first
            elif 0xc0 <= first <= 0xef:
                count = 1 if first < 0xe0 else 2
                require(offset + count <= len(self.data), "Truncated DEX string")
                tail = self.data[offset:offset + count]
                require(all(byte & 0xc0 == 0x80 for byte in tail), "Invalid DEX modified UTF-8")
                unit = first & (0x1f if count == 1 else 0x0f)
                for byte in tail:
                    unit = (unit << 6) | (byte & 63)
                require((count == 1 and (unit == 0 or unit >= 128)) or (count == 2 and unit >= 2048),
                        "Invalid DEX modified UTF-8")
                offset += count
            else:
                raise ValueError("Invalid DEX modified UTF-8")
            units.extend(struct.pack("<H", unit))
        require(offset < len(self.data) and self.data[offset] == 0, "Invalid DEX string length/terminator")
        try:
            value = units.decode("utf-16-le", errors="strict")
        except UnicodeDecodeError as error:
            raise ValueError("Invalid DEX string Unicode") from error
        self.string_cache[index] = value
        return value

    def type(self, index: int) -> str:
        require(0 <= index < self.types, "Invalid DEX field type index")
        return self.string(struct.unpack_from("<I", self.data, self.type_offset + index * 4)[0])

    def value(self, offset: int) -> tuple[int, object, int]:
        require(112 <= offset < len(self.data), "Truncated DEX static value")
        header = self.data[offset]
        offset += 1
        kind, argument = header & 31, header >> 5
        if kind in (0x1e, 0x1f):  # VALUE_NULL / VALUE_BOOLEAN have no payload.
            require(argument <= (1 if kind == 0x1f else 0), "Invalid DEX static value argument")
            return kind, bool(argument) if kind == 0x1f else None, offset
        widths = {0x00: 1, 0x02: 2, 0x03: 2, 0x04: 4, 0x06: 8, 0x10: 4, 0x11: 8, 0x17: 4}
        require(kind in widths and argument < widths[kind], "Unsupported or invalid DEX static value")
        width = argument + 1
        require(width <= len(self.data) - offset, "Truncated DEX static value")
        raw = self.data[offset:offset + width]
        offset += width
        if kind == 0x17:  # VALUE_STRING stores a string_id index.
            return kind, self.string(int.from_bytes(raw, "little")), offset
        if kind in (0x10, 0x11):  # Floating-point payloads are right-zero-extended.
            bits = int.from_bytes(raw, "little") << ((widths[kind] - width) * 8)
            value = struct.unpack("<f" if kind == 0x10 else "<d", bits.to_bytes(widths[kind], "little"))[0]
        else:
            value = int.from_bytes(raw, "little", signed=kind != 0x03)
        return kind, value, offset


def extract_build_settings(data: bytes) -> dict | None:
    """Read ru.privatediary.BuildConfig static literals; do not execute its code.

    Missing fields retain empty/default report values. This deliberately reads a
    bounded subset of DEX (primitive/String literals), not arbitrary initializers.
    """
    require(len(data) <= 64 * 1024 * 1024, "DEX file is unexpectedly large")
    classes = dex_classes(data)
    matches = [index for index, name in enumerate(classes) if name == b"ru/privatediary/BuildConfig"]
    require(len(matches) <= 1, "Duplicate ru.privatediary.BuildConfig class")
    if not matches:
        return None
    reader = _DexBuildConfigReader(data)
    class_offset = struct.unpack_from("<I", data, 100)[0] + matches[0] * 32
    owner = struct.unpack_from("<I", data, class_offset)[0]
    require(reader.type(owner) == "Lru/privatediary/BuildConfig;", "Invalid DEX BuildConfig descriptor")
    class_data, static_values = struct.unpack_from("<II", data, class_offset + 24)
    fields: list[tuple[str, str]] = []
    names: set[str] = set()
    if class_data:
        require(112 <= class_data < len(data), "Invalid DEX class data offset")
        counts = []
        cursor = class_data
        for _ in range(4):
            count, cursor = reader.uleb(cursor)
            counts.append(count)
        require(all(count <= limit for count, limit in zip(counts, (reader.fields, reader.fields, reader.methods, reader.methods))),
                "Invalid DEX class member counts")
        for section, count in enumerate(counts):
            member = 0
            for position in range(count):
                delta, cursor = reader.uleb(cursor)
                require(position == 0 or delta > 0, "Duplicate DEX class member index")
                member += delta
                flags, cursor = reader.uleb(cursor)
                if section < 2:
                    require(member < reader.fields, "Invalid DEX static field index")
                    class_index, type_index, name_index = struct.unpack_from("<HHI", data, reader.field_offset + member * 8)
                    require(class_index == owner, "Invalid DEX field owner")
                    name, descriptor = reader.string(name_index), reader.type(type_index)
                    require(name not in names, "Duplicate DEX field name")
                    names.add(name)
                    require(bool(flags & 8) == (section == 0), "Invalid DEX static field flags")
                    if section == 0:
                        fields.append((name, descriptor))
                else:
                    require(member < reader.methods, "Invalid DEX method index")
                    class_index, proto_index, name_index = struct.unpack_from("<HHI", data, reader.method_offset + member * 8)
                    require(class_index == owner and proto_index < reader.protos and name_index < reader.strings,
                            "Invalid DEX method reference")
                    code_offset, cursor = reader.uleb(cursor)
                    require(code_offset == 0 or (112 <= code_offset < len(data) and code_offset % 4 == 0),
                            "Invalid DEX method code offset")
    values: dict[str, object] = {name: False if descriptor == "Z" else 0 if descriptor in ("B", "S", "C", "I", "J", "F", "D") else None
                                 for name, descriptor in fields}
    if static_values:
        require(112 <= static_values < len(data), "Invalid DEX static values offset")
        count, cursor = reader.uleb(static_values)
        require(count <= len(fields), "Too many DEX static values")
        primitive_types = {"B": 0x00, "S": 0x02, "C": 0x03, "I": 0x04, "J": 0x06,
                           "F": 0x10, "D": 0x11, "Z": 0x1f}
        for name, descriptor in fields[:count]:
            kind, value, cursor = reader.value(cursor)
            require((descriptor in primitive_types and kind == primitive_types[descriptor]) or
                    (descriptor == "Ljava/lang/String;" and kind in (0x17, 0x1e)) or
                    (descriptor.startswith(("L", "[")) and kind == 0x1e), "Invalid DEX static field value type")
            values[name] = value
    expected = {"DISTRIBUTION": "Ljava/lang/String;", "NETWORK_FEATURES": "Z", "YAN_AD_UNIT_ID": "Ljava/lang/String;",
                "PAYMENT_BACKEND_URL": "Ljava/lang/String;", "PRO_LICENSE_PUBLIC_KEY": "Ljava/lang/String;",
                "SELLER_LEGAL_NAME": "Ljava/lang/String;", "PRO_TERMS_URL": "Ljava/lang/String;", "PRO_REFUND_URL": "Ljava/lang/String;"}
    require(all(name not in expected or descriptor == expected[name] for name, descriptor in fields),
            "Unexpected BuildConfig settings field type")
    public_key = values.get("PRO_LICENSE_PUBLIC_KEY") or ""
    return {"present": True, "distribution": values.get("DISTRIBUTION") or "",
            "networkFeatures": values.get("NETWORK_FEATURES", False), "yanAdUnitId": values.get("YAN_AD_UNIT_ID") or "",
            "paymentBackendUrl": values.get("PAYMENT_BACKEND_URL") or "",
            "proLicensePublicKeySha256": hashlib.sha256(public_key.encode("utf-8")).hexdigest() if public_key.strip() else None,
            "sellerLegalName": values.get("SELLER_LEGAL_NAME") or "", "proTermsUrl": values.get("PRO_TERMS_URL") or "",
            "proRefundUrl": values.get("PRO_REFUND_URL") or ""}


def check_dex(data: bytes, name: str, variant: str = "offline") -> None:
    require(variant in ("offline", "store"), "Unknown distribution variant")
    for class_name in dex_classes(data):
        if variant == "store" and (class_name in STORE_ALLOWED_DEX_CLASSES or
                                   class_name.startswith(STORE_ALLOWED_DEX_PREFIXES)):
            continue
        require(not class_name.startswith(BANNED_DEX_PREFIXES),
                f"Forbidden advertising/analytics/network SDK class in {name}: {class_name.decode(errors='replace')}")


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


def verify(path: Path, allow_unsigned: bool = False, expected_package: str = "ru.privatediary",
           variant: str = "offline") -> dict:
    require(variant in ("offline", "store"), "Unknown distribution variant")
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
        manifest = check_manifest(root, expected_package, variant)
        model_prefix = "base/assets/model/" if bundle else "assets/model/"
        for name, expected in MODEL_HASHES.items():
            with archive.open(model_prefix + name) as source:
                require(hashlib.file_digest(source, "sha256").hexdigest() == expected, f"Offline model SHA-256 mismatch: {name}")
        native = []
        dex_count = 0
        build_settings = None
        for name in names:
            if re.fullmatch(r"(?:[^/]+/dex/)?classes\d*\.dex", name):
                info = archive.getinfo(name)
                require(info.file_size <= 64 * 1024 * 1024, "DEX file is unexpectedly large")
                data = archive.read(info)
                check_dex(data, name, variant)
                settings = extract_build_settings(data)
                require(settings is None or build_settings is None, "Duplicate ru.privatediary.BuildConfig across DEX files")
                if settings is not None:
                    build_settings = settings
                dex_count += 1
            if re.fullmatch(r"(?:base/)?lib/[^/]+/[^/]+\.so", name):
                native.append(elf_alignment(archive.read(name), name))
        require(dex_count > 0 and bool(native), "App bytecode or native libraries are missing")
        signed = signature(path, archive, bundle, allow_unsigned)
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    limitations = ["Store publisher/account approval is a separate check.",
                   "Test installed APK splits on real devices, including a 16-KB-page device.",
                   "Known SDK bytecode signatures are checked; this is not a complete third-party security audit."]
    if variant == "store":
        limitations.extend([
            "INTERNET is an application-wide Android permission; this check is not a network firewall.",
            "The :ads process shares the diary's Android UID and is not a separate security sandbox.",
            "Yandex Mobile Ads includes AppMetrica; process and metadata checks do not prove zero SDK telemetry.",
        ])
    return {"artifact": path.name, "distribution": variant, "bytes": path.stat().st_size, "sha256": digest,
            "format": "aab" if bundle else "apk", "manifest": manifest,
            "signature": signed, "technicalChecksPassed": True,
            "buildSettings": build_settings if build_settings is not None else {"present": False},
            "candidateOnly": signed["unsignedCandidate"], "nativeLibraries": native,
            "limitations": limitations}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--allow-unsigned-candidate", action="store_true",
                        help="Verify an unsigned review candidate; does not make it store-uploadable")
    parser.add_argument("--application-id", default="ru.privatediary")
    parser.add_argument("--variant", choices=("offline", "store"), default="offline",
                        help="Expected distribution; offline rejects INTERNET and advertising/network SDK bytecode")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.artifact, args.allow_unsigned_candidate, args.application_id, args.variant)
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
