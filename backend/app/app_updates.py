from __future__ import annotations

from pathlib import Path
from urllib.parse import urlsplit

from .config import settings

from .db import connect, transaction
from .services import iso, now_ts


APKPURE_UPDATE_URL = "https://apkpure.com/p/com.barkatunnel.app"


def valid_download_url(value: str) -> bool:
    if not value or len(value) > 1000 or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in value) or "\\" in value:
        return False
    try:
        url = urlsplit(value)
        return (url.scheme == "https" and bool(url.hostname)
                and url.username is None and url.password is None
                and (url.port is None or 1 <= url.port <= 65535))
    except ValueError:
        return False


def _row_to_admin(row) -> dict:
    return {
        "enabled": bool(row["enabled"]),
        "latest_version_code": int(row["latest_version_code"]),
        "latest_version_name": str(row["latest_version_name"]),
        "apk_url": str(row["apk_url"] or APKPURE_UPDATE_URL),
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
        # Older installed clients also use enabled to decide notifications.
        "enabled": available,
        "update_available": available,
        "force_update": available and bool(item["mandatory"]),
        "latest_version_code": int(item["latest_version_code"]),
        "latest_version_name": item["latest_version_name"],
        "apk_url": item["apk_url"] if item["enabled"] else "",
        "message": item["message"],
        "updated_at": item["updated_at"],
    }


def upsert_app_update(payload: dict) -> dict:
    enabled = bool(payload.get("enabled"))
    mandatory = bool(payload.get("mandatory"))
    version_code = int(payload.get("latest_version_code", 1))
    version_name = str(payload.get("latest_version_name", "")).strip()
    message = str(payload.get("message", "")).strip()
    if not version_name:
        raise ValueError("Le nom de version est obligatoire.")
    apk_url = str(payload.get("apk_url", "")).strip()
    if not apk_url and not enabled:
        apk_url = get_app_update_admin()["apk_url"]
    if not valid_download_url(apk_url):
        raise ValueError("Indiquez un lien HTTPS valide, sans identifiants ni espaces.")
    if mandatory and not enabled:
        raise ValueError("Une mise à jour obligatoire doit être activée.")
    with transaction() as cx:
        cx.execute(
            """UPDATE app_update
               SET enabled=?, latest_version_code=?, latest_version_name=?,
                   apk_url=?, message=?, mandatory=?,
                   updated_at=MAX(updated_at + 1, ?)
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


def remove_app_update() -> dict:
    with transaction() as cx:
        cx.execute(
            """UPDATE app_update
               SET enabled=0, mandatory=0,
                   updated_at=MAX(updated_at + 1, ?)
               WHERE id=1""",
            (now_ts(),),
        )
    return get_app_update_admin()


def release_apk_path() -> Path:
    return Path(settings.database_path).parent / "releases" / "BarkaTunnel.apk"


def release_apk_url() -> str:
    return settings.public_base_url + "/downloads/BarkaTunnel.apk"
