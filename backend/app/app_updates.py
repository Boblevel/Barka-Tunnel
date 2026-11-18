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
        "orange_ip_indicator_enabled": bool(row["orange_ip_indicator_enabled"]),
        "download_in_app": bool(row["download_in_app"]),
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
        # Display setting is synchronized even when no APK update is available.
        "orange_ip_indicator_enabled": item["orange_ip_indicator_enabled"],
        # Older installed clients also use enabled to decide notifications.
        "download_in_app": item["download_in_app"],
        "enabled": available,
        "update_available": available,
        "force_update": available and bool(item["mandatory"]),
        "latest_version_code": int(item["latest_version_code"]),
        "latest_version_name": item["latest_version_name"],
        "apk_url": item["apk_url"] if item["enabled"] else "",
        "message": item["message"],
        "updated_at": item["updated_at"],
    }


def set_orange_ip_indicator(enabled: bool) -> dict:
    # Independent from APK publication: do not change its revision or notification.
    with transaction() as cx:
        cx.execute("UPDATE app_update SET orange_ip_indicator_enabled=? WHERE id=1", (int(enabled),))
    return {"enabled": enabled}


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
    if enabled and payload.get("download_in_app"):
        # Freeze our mutable VPS link before announcing it. Existing clients
        # cannot safely finish a download if the file changes under that URL.
        from .apk_metadata import read_apk_metadata
        from .apk_release import publish
        import hashlib
        import re
        import shutil
        import tempfile
        url = urlsplit(apk_url)
        if url.netloc == urlsplit(settings.public_base_url).netloc:
            match = re.fullmatch(r"/downloads/apk/([a-f0-9]{64})\.apk", url.path)
            path = (release_apk_path().parent / (match[1] + '.apk')) if match else (
                release_apk_path() if url.path == '/downloads/BarkaTunnel.apk' else None)
            if path is None or not path.is_file():
                raise ValueError("APK absent du serveur. Téléversez le fichier avant de publier.")
            # Open the source once: a deployment may atomically replace its
            # pathname while we verify. Publish the exact bytes verified here.
            with path.open('rb') as source, tempfile.NamedTemporaryFile(dir=path.parent, suffix='.apk') as snapshot:
                shutil.copyfileobj(source, snapshot)
                snapshot.flush()
                info = read_apk_metadata(snapshot.name)
                if info['version_code'] != version_code or info['version_name'] != version_name:
                    raise ValueError(f"L’APK contient la version {info['version_name']} (code {info['version_code']}). Corrigez la version annoncée ou téléversez le bon APK.")
                snapshot.seek(0)
                digest = hashlib.file_digest(snapshot, 'sha256').hexdigest()
                if match and digest != match[1]:
                    raise ValueError("APK endommagé sur le serveur. Téléversez-le de nouveau.")
                apk_url = publish(snapshot.name, digest, Path(snapshot.name).stat().st_size)['apk_url']
    with transaction() as cx:
        cx.execute(
            """UPDATE app_update
               SET download_in_app=?, enabled=?, latest_version_code=?, latest_version_name=?,
                   apk_url=?, message=?, mandatory=?,
                   updated_at=MAX(updated_at + 1, ?)
               WHERE id=1""",
            (
                int(bool(payload.get("download_in_app"))),
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
