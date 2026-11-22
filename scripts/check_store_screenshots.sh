#!/usr/bin/env bash
set -euo pipefail

# Developer/CI-only capture of the real Compose UI with public, synthetic diary entries.
# Production MainActivity keeps FLAG_SECURE; StorePreviewActivity exists only in debug.
DEBUG_APK="${1:?Usage: check_store_screenshots.sh DEBUG_APK TEST_APK OUTPUT_DIRECTORY [offline|store]}"
TEST_APK="${2:?Supply the matching signed instrumentation APK}"
OUTPUT_DIRECTORY="${3:?Supply a local output directory}"
BUILD_FLAVOR="${4:-offline}"
TEST_RUNNER='ru.privatediary.test/androidx.test.runner.AndroidJUnitRunner'
TEST_CLASS='ru.privatediary.store.StoreScreenshotsTest'
REPORT_DIRECTORY="${RUNNER_TEMP:-/tmp}/private-diary-store-capture-$BUILD_FLAVOR"
if [[ $# -gt 4 || "$BUILD_FLAVOR" != offline && "$BUILD_FLAVOR" != store ]]; then
    echo 'Choose the screenshot variant: offline or store.' >&2
    exit 2
fi
[[ -f "$DEBUG_APK" ]] || { printf 'Debug APK is missing: %s\n' "$DEBUG_APK" >&2; exit 1; }
[[ -f "$TEST_APK" ]] || { printf 'Matching instrumentation APK is missing: %s\n' "$TEST_APK" >&2; exit 1; }
mkdir -p "$OUTPUT_DIRECTORY" "$REPORT_DIRECTORY"

original_size="$(adb shell wm size | tr -d '\r' | sed -n 's/^Override size: //p')"
original_density="$(adb shell wm density | tr -d '\r' | sed -n 's/^Override density: //p')"
restore_display() {
    if [[ -n "$original_size" ]]; then adb shell wm size "$original_size"; else adb shell wm size reset; fi
    if [[ -n "$original_density" ]]; then adb shell wm density "$original_density"; else adb shell wm density reset; fi
}
trap restore_display EXIT

adb shell wm size 1080x1920
adb shell wm density 420
adb install -r "$DEBUG_APK"
adb install -r -t "$TEST_APK"
timeout 180s adb shell am instrument -w -r -e class "$TEST_CLASS" \
    -e captureStoreScreenshots true "$TEST_RUNNER" > "$REPORT_DIRECTORY/instrumentation.txt" 2>&1
cat "$REPORT_DIRECTORY/instrumentation.txt"
grep -Eq '^OK \(1 test\)' "$REPORT_DIRECTORY/instrumentation.txt"

for language in ru en; do
    for scene in journal editor search calendar; do
        file="$language-$scene.png"
        adb exec-out run-as ru.privatediary cat "files/store-previews/$file" > "$OUTPUT_DIRECTORY/$file"
        test -s "$OUTPUT_DIRECTORY/$file"
    done
done
echo 'Captured eight genuine Compose screens using only synthetic diary entries.'
