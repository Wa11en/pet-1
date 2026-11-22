#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

# Developer/CI-only checks with synthetic data. The actual release remains unsigned.
RELEASE_APK="${1:?Usage: check_release_android.sh UNSIGNED_RELEASE_APK TEST_APK REPORT_DIRECTORY [DEBUG_KEYSTORE] [offline|store]}"
TEST_APK="${2:?Supply the matching debug instrumentation APK}"
REPORT_DIRECTORY="${3:?Supply a directory for public verification reports}"
TEST_KEYSTORE="${4:-${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore}"
BUILD_FLAVOR="${5:-offline}"
SDK_DIRECTORY="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
APKSIGNER="$SDK_DIRECTORY/build-tools/35.0.0/apksigner"
TEST_RUNNER='ru.privatediary.test/androidx.test.runner.AndroidJUnitRunner'
SCREENSHOTS_CLASS='ru.privatediary.store.StoreScreenshotsTest'

if [[ $# -gt 5 || "$BUILD_FLAVOR" != offline && "$BUILD_FLAVOR" != store ]]; then
    echo 'Choose the tested variant: offline or store.' >&2
    exit 2
fi

for setting in DIARY_RELEASE_STORE_FILE DIARY_RELEASE_STORE_PASSWORD DIARY_RELEASE_KEY_ALIAS DIARY_RELEASE_KEY_PASSWORD; do
    if [[ -v "$setting" ]]; then
        echo 'Runtime verification must not receive owner release signing settings.' >&2
        exit 1
    fi
done
[[ -f "$RELEASE_APK" ]] || { printf 'Unsigned release APK is missing: %s\n' "$RELEASE_APK" >&2; exit 1; }
[[ -f "$TEST_APK" ]] || { printf 'Matching instrumentation APK is missing: %s\n' "$TEST_APK" >&2; exit 1; }
[[ -f "$TEST_KEYSTORE" ]] || {
    printf 'CI debug keystore is missing: %s\nCreate the key in ANDROID_USER_HOME before Gradle builds the matching test APK.\n' "$TEST_KEYSTORE" >&2
    exit 1
}
[[ -x "$APKSIGNER" ]] || { echo 'Android build-tools 35.0.0 are required.' >&2; exit 1; }
if "$APKSIGNER" verify "$RELEASE_APK" >/dev/null 2>&1; then
    echo 'This check requires an explicitly unsigned release candidate.' >&2
    exit 1
fi

mkdir -p "$REPORT_DIRECTORY"
original_sha256="$(sha256sum "$RELEASE_APK" | cut -d ' ' -f 1)"
runtime_directory="$(mktemp -d "${RUNNER_TEMP:-/tmp}/private-diary-release-runtime.XXXXXXXX")"
trap 'rm -rf -- "$runtime_directory"' EXIT
runtime_apk="$runtime_directory/release-runtime-only.apk"

# This disposable Android Debug key belongs to this CI runner, never the owner.
# --out leaves the candidate untouched; the signed copy is deleted by the trap.
"$APKSIGNER" sign --ks "$TEST_KEYSTORE" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$runtime_apk" "$RELEASE_APK"
runtime_certificate="$("$APKSIGNER" verify --print-certs "$runtime_apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
test_certificate="$("$APKSIGNER" verify --print-certs "$TEST_APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ -n "$runtime_certificate" && "$runtime_certificate" == "$test_certificate" ]] || {
    echo 'The runtime copy and instrumentation APK must use the same CI debug certificate.' >&2
    exit 1
}

adb install -r "$runtime_apk"
adb install -r -t "$TEST_APK"
# Prove that the runtime APK really retains the production non-debuggable setting.
if adb shell run-as ru.privatediary true > "$REPORT_DIRECTORY/release-not-debuggable.txt" 2>&1; then
    echo 'The runtime release copy unexpectedly allows run-as/debug access.' >&2
    exit 1
fi
grep -Fq 'not debuggable' "$REPORT_DIRECTORY/release-not-debuggable.txt"

timeout 420s adb shell am instrument -w -r -e notClass "$SCREENSHOTS_CLASS" \
    "$TEST_RUNNER" > "$REPORT_DIRECTORY/core-tests.txt" 2>&1
cat "$REPORT_DIRECTORY/core-tests.txt"
# am instrument can return zero after failures; require a positive JUnit summary.
mapfile -t counts < <(tr -d '\r' < "$REPORT_DIRECTORY/core-tests.txt" | sed -nE 's/^OK \(([0-9]+) tests?\)$/\1/p')
if [[ "${#counts[@]}" -ne 1 || ! "${counts[0]:-}" =~ ^[0-9]+$ || "${counts[0]:-0}" -lt 18 ]]; then
    echo 'Expected at least 18 passing core tests on the release runtime copy.' >&2
    exit 1
fi
[[ "$(sha256sum "$RELEASE_APK" | cut -d ' ' -f 1)" == "$original_sha256" ]] || {
    echo 'The unsigned release candidate changed during runtime verification.' >&2
    exit 1
}
python3 - "$REPORT_DIRECTORY/runtime-summary.json" "$original_sha256" "${counts[0]}" "$BUILD_FLAVOR" <<'PY'
import json
from pathlib import Path
import sys
Path(sys.argv[1]).write_text(json.dumps({
    "testedVariant": sys.argv[4] + "Release",
    "testsPassed": int(sys.argv[3]),
    "debuggable": False,
    "screenshotsExcluded": True,
    "runtimeSignatureOnly": "disposable CI Android Debug key",
    "unsignedCandidateSha256": sys.argv[2],
    "candidateUnchanged": True,
}, indent=2) + "\n", encoding="utf-8")
PY
echo 'Core Android checks passed on the non-debuggable release copy; the unsigned candidate is unchanged.'
