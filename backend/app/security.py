from __future__ import annotations

import base64
import hashlib
import hmac
import re

from fastapi import Header, HTTPException, status

from .config import settings

_CODE_RE = re.compile(r"[^A-Z0-9-]")


def normalize_code(code: str) -> str:
    cleaned = _CODE_RE.sub("", code.strip().upper())
    return cleaned


def code_hash(code: str) -> str:
    return hashlib.sha256(normalize_code(code).encode("utf-8")).hexdigest()


def activation_code_for_source(source_ref: str) -> str:
    if not settings.code_secret or settings.code_secret == "CHANGE_ME":
        raise RuntimeError("CODE_SECRET n'est pas configuré")
    digest = hmac.new(
        settings.code_secret.encode("utf-8"),
        source_ref.encode("utf-8"),
        hashlib.sha256,
    ).digest()
    token = base64.b32encode(digest).decode("ascii").rstrip("=")[:12]
    return f"BARKA-{token[:4]}-{token[4:8]}-{token[8:12]}"


def verify_lomopay_signature(raw_body: bytes, signature: str | None) -> bool:
    if not signature or not settings.lomopay_secret_key:
        return False
    expected = "sha256=" + hmac.new(
        settings.lomopay_secret_key.encode("utf-8"),
        raw_body,
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(expected, signature.strip())


def require_admin(x_admin_token: str | None = Header(default=None)) -> None:
    if (
        not settings.admin_token
        or settings.admin_token == "CHANGE_ME"
        or not x_admin_token
        or not hmac.compare_digest(settings.admin_token, x_admin_token)
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Accès administrateur refusé",
        )
