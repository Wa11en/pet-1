#!/usr/bin/env bash
set -euo pipefail
DIARY_PREVIEW_DIRECTORY="${1:?Supply the verified preview directory}"
for DIARY_PREVIEW_FLAVOR in offline store; do
    adb install -r "$DIARY_PREVIEW_DIRECTORY/Private-Diary-1.0.0-rc2-$DIARY_PREVIEW_FLAVOR.apk"
    adb install -r -t "$DIARY_PREVIEW_DIRECTORY/$DIARY_PREVIEW_FLAVOR-tests.apk"
    DIARY_PREVIEW_REPORT="$DIARY_PREVIEW_DIRECTORY/$DIARY_PREVIEW_FLAVOR-android-tests.txt"
    timeout 420s adb shell am instrument -w -r \
        -e notClass ru.privatediary.store.StoreScreenshotsTest \
        ru.privatediary.test/androidx.test.runner.AndroidJUnitRunner > "$DIARY_PREVIEW_REPORT" 2>&1
    cat "$DIARY_PREVIEW_REPORT"
    python3 - "$DIARY_PREVIEW_REPORT" "$DIARY_PREVIEW_FLAVOR" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
counts = re.findall(r'^OK \((\d+) tests?\)\s*$', text, re.MULTILINE)
minimum = 22 if sys.argv[2] == 'store' else 21
if len(counts) != 1 or int(counts[0]) < minimum:
    raise SystemExit('Preview APK Android verification failed')
PY
done
