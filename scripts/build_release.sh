#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077
DIARY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$DIARY_ROOT"

DIARY_CANDIDATE=false
if [[ $# -eq 1 && "$1" == --unsigned-candidate ]]; then
    DIARY_CANDIDATE=true
elif [[ $# -ne 0 ]]; then
    echo 'Usage: bash scripts/build_release.sh [--unsigned-candidate]' >&2
    exit 2
fi

if [[ "$DIARY_CANDIDATE" == true ]]; then
    # Prevent ambient credentials from changing the meaning of the candidate command.
    unset DIARY_RELEASE_STORE_FILE DIARY_RELEASE_STORE_PASSWORD DIARY_RELEASE_KEY_ALIAS DIARY_RELEASE_KEY_PASSWORD
    ./gradlew assembleRelease bundleRelease checkReleaseNoInternet \
        -PrequireModel=true -PunsignedReleaseCandidate=true
    python3 scripts/verify_release.py app/build/outputs/apk/release/app-release-unsigned.apk \
        --allow-unsigned-candidate --report app/build/outputs/apk/release/verification-candidate.json
    python3 scripts/verify_release.py app/build/outputs/bundle/release/app-release.aab \
        --allow-unsigned-candidate --report app/build/outputs/bundle/release/verification-candidate.json
    echo 'Unsigned candidate only. These artifacts must not be uploaded as a finished store release.'
    exit 0
fi

for DIARY_ENV_NAME in DIARY_RELEASE_STORE_FILE DIARY_RELEASE_STORE_PASSWORD DIARY_RELEASE_KEY_ALIAS DIARY_RELEASE_KEY_PASSWORD; do
    if [[ ! -v "$DIARY_ENV_NAME" || -z "${!DIARY_ENV_NAME}" ]]; then
        echo "Missing signing setting: $DIARY_ENV_NAME" >&2
        exit 1
    fi
done
python3 - "$DIARY_ROOT" "$DIARY_RELEASE_STORE_FILE" <<'PY'
import sys
from pathlib import Path
root, key = (Path(value).expanduser().resolve() for value in sys.argv[1:])
if root == key or root in key.parents:
    raise SystemExit('The release keystore must be outside the repository.')
if not key.is_file():
    raise SystemExit('The release keystore does not exist.')
PY
./gradlew assembleRelease bundleRelease checkReleaseNoInternet -PrequireModel=true
python3 scripts/verify_release.py app/build/outputs/apk/release/app-release.apk \
    --report app/build/outputs/apk/release/verification-release.json
python3 scripts/verify_release.py app/build/outputs/bundle/release/app-release.aab \
    --report app/build/outputs/bundle/release/verification-release.json
echo 'Signed artifacts passed the technical checks. Complete the store-account and seller checks separately.'
