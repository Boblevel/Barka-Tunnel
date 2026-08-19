from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


@dataclass(frozen=True)
class Settings:
    app_env: str = _env("APP_ENV", "production")
    host: str = _env("HOST", "0.0.0.0")
    port: int = int(_env("PORT", "8085"))
    public_base_url: str = _env("PUBLIC_BASE_URL", "https://api.example.com").rstrip("/")
    database_path: str = _env("DATABASE_PATH", "/app/data/barka.db")
    code_secret: str = _env("CODE_SECRET", "CHANGE_ME")
    admin_token: str = _env("ADMIN_TOKEN", "CHANGE_ME")
    lomopay_public_key: str = _env("LOMOPAY_PUBLIC_KEY")
    lomopay_secret_key: str = _env("LOMOPAY_SECRET_KEY")
    lomopay_api_base: str = _env("LOMOPAY_API_BASE", "https://lomopay.net/api/v1").rstrip("/")
    cors_origins: str = _env("CORS_ORIGINS")

    def validate_runtime(self) -> None:
        Path(self.database_path).parent.mkdir(parents=True, exist_ok=True)

    @property
    def payment_ready(self) -> bool:
        return (
            bool(self.lomopay_public_key)
            and bool(self.lomopay_secret_key)
            and self.public_base_url.startswith("https://")
            and "example.com" not in self.public_base_url
        )


settings = Settings()
