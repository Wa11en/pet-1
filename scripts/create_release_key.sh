#!/usr/bin/env bash
# Run on the owner's computer. The private key is never uploaded by this script.
set +x
set -euo pipefail
umask 077

DIARY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
if [[ $# -gt 1 ]]; then
    echo 'Usage: bash scripts/create_release_key.sh [/private/path/private-diary-release.jks]' >&2
    exit 2
fi
DIARY_KEY_PATH="${1:-$HOME/.private-diary-signing/private-diary-release.jks}"
DIARY_KEY_PATH="$(python3 - "$DIARY_ROOT" "$DIARY_KEY_PATH" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
key = Path(sys.argv[2]).expanduser().resolve()
if key == root or root in key.parents:
    raise SystemExit('The release keystore must be outside the repository.')
if key.exists():
    raise SystemExit('Refusing to replace an existing keystore.')
print(key)
PY
)"
if [[ -e "$DIARY_KEY_PATH" || -L "$DIARY_KEY_PATH" ]]; then
    echo 'Refusing to replace an existing file or symlink.' >&2
    exit 1
fi
command -v keytool >/dev/null || { echo 'Install a JDK and make keytool available.' >&2; exit 1; }
mkdir -p -- "$(dirname -- "$DIARY_KEY_PATH")"
DIARY_KEY_LOCK="$DIARY_KEY_PATH.create-lock"
mkdir -- "$DIARY_KEY_LOCK" || { echo 'Another key creation is in progress.' >&2; exit 1; }
trap 'rmdir -- "$DIARY_KEY_LOCK"' EXIT
[[ ! -e "$DIARY_KEY_PATH" && ! -L "$DIARY_KEY_PATH" ]] || exit 1
echo 'keytool will ask for passwords and certificate details in this terminal.'
echo 'Keep this keystore and its passwords in your private backup; do not commit them.'
keytool -genkeypair -v -storetype JKS \
    -keystore "$DIARY_KEY_PATH" -alias private-diary-release \
    -keyalg RSA -keysize 3072 -validity 10000
chmod 600 -- "$DIARY_KEY_PATH"
echo 'Release key created locally. No private key or password was uploaded.'
