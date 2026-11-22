from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Settings:
    database_path: Path
    shop_id: str | None = None
    shop_secret: str | None = None
    return_url: str | None = None
    signing_key_path: Path | None = None
    provider_managed_receipts_confirmed: bool = False
    public_origin: str | None = None

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(database_path=Path(os.environ.get("PAYMENTS_DATABASE_PATH", "./private/payments.sqlite3")),
            shop_id=os.environ.get("YOOKASSA_SHOP_ID"), shop_secret=os.environ.get("YOOKASSA_SECRET_KEY"),
            return_url=os.environ.get("PAYMENTS_RETURN_URL"),
            signing_key_path=Path(os.environ["LICENSE_SIGNING_KEY_FILE"]) if os.environ.get("LICENSE_SIGNING_KEY_FILE") else None,
            provider_managed_receipts_confirmed=os.environ.get("PROVIDER_MANAGED_RECEIPTS_CONFIRMED") == "true",
            public_origin=os.environ.get("PAYMENTS_PUBLIC_ORIGIN"))

    def configured(self) -> bool:
        if not all((self.shop_id, self.shop_secret, self.return_url, self.signing_key_path,
                    self.provider_managed_receipts_confirmed, self.public_origin)):
            return False
        for value in (self.return_url, self.public_origin):
            try:
                parsed = urlsplit(value or "")
                valid = (parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password and
                         parsed.port in (None, 443) and not parsed.fragment and "\\" not in value and
                         not any(ord(char) <= 32 or ord(char) == 127 for char in value))
            except (ValueError, TypeError):
                valid = False
            if not valid:
                return False
        origin = urlsplit(self.public_origin)
        if origin.path not in ("", "/") or origin.query:
            return False
        return True
