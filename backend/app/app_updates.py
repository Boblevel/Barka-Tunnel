from __future__ import annotations

from urllib.parse import urlparse
from pathlib import Path

from .config import settings

from .db import connect, transaction
from .services import iso, now_ts


VERCEL_APK_BASE_URL = "https://barkatunnel.vercel.app/downloads/BarkaTunnel.apk"


def _vercel_apk_url(version_code: int) -> str:
    return f"{VERCEL_APK_BASE_URL}?v={version_code}"


def _row_to_admin(row) -> dict:
    return {
        "enabled": bool(row["enabled"]),
        "latest_version_code": int(row["latest_version_code"]),
        "latest_version_name": str(row["latest_version_name"]),
        "apk_url": str(row["apk_url"] or ""),
        "message": str(row["message"] or ""),
        "mandatory": bool(row["mandatory"]),
        "updated_at": iso(int(row["updated_at"])),
    }


def get_app_update_admin() -> dict:
    cx = connect()
    try:
        row = cx.execute("SELECT * FROM app_update WHERE id=1").fetchone()
    finally:
        cx.close()
    if not row:
        raise RuntimeError("Configuration de mise à jour absente.")
    return _row_to_admin(row)


def get_app_update_for_client(current_version_code: int) -> dict:
    item = get_app_update_admin()
    available = bool(item["enabled"]) and int(item["latest_version_code"]) > int(current_version_code)
    return {
        "enabled": bool(item["enabled"]),
        "update_available": available,
        "force_update": available and bool(item["mandatory"]),
        "latest_version_code": int(item["latest_version_code"]),
        "latest_version_name": item["latest_version_name"],
        "apk_url": item["apk_url"] if available else "",
        "message": item["message"],
        "updated_at": item["updated_at"],
    }


def upsert_app_update(payload: dict) -> dict:
    enabled = bool(payload.get("enabled"))
    mandatory = bool(payload.get("mandatory"))
    version_code = int(payload.get("latest_version_code", 1))
    version_name = str(payload.get("latest_version_name", "")).strip()
    apk_url = str(payload.get("apk_url", "")).strip()
    message = str(payload.get("message", "")).strip()
    if not version_name:
        raise ValueError("Le nom de version est obligatoire.")
    if enabled:
        apk_url = _vercel_apk_url(version_code)
        parsed = urlparse(apk_url)
        if parsed.scheme != "https" or not parsed.netloc:
            raise ValueError("Une URL APK HTTPS valide est obligatoire quand la mise à jour est activée.")
    if mandatory and not enabled:
        raise ValueError("Une mise à jour obligatoire doit être activée.")
    with transaction() as cx:
        cx.execute(
            """UPDATE app_update
               SET enabled=?, latest_version_code=?, latest_version_name=?,
                   apk_url=?, message=?, mandatory=?, updated_at=?
               WHERE id=1""",
            (
                1 if enabled else 0,
                version_code,
                version_name,
                apk_url,
                message or "Une nouvelle version de Barka Tunnel est disponible.",
                1 if mandatory else 0,
                now_ts(),
            ),
        )
    return get_app_update_admin()


def release_apk_path() -> Path:
    return Path(settings.database_path).parent / "releases" / "BarkaTunnel.apk"


def release_apk_url() -> str:
    return f"{settings.public_base_url}/downloads/BarkaTunnel.apk"
