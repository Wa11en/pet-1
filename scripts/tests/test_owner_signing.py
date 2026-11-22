"""Exercise the owner shell flow with fake tools; never create a production key."""
import errno
import hashlib
import json
import os
from pathlib import Path
import select
import shutil
import subprocess
import tempfile
import time
import unittest

try:
    import pty
except ImportError:  # Let Windows discover this suite and skip the POSIX tests.
    pty = None


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'release/signing-kit/sign-release.sh'
APK = 'Private-Diary-1.0.0-store-release-unsigned.apk'
OUTPUT = 'Private-Diary-1.0.0-RuStore.apk'
PASSWORD = 'Test Only!pA$$word123'
CERTIFICATE = b'FAKE PUBLIC CERTIFICATE FOR SHELL TESTS ONLY'
CERT_SHA256 = hashlib.sha256(CERTIFICATE).hexdigest().upper()

# This program stands in for Java/keytool. It records arguments but deliberately
# never records secret environment values and creates no cryptographic keys.
FAKE_TOOL = r'''#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import sys

name = Path(sys.argv[0]).name
args = sys.argv[1:]
password = os.environ.get('DIARY_OWNER_SIGN_PASSWORD')
with open(os.environ['FAKE_TOOL_LOG'], 'a', encoding='utf-8') as log:
    log.write(json.dumps({'tool': name, 'args': args,
                          'has_password_environment': password is not None,
                          'secret_in_arguments': bool(password and any(password in arg for arg in args))}) + '\n')
cert = os.environ.get('FAKE_CERTIFICATE', 'FAKE PUBLIC CERTIFICATE FOR SHELL TESTS ONLY').encode()
if name == 'keytool':
    if '-genkeypair' in args:
        Path(args[args.index('-keystore') + 1]).write_bytes(b'FAKE KEY - NOT CRYPTOGRAPHIC MATERIAL')
    elif '-exportcert' in args:
        Path(args[args.index('-file') + 1]).write_bytes(cert)
    else:
        sys.exit(3)
elif args == ['-version']:
    print('openjdk version "' + os.environ.get('FAKE_JAVA_VERSION', '17.0.10') + '"', file=sys.stderr)
elif 'sign' in args:
    Path(args[args.index('--out') + 1]).write_bytes(b'FAKE VERIFIED SIGNED APK')
elif 'verify' in args:
    fingerprint = os.environ.get('FAKE_SIGNER_SHA256', hashlib.sha256(cert).hexdigest())
    print('Verifies')
    print('Verified using v1 scheme (JAR signing): false')
    print('Verified using v2 scheme (APK Signature Scheme v2): true')
    print('Verified using v3 scheme (APK Signature Scheme v3): true')
    print('Verified using v4 scheme (APK Signature Scheme v4): false')
    print('Signer #1 certificate DN: ' + os.environ.get('FAKE_SIGNER_DN', 'CN=Private Diary'))
    print('Signer #1 certificate SHA-256 digest: ' + fingerprint)
else:
    sys.exit(4)
'''


@unittest.skipUnless(os.name == 'posix' and pty is not None, 'POSIX terminal signing helper')
class OwnerSigningShellTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.kit = self.root / 'kit with spaces'
        (self.kit / 'tools').mkdir(parents=True)
        shutil.copy2(SCRIPT, self.kit / 'sign-release.sh')
        (self.kit / APK).write_bytes(b'UNSIGNED TEST APK')
        (self.kit / 'tools/apksigner.jar').write_bytes(b'TEST APKSIGNER PLACEHOLDER')
        self.manifest = {'schemaVersion': 1, 'applicationId': 'ru.privatediary',
                         'versionName': '1.0.0', 'versionCode': 11, 'minSdk': 26,
                         'unsignedApkFile': APK,
                         'unsignedApkSha256': hashlib.sha256((self.kit / APK).read_bytes()).hexdigest(),
                         'apksignerJarFile': 'tools/apksigner.jar',
                         'apksignerJarSha256': hashlib.sha256((self.kit / 'tools/apksigner.jar').read_bytes()).hexdigest()}
        self.save_manifest()
        self.key_directory = self.root / 'owner-private-test-folder'
        self.log = self.root / 'tool-calls.jsonl'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for name in ('java', 'keytool'):
            tool = self.bin / name
            tool.write_text(FAKE_TOOL)
            tool.chmod(0o700)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        DIARY_OWNER_KEY_DIRECTORY=str(self.key_directory),
                        FAKE_TOOL_LOG=str(self.log))
        # An inherited variable must never bypass the terminal password request.
        self.env['DIARY_OWNER_SIGN_PASSWORD'] = 'INHERITED-SECRET-MUST-BE-DISCARDED'

    def save_manifest(self):
        (self.kit / 'release-manifest.json').write_text(json.dumps(self.manifest))

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def run_script(self, passwords=None, extra_env=None):
        environment = dict(self.env, **(extra_env or {}))
        if passwords is None:
            result = subprocess.run(['bash', str(self.kit / 'sign-release.sh')],
                                    cwd=self.root, env=environment, input=b'',
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
            return result.returncode, result.stdout.decode(errors='replace')
        master, slave = pty.openpty()
        process = subprocess.Popen(['bash', str(self.kit / 'sign-release.sh')],
                                   cwd=self.root, env=environment, stdin=slave,
                                   stdout=slave, stderr=slave)
        os.close(slave)
        transcript = bytearray()
        prompts = [b'Password (at least 12 printable ASCII characters): ',
                   b'Confirm password (case-sensitive): ']
        sent = 0
        deadline = time.monotonic() + 15
        try:
            while time.monotonic() < deadline:
                readable, _, _ = select.select([master], [], [], 0.1)
                if readable:
                    try:
                        chunk = os.read(master, 65536)
                    except OSError as error:
                        if error.errno == errno.EIO:
                            break
                        raise
                    if not chunk:
                        break
                    transcript.extend(chunk)
                    if sent < len(passwords) and prompts[sent] in transcript:
                        os.write(master, passwords[sent].encode() + b'\n')
                        sent += 1
                if process.poll() is not None and not readable:
                    break
            try:
                code = process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                self.fail('Signing helper did not finish: ' + transcript.decode(errors='replace'))
            return code, transcript.decode(errors='replace')
        finally:
            os.close(master)
            if process.poll() is None:
                process.kill()
            process.wait(timeout=2)

    def test_modified_apk_and_signer_jar_fail_before_java_or_password(self):
        for filename in (APK, 'tools/apksigner.jar'):
            with self.subTest(filename=filename):
                path = self.kit / filename
                original = path.read_bytes()
                path.write_bytes(original + b'TAMPERING')
                code, output = self.run_script()
                self.assertNotEqual(0, code)
                self.assertIn('SHA-256 mismatch', output)
                self.assertEqual([], self.calls())
                path.write_bytes(original)

    def test_manifest_identity_paths_and_duplicate_keys_are_untrusted(self):
        for name, wrong in [('applicationId', 'other.app'), ('versionCode', True),
                            ('unsignedApkFile', '../' + APK),
                            ('apksignerJarFile', '/tmp/apksigner.jar')]:
            with self.subTest(field=name):
                original = self.manifest[name]
                self.manifest[name] = wrong
                self.save_manifest()
                code, output = self.run_script()
                self.assertNotEqual(0, code)
                self.assertIn('manifest', output)
                self.assertEqual([], self.calls())
                self.manifest[name] = original
        self.save_manifest()
        path = self.kit / 'release-manifest.json'
        path.write_text(path.read_text().replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1'))
        code, output = self.run_script()
        self.assertNotEqual(0, code)
        self.assertIn('Duplicate manifest field', output)
        self.assertEqual([], self.calls())

    def test_input_symlink_and_output_collisions_fail_before_java(self):
        jar = self.kit / 'tools/apksigner.jar'
        original = jar.read_bytes()
        external = self.root / 'external.jar'
        external.write_bytes(original)
        jar.unlink()
        jar.symlink_to(external)
        code, output = self.run_script()
        self.assertNotEqual(0, code)
        self.assertIn('Symlinks', output)
        jar.unlink()
        jar.write_bytes(original)
        output_dir = self.kit / 'output'
        output_dir.mkdir()
        final = output_dir / OUTPUT
        os.link(self.kit / APK, final)
        code, output = self.run_script()
        self.assertNotEqual(0, code)
        self.assertIn('replace a release input', output)
        self.assertEqual(b'UNSIGNED TEST APK', (self.kit / APK).read_bytes())
        final.unlink()
        final.symlink_to(self.root / 'must-not-be-written.apk')
        code, output = self.run_script()
        self.assertNotEqual(0, code)
        self.assertIn('Symlinks', output)
        self.assertEqual([], self.calls())

    def test_owner_key_is_created_outside_kit_then_reused_and_password_stays_private(self):
        code, output = self.run_script([PASSWORD, PASSWORD])
        self.assertEqual(0, code, output)
        final = self.kit / 'output' / OUTPUT
        self.assertEqual(b'FAKE VERIFIED SIGNED APK', final.read_bytes())
        self.assertEqual(hashlib.sha256(final.read_bytes()).hexdigest() + '  ' + OUTPUT + '\n',
                         final.with_suffix('.apk.sha256').read_text())
        record = self.key_directory / 'publickeyfingerprint.txt'
        self.assertEqual('applicationId=ru.privatediary\nalias=private-diary-release\nsha256=' + CERT_SHA256 + '\n',
                         record.read_text())
        self.assertEqual(0o700, self.key_directory.stat().st_mode & 0o777)
        self.assertEqual(0o600, (self.key_directory / 'private-diary-release.p12').stat().st_mode & 0o777)
        self.assertEqual(0o600, record.stat().st_mode & 0o777)
        self.assertFalse(list((self.kit / 'output').glob('.owner-signing.*')))
        self.assertFalse(list(self.kit.rglob('*.p12')))
        calls = self.calls()
        self.assertEqual(1, sum('-genkeypair' in item['args'] for item in calls))
        for item in calls:
            self.assertFalse(item['secret_in_arguments'])
            if item['tool'] == 'keytool' or 'sign' in item['args']:
                self.assertTrue(item['has_password_environment'])
        self.assertNotIn(PASSWORD, output + self.log.read_text())
        generation = next(item['args'] for item in calls if '-genkeypair' in item['args'])
        self.assertIn('3072', generation)
        self.assertIn('10000', generation)
        self.assertIn('CN=Private Diary', generation)
        self.assertIn('-storepass:env', generation)
        final.unlink()
        final.with_suffix('.apk.sha256').unlink()
        code, output = self.run_script([PASSWORD])
        self.assertEqual(0, code, output)
        self.assertIn('Reusing', output)
        self.assertEqual(1, sum('-genkeypair' in item['args'] for item in self.calls()))
        self.assertNotIn(PASSWORD, output + self.log.read_text())

    def test_fingerprint_record_with_missing_key_never_creates_replacement(self):
        self.key_directory.mkdir()
        (self.key_directory / 'publickeyfingerprint.txt').write_text(
            'applicationId=ru.privatediary\nalias=private-diary-release\nsha256=' + CERT_SHA256 + '\n')
        code, output = self.run_script()
        self.assertNotEqual(0, code)
        self.assertIn('Restore your original key backup', output)
        self.assertEqual([], self.calls())

    def test_existing_fingerprint_mismatch_prevents_signing(self):
        code, output = self.run_script([PASSWORD, PASSWORD])
        self.assertEqual(0, code, output)
        (self.kit / 'output' / OUTPUT).unlink()
        (self.kit / 'output' / (OUTPUT + '.sha256')).unlink()
        calls_before = len(self.calls())
        code, output = self.run_script([PASSWORD], {'FAKE_CERTIFICATE': 'SOME OTHER KEY'})
        self.assertNotEqual(0, code)
        self.assertIn('does not match the saved owner fingerprint', output)
        self.assertFalse(any('sign' in item['args'] for item in self.calls()[calls_before:]))

    def test_existing_apk_or_checksum_is_never_overwritten_or_prompted_for(self):
        output_dir = self.kit / 'output'
        output_dir.mkdir()
        for name in (OUTPUT, OUTPUT + '.sha256'):
            with self.subTest(output=name):
                target = output_dir / name
                target.write_bytes(b'OWNER OUTPUT MUST REMAIN UNCHANGED')
                code, output = self.run_script()
                self.assertNotEqual(0, code)
                self.assertIn('output already exists', output)
                self.assertEqual(b'OWNER OUTPUT MUST REMAIN UNCHANGED', target.read_bytes())
                self.assertFalse(self.key_directory.exists())
                self.assertEqual([], self.calls())
                target.unlink()

    def test_apksigner_actual_debug_identity_and_wrong_signer_are_not_published(self):
        for environment in ({'FAKE_SIGNER_DN': 'CN=Android Debug, O=Android, C=US'},
                            {'FAKE_SIGNER_DN': 'CN=Android Debug+OU=Android'},
                            {'FAKE_SIGNER_SHA256': 'f' * 64}):
            with self.subTest(environment=environment):
                # A first failed signing still leaves its original local key for reuse.
                exists = (self.key_directory / 'private-diary-release.p12').exists()
                code, output = self.run_script([PASSWORD] if exists else [PASSWORD, PASSWORD], environment)
                self.assertNotEqual(0, code)
                self.assertFalse((self.kit / 'output' / OUTPUT).exists())
                self.assertFalse(list((self.kit / 'output').glob('.owner-signing.*')))

    def test_password_confirmation_is_case_sensitive_and_weak_password_is_rejected(self):
        for passwords in ([PASSWORD, PASSWORD.lower()], ['short'], ['тест-пароль-длинный']):
            with self.subTest(passwords=passwords):
                code, output = self.run_script(passwords)
                self.assertNotEqual(0, code)
                self.assertFalse(any('-genkeypair' in item['args'] for item in self.calls()))
                self.assertFalse((self.key_directory / 'private-diary-release.p12').exists())
                self.assertNotIn(passwords[0], output)

    def test_redirected_input_cannot_use_inherited_password(self):
        code, output = self.run_script()
        self.assertNotEqual(0, code)
        self.assertIn('interactive terminal', output)
        self.assertNotIn('INHERITED-SECRET', output)
        self.assertFalse(any('-genkeypair' in item['args'] for item in self.calls()))

    def test_jdk_below_17_and_key_location_inside_kit_fail(self):
        code, output = self.run_script(extra_env={'FAKE_JAVA_VERSION': '11.0.22'})
        self.assertNotEqual(0, code)
        self.assertIn('JDK 17 or newer', output)
        self.log.unlink()
        code, output = self.run_script(extra_env={'DIARY_OWNER_KEY_DIRECTORY': str(self.kit / 'keys')})
        self.assertNotEqual(0, code)
        self.assertIn('outside the signing kit', output)
        self.assertEqual([], self.calls())


if __name__ == '__main__':
    unittest.main()
