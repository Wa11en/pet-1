import base64
import json
from pathlib import Path
import tempfile
import unittest

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from diary_payments.settings import Settings
from diary_payments.signatures import LicenseSigner


class SignatureTests(unittest.TestCase):
    def test_public_cross_language_fixture_has_valid_signature(self):
        fixture = json.loads((Path(__file__).parent / "fixtures/license.json").read_text())
        version, payload, signature = fixture["token"].split(".")
        key = serialization.load_pem_public_key(fixture["publicKeyPem"].encode("ascii"))
        self.assertGreaterEqual(key.key_size, 3072)
        key.verify(base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4)),
                   (version + "." + payload).encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        self.assertEqual(fixture["issuedAt"], claims["issuedAt"])
        self.assertEqual(fixture["expiresAt"], claims["expiresAt"])
        with self.assertRaises(InvalidSignature):
            key.verify(base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4)),
                       (version + "." + payload + "A").encode("ascii"), padding.PKCS1v15(), hashes.SHA256())

    def test_weak_key_and_expired_access_are_rejected(self):
        with self.assertRaises(ValueError):
            LicenseSigner(rsa.generate_private_key(public_exponent=65537, key_size=2048))
        signer = LicenseSigner(rsa.generate_private_key(public_exponent=65537, key_size=3072))
        with self.assertRaises(ValueError):
            signer.issue("a", 100, 100)

    def test_private_key_permissions_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "key.pem"
            key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                               serialization.NoEncryption()))
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                LicenseSigner.from_file(path)
            path.chmod(0o600)
            self.assertIn("BEGIN PUBLIC KEY", LicenseSigner.from_file(path).public_key_pem())

    def test_receipt_confirmation_and_valid_https_origin_are_required(self):
        values = dict(database_path=Path("unused.sqlite3"), shop_id="123456", shop_secret="secret",
                      return_url="https://payments.example.org/return", signing_key_path=Path("key.pem"),
                      public_origin="https://payments.example.org", provider_managed_receipts_confirmed=True)
        self.assertTrue(Settings(**values).configured())
        for change in ({"provider_managed_receipts_confirmed": False}, {"public_origin": "http://payments.example.org"},
                       {"public_origin": "https://payments.example.org:bad"}, {"public_origin": "https://payments.example.org/path"},
                       {"return_url": "https://user:secret@payments.example.org"}):
            with self.subTest(change=change):
                self.assertFalse(Settings(**(values | change)).configured())
