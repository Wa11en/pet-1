#!/usr/bin/env bash
# Local owner signing. The private key and its password never belong in this kit.
set +x
set -euo pipefail
umask 077
export LC_ALL=C
unset DIARY_OWNER_SIGN_PASSWORD

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

if [[ "${1:-}" == '--help' || "${1:-}" == '-h' ]]; then
    printf '%s\n' \
        'Usage: bash sign-release.sh' \
        'Requires JDK 17+ (java and keytool) and Python 3. No downloads are made.' \
        'The password is requested privately in an interactive terminal.' \
        'Key: ~/.private-diary-signing/private-diary-release.p12' \
        'Optional: DIARY_OWNER_KEY_DIRECTORY=/absolute/private/folder (outside this kit).' \
        'Keep the key, password and publickeyfingerprint.txt in your own secure backup.' \
        'Result: output/Private-Diary-1.0.0-RuStore.apk and its .sha256 file.'
    exit 0
fi
[[ $# == 0 ]] || fail 'Unexpected arguments. Use --help.'
command -v python3 >/dev/null 2>&1 || fail 'Install Python 3, then run this script again.'

# All JSON, hashing and filesystem checks use only the Python standard library.
helper() {
    python3 - "$@" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

APK = 'Private-Diary-1.0.0-store-release-unsigned.apk'
OUTPUT = 'Private-Diary-1.0.0-RuStore.apk'
KEY = 'private-diary-release.p12'
RECORD = 'publickeyfingerprint.txt'
ALIAS = 'private-diary-release'
DEBUG_SHA256 = '01EFF5E414F45AF7746C77732F65AFA5AF85800487653C4DFBACDA93E2E14C81'

def reject(message):
    raise ValueError(message)

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest().upper()

def within(path, root):
    return os.path.commonpath((str(path), str(root))) == str(root)

def remove_if_present(path):
    try:
        path.unlink()
    except FileNotFoundError:
        pass

def plain(path, directory=False, optional=False):
    if path.is_symlink():
        reject('Symlinks are not allowed: ' + path.name)
    if not path.exists():
        if optional:
            return
        reject('Missing required file or directory: ' + path.name)
    if not (path.is_dir() if directory else path.is_file()):
        reject('Unexpected file type: ' + path.name)

def inside(root, relative):
    if not isinstance(relative, str) or not relative or '\\' in relative:
        reject('Invalid manifest path.')
    parts = relative.split('/')
    if any(part in ('', '.', '..') for part in parts) or Path(relative).is_absolute():
        reject('Manifest paths must be trusted relative paths.')
    current = root
    for index, part in enumerate(parts):
        current = current / part
        plain(current, directory=index < len(parts) - 1)
    if not within(current.resolve(), root):
        reject('A manifest path escapes the signing kit.')
    return current

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            reject('Duplicate manifest field: ' + key)
        result[key] = value
    return result

def inputs(root):
    plain(root / 'release-manifest.json')
    manifest = json.loads((root / 'release-manifest.json').read_text(encoding='utf-8'),
                          object_pairs_hook=unique_object)
    expected = {'schemaVersion': 1, 'applicationId': 'ru.privatediary',
                'versionName': '1.0.0', 'versionCode': 11, 'minSdk': 26,
                'unsignedApkFile': APK, 'apksignerJarFile': 'tools/apksigner.jar'}
    if not isinstance(manifest, dict):
        reject('The release manifest must be a JSON object.')
    for name, value in expected.items():
        if type(manifest.get(name)) is not type(value) or manifest[name] != value:
            reject('Unexpected release manifest field: ' + name)
    paths = []
    for filename, checksum in [('unsignedApkFile', 'unsignedApkSha256'),
                               ('apksignerJarFile', 'apksignerJarSha256')]:
        path = inside(root, manifest[filename])
        wanted = manifest.get(checksum)
        if not isinstance(wanted, str) or not re.fullmatch(r'[0-9a-fA-F]{64}', wanted):
            reject('Invalid SHA-256 in the release manifest: ' + checksum)
        if digest(path) != wanted.upper():
            reject('SHA-256 mismatch: ' + manifest[filename])
        paths.append(path)
    output = root / 'output'
    plain(output, directory=True, optional=True)
    for name in (OUTPUT, OUTPUT + '.sha256'):
        target = output / name
        plain(target, optional=True)
        if target.exists() and any(os.path.samefile(target, item) for item in paths):
            reject('An output would replace a release input.')
        if target.exists():
            reject('An output already exists: ' + name + '. Move it to a safe backup or remove it explicitly before signing again.')
    return output

def identity_text(fingerprint):
    return 'applicationId=ru.privatediary\nalias=' + ALIAS + '\nsha256=' + fingerprint + '\n'

def key_state(root, value, create=False):
    path = Path(value)
    if not path.is_absolute() or '..' in path.parts:
        reject('DIARY_OWNER_KEY_DIRECTORY must be an absolute path without .. components.')
    # Resolve the user's home/backup location, but never follow a key-directory symlink.
    plain(path, directory=True, optional=True)
    path = path.parent.resolve() / path.name
    if within(path, root):
        reject('The private key directory must be outside the signing kit.')
    key, record = path / KEY, path / RECORD
    plain(key, optional=True)
    plain(record, optional=True)
    if record.exists():
        if not key.exists():
            reject('The signing key is missing but its fingerprint record exists. Restore your original key backup; a new identity will not be generated.')
        text = record.read_text(encoding='ascii')
        if not re.fullmatch(r'applicationId=ru\.privatediary\nalias=private-diary-release\nsha256=[0-9A-F]{64}\n', text):
            reject('The saved public key fingerprint record is invalid. Restore your original backup.')
    if create:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        path.chmod(0o700)
        if key.exists():
            key.chmod(0o600)
        if record.exists():
            record.chmod(0o600)
    return path, key, record

def certificate(root, key_directory, cert):
    path, key, record = key_state(root, key_directory)
    plain(key)
    key.chmod(0o600)
    plain(cert)
    fingerprint = digest(cert)
    if fingerprint == DEBUG_SHA256:
        reject('An Android Debug certificate cannot sign the owner release.')
    if record.exists() and record.read_text(encoding='ascii') != identity_text(fingerprint):
        reject('The key does not match the saved owner fingerprint. Restore the original signing key backup.')
    return path, record, fingerprint

def write_private(path, text):
    with tempfile.NamedTemporaryFile(mode='w', encoding='ascii', dir=path.parent,
                                     prefix='.owner-record-', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        except BaseException:
            remove_if_present(temporary)
            raise
    try:
        temporary.chmod(0o600)
        plain(path, optional=True)
        os.replace(temporary, path)
    finally:
        remove_if_present(temporary)

try:
    operation = sys.argv[1]
    if operation == 'java':
        report = Path(sys.argv[2]).read_text(encoding='utf-8', errors='replace')
        version = re.search(r'(?:openjdk|java)\s+(?:version\s+)?"?(\d+)', report)
        if not version or int(version[1]) < 17:
            reject('JDK 17 or newer is required.')
    else:
        root = Path(sys.argv[2]).resolve(strict=True)
        if operation == 'preflight':
            output = inputs(root)
            output.mkdir(mode=0o700, exist_ok=True)
        elif operation == 'key-state':
            _, key, _ = key_state(root, sys.argv[3], create=True)
            print('existing' if key.exists() else 'new')
        elif operation == 'certificate':
            _, _, fingerprint = certificate(root, sys.argv[3], Path(sys.argv[4]))
            print(fingerprint)
        elif operation == 'publish':
            output = inputs(root)
            _, record, fingerprint = certificate(root, sys.argv[3], Path(sys.argv[4]))
            report = Path(sys.argv[5]).read_text(encoding='utf-8', errors='strict')
            found = re.findall(r'^Signer #(\d+) certificate SHA-256 digest: ([0-9A-Fa-f]{64})\s*$', report, re.M)
            if len(found) != 1 or found[0][0] != '1' or found[0][1].upper() != fingerprint:
                reject('Verified APK signer does not match the owner certificate, or has multiple signers.')
            names = re.findall(r'^Signer #(\d+) certificate DN: (.+)$', report, re.M)
            if len(names) != 1 or names[0][0] != '1':
                reject('The verified APK has an unexpected certificate identity.')
            if re.search(r'(?:^|[,;+])\s*CN\s*=\s*"?Android\s*Debug"?\s*(?:[,;+]|$)', names[0][1], re.I):
                reject('An Android Debug certificate cannot sign the owner release.')
            for scheme, wanted in [(1, 'false'), (2, 'true'), (3, 'true'), (4, 'false')]:
                label = 'JAR signing' if scheme == 1 else 'APK Signature Scheme v' + str(scheme)
                if not re.search(r'^Verified using v' + str(scheme) +
                                 r' scheme \(' + label +
                                 r'\): ' + wanted + r'\s*$', report, re.M):
                    reject('APK signature scheme verification failed for v' + str(scheme) + '.')
            signed = Path(sys.argv[6])
            plain(signed)
            signed_hash = digest(signed).lower()
            if not record.exists():
                write_private(record, identity_text(fingerprint))
            write_private(output / (OUTPUT + '.sha256'), signed_hash + '  ' + OUTPUT + '\n')
            signed.chmod(0o600)
            os.replace(signed, output / OUTPUT)
            print('Owner certificate SHA-256: ' + fingerprint)
            print('Signed and verified: ' + str(output / OUTPUT))
            print('APK SHA-256: ' + signed_hash)
        else:
            reject('Unknown helper operation.')
except (ValueError, OSError, UnicodeError) as error:
    print('Error: ' + str(error), file=sys.stderr)
    sys.exit(1)
PY
}

kit_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
key_directory="${DIARY_OWNER_KEY_DIRECTORY:-${HOME:?HOME must be set}/.private-diary-signing}"
# Check both supplied binaries before executing Java or asking for a password.
helper preflight "$kit_dir"
key_status="$(helper key-state "$kit_dir" "$key_directory")"
command -v java >/dev/null 2>&1 || fail 'Install JDK 17+ (java), then run this script again.'
command -v keytool >/dev/null 2>&1 || fail 'Install JDK 17+ (keytool), then run this script again.'

work_dir=''
cleanup() {
    unset DIARY_OWNER_SIGN_PASSWORD password_confirmation
    if [[ -n "$work_dir" && -d "$work_dir" ]]; then
        rm -rf -- "$work_dir"
    fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
work_dir="$(mktemp -d "$kit_dir/output/.owner-signing.XXXXXXXX")"
java -version >"$work_dir/java-version.txt" 2>&1 || fail 'Java could not start. Install JDK 17+.'
helper java "$work_dir/java-version.txt"
[[ -t 0 ]] || fail 'Run this script in an interactive terminal; password input cannot be redirected.'

IFS= read -r -s -p 'Password (at least 12 printable ASCII characters): ' DIARY_OWNER_SIGN_PASSWORD \
    || fail 'Password entry was cancelled.'
printf '\n' >&2
if [[ ${#DIARY_OWNER_SIGN_PASSWORD} -lt 12 || "$DIARY_OWNER_SIGN_PASSWORD" == *[![:print:]]* ]]; then
    fail 'Use at least 12 printable ASCII characters: Latin letters, digits, symbols or spaces.'
fi
if [[ "$key_status" == 'new' ]]; then
    IFS= read -r -s -p 'Confirm password (case-sensitive): ' password_confirmation \
        || fail 'Password confirmation was cancelled.'
    printf '\n' >&2
    [[ "$DIARY_OWNER_SIGN_PASSWORD" == "$password_confirmation" ]] || fail 'Passwords do not match.'
    unset password_confirmation
fi
export DIARY_OWNER_SIGN_PASSWORD
key_file="$key_directory/private-diary-release.p12"
if [[ "$key_status" == 'new' ]]; then
    printf 'Creating your local owner signing key. Keep your key and password in a secure backup.\n'
    keytool -genkeypair -alias private-diary-release -keyalg RSA -keysize 3072 \
        -sigalg SHA256withRSA -validity 10000 -dname 'CN=Private Diary' \
        -keystore "$key_file" -storetype PKCS12 \
        -storepass:env DIARY_OWNER_SIGN_PASSWORD -keypass:env DIARY_OWNER_SIGN_PASSWORD \
        -noprompt >"$work_dir/keytool.log" 2>&1 || fail 'Key creation failed. Keep any existing key; do not delete it to retry.'
else
    printf 'Reusing your existing local owner signing key.\n'
fi
keytool -exportcert -alias private-diary-release -keystore "$key_file" -storetype PKCS12 \
    -storepass:env DIARY_OWNER_SIGN_PASSWORD -file "$work_dir/owner-cert.der" \
    >"$work_dir/keytool.log" 2>&1 || fail 'Cannot open the existing key. Check its password and restore the original backup if needed.'
helper certificate "$kit_dir" "$key_directory" "$work_dir/owner-cert.der" >/dev/null
java -jar "$kit_dir/tools/apksigner.jar" sign \
    --ks "$key_file" --ks-type PKCS12 --ks-key-alias private-diary-release \
    --ks-pass env:DIARY_OWNER_SIGN_PASSWORD --key-pass env:DIARY_OWNER_SIGN_PASSWORD \
    --min-sdk-version 26 --v1-signing-enabled false --v2-signing-enabled true \
    --v3-signing-enabled true --v4-signing-enabled false \
    --out "$work_dir/signed.apk" "$kit_dir/Private-Diary-1.0.0-store-release-unsigned.apk" \
    >"$work_dir/signing.log" 2>&1 || fail 'APK signing failed. No verified output was published.'
java -jar "$kit_dir/tools/apksigner.jar" verify --min-sdk-version 26 --verbose --print-certs \
    "$work_dir/signed.apk" >"$work_dir/verification.txt" 2>&1 \
    || fail 'APK signature verification failed. No verified output was published.'
helper publish "$kit_dir" "$key_directory" "$work_dir/owner-cert.der" \
    "$work_dir/verification.txt" "$work_dir/signed.apk"
unset DIARY_OWNER_SIGN_PASSWORD
printf 'Back up your local .p12 key, its password and publickeyfingerprint.txt. Never upload them with the APK.\n'
