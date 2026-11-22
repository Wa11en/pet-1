#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077
DIARY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$DIARY_ROOT"

DIARY_CANDIDATE=false
DIARY_FLAVOR=offline
DIARY_FLAVOR_SELECTED=false
for DIARY_ARGUMENT in "$@"; do
    case "$DIARY_ARGUMENT" in
        offline|store)
            if [[ "$DIARY_FLAVOR_SELECTED" == true ]]; then
                echo 'Choose exactly one build variant: offline or store.' >&2
                exit 2
            fi
            DIARY_FLAVOR="$DIARY_ARGUMENT"
            DIARY_FLAVOR_SELECTED=true
            ;;
        --unsigned-candidate)
            if [[ "$DIARY_CANDIDATE" == true ]]; then
                echo 'Supply --unsigned-candidate only once.' >&2
                exit 2
            fi
            DIARY_CANDIDATE=true
            ;;
        *)
            echo 'Usage: bash scripts/build_release.sh [offline|store] [--unsigned-candidate]' >&2
            exit 2
            ;;
    esac
done
DIARY_GRADLE_VARIANT="${DIARY_FLAVOR^}"
DIARY_APK_DIRECTORY="app/build/outputs/apk/$DIARY_FLAVOR/release"
DIARY_BUNDLE_DIRECTORY="app/build/outputs/bundle/${DIARY_FLAVOR}Release"

if [[ "$DIARY_CANDIDATE" == true ]]; then
    # Prevent ambient credentials from changing the meaning of the candidate command.
    unset DIARY_RELEASE_STORE_FILE DIARY_RELEASE_STORE_PASSWORD DIARY_RELEASE_KEY_ALIAS DIARY_RELEASE_KEY_PASSWORD
    ./gradlew "assemble${DIARY_GRADLE_VARIANT}Release" "bundle${DIARY_GRADLE_VARIANT}Release" \
        "check${DIARY_GRADLE_VARIANT}ReleaseNoInternet" \
        -PrequireModel=true -PunsignedReleaseCandidate=true
    python3 scripts/verify_release.py "$DIARY_APK_DIRECTORY/app-$DIARY_FLAVOR-release-unsigned.apk" \
        --variant "$DIARY_FLAVOR" --allow-unsigned-candidate --report "$DIARY_APK_DIRECTORY/verification-candidate.json"
    python3 scripts/verify_release.py "$DIARY_BUNDLE_DIRECTORY/app-$DIARY_FLAVOR-release.aab" \
        --variant "$DIARY_FLAVOR" --allow-unsigned-candidate --report "$DIARY_BUNDLE_DIRECTORY/verification-candidate.json"
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
./gradlew "assemble${DIARY_GRADLE_VARIANT}Release" "bundle${DIARY_GRADLE_VARIANT}Release" \
    "check${DIARY_GRADLE_VARIANT}ReleaseNoInternet" -PrequireModel=true
python3 scripts/verify_release.py "$DIARY_APK_DIRECTORY/app-$DIARY_FLAVOR-release.apk" \
    --variant "$DIARY_FLAVOR" --report "$DIARY_APK_DIRECTORY/verification-release.json"
python3 scripts/verify_release.py "$DIARY_BUNDLE_DIRECTORY/app-$DIARY_FLAVOR-release.aab" \
    --variant "$DIARY_FLAVOR" --report "$DIARY_BUNDLE_DIRECTORY/verification-release.json"
echo 'Signed artifacts passed the technical checks. Complete the store-account and seller checks separately.'
