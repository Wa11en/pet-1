"""Fixed-algorithm, device-bound offline access certificates; no diary fields."""
from __future__ import annotations

import base64
import json
from pathlib import Path
import stat

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


class LicenseSigner:
    def __init__(self, private_key: rsa.RSAPrivateKey):
        if not isinstance(private_key, rsa.RSAPrivateKey) or private_key.key_size < 3072:
            raise ValueError("A dedicated RSA signing key of at least 3072 bits is required")
        self._key = private_key

    @classmethod
    def from_file(cls, path: Path, password: bytes | None = None) -> "LicenseSigner":
        path = path.expanduser().resolve()
        if stat.S_IMODE(path.stat().st_mode) & 0o077:
            raise ValueError("The license signing private key must be readable only by its owner")
        key = serialization.load_pem_private_key(path.read_bytes(), password=password)
        return cls(key)

    def public_key_pem(self) -> str:
        return self._key.public_key().public_bytes(serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo).decode("ascii")

    def issue(self, installation_id: str, issued_at: int, expires_at: int) -> str:
        if expires_at <= issued_at:
            raise ValueError("Cannot issue expired access")
        payload = json.dumps({"version": 1, "aud": "ru.privatediary", "sub": installation_id,
            "plan": "pro_monthly", "issuedAt": issued_at, "expiresAt": expires_at},
            ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        message = "v1." + base64url(payload)
        signature = self._key.sign(message.encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
        return message + "." + base64url(signature)
