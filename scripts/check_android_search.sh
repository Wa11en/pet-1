#!/usr/bin/env bash
set -euo pipefail

# Runs only on the developer's Android emulator; all test text is synthetic.
CURRENT_APK="$1"
TEST_APK="$2"
BASELINE_APK="${3:-}"
REPORT_DIR="${RUNNER_TEMP:-/tmp}/private-diary-search-test"
TEST_CLASS="ru.privatediary.util.SearchInputAndroidTest"
TEST_RUNNER="ru.privatediary.test/androidx.test.runner.AndroidJUnitRunner"
mkdir -p "$REPORT_DIR"

if [[ -n "$BASELINE_APK" ]]; then
    adb install -r "$BASELINE_APK"
    adb install -r -t "$TEST_APK"
    timeout 120s adb shell am instrument -w -r -e class "$TEST_CLASS" "$TEST_RUNNER" > "$REPORT_DIR/baseline.txt" 2>&1 || true
    cat "$REPORT_DIR/baseline.txt"
    # A failed baseline must be this Android API incompatibility, not an install/test error.
    if ! grep -Eq 'UNICODE_CHARACTER_CLASS|Unsupported flags: 256' "$REPORT_DIR/baseline.txt"; then
        echo 'The baseline did not reproduce the expected regex initialization failure.' >&2
        exit 1
    fi
    if grep -Eq '^OK \(3 tests\)' "$REPORT_DIR/baseline.txt"; then
        echo 'The regression test unexpectedly passed on the affected baseline.' >&2
        exit 1
    fi
fi

adb install -r "$CURRENT_APK"
adb install -r -t "$TEST_APK"
timeout 120s adb shell am instrument -w -r -e class "$TEST_CLASS" "$TEST_RUNNER" > "$REPORT_DIR/fixed.txt" 2>&1
cat "$REPORT_DIR/fixed.txt"
# adb/am can return exit code zero for failed JUnit runs; require the success summary.
grep -Eq '^OK \(3 tests\)' "$REPORT_DIR/fixed.txt"
echo 'All three Android search regression tests passed on the exact signed release APK.'
