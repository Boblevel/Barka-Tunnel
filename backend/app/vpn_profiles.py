from __future__ import annotations

import json

from .db import connect, transaction
from .services import access_state, iso, now_ts

EXPECTED_PROTOCOL = {
    "moov_bf": "SLOWDNS",
    "orange_bf": "VLESS",
    "telecel_bf": "UDP",
}


def _row_to_public(row) -> dict:
    return {
        "network_id": row["network_id"],
        "display_name": row["display_name"],
        "protocol": row["protocol"],
        "enabled": bool(row["enabled"]),
        "maintenance": bool(row["maintenance"]),
        "priority": int(row["priority"]),
        "version": int(row["version"]),
        "updated_at": iso(int(row["updated_at"])),
    }


def _decode_config(raw: str) -> dict:
    try:
        value = json.loads(raw or "{}")
    except Exception:
        return {}
    return value if isinstance(value, dict) else {}


def list_catalog() -> list[dict]:
    cx = connect()
    try:
        rows = cx.execute(
            "SELECT * FROM vpn_profiles ORDER BY priority, network_id"
        ).fetchall()
    finally:
        cx.close()
    return [_row_to_public(row) for row in rows]


def get_profile_for_device(device_id: str, network_id: str) -> dict:
    access = access_state(device_id)
    if not access["allowed"]:
        raise PermissionError("Aucun accès actif.")

    cx = connect()
    try:
        row = cx.execute(
            "SELECT * FROM vpn_profiles WHERE network_id=?",
            (network_id,),
        ).fetchone()
    finally:
        cx.close()

    if not row or not bool(row["enabled"]):
        raise LookupError("Service VPN temporairement indisponible pour ce réseau.")
    if bool(row["maintenance"]):
        raise LookupError("Réseau en maintenance. Réessaie plus tard.")

    config = _decode_config(row["config_json"] or "{}")
    if not config:
        raise LookupError("Configuration VPN non publiée pour ce réseau.")

    result = _row_to_public(row)
    result["config"] = config
    return result


def list_admin_profiles() -> list[dict]:
    cx = connect()
    try:
        rows = cx.execute(
            "SELECT * FROM vpn_profiles ORDER BY priority, network_id"
        ).fetchall()
    finally:
        cx.close()

    result: list[dict] = []
    for row in rows:
        item = _row_to_public(row)
        item["config"] = _decode_config(row["config_json"] or "{}")
        result.append(item)
    return result


def upsert_admin_profile(payload: dict) -> dict:
    network_id = str(payload["network_id"])
    protocol = str(payload["protocol"]).upper()
    expected = EXPECTED_PROTOCOL.get(network_id)
    if expected is None:
        raise ValueError("Réseau inconnu.")
    if protocol != expected:
        raise ValueError(f"{network_id} doit utiliser le protocole {expected}.")

    config = payload.get("config") or {}
    if not isinstance(config, dict):
        raise ValueError("Configuration VPN invalide.")
    if payload.get("enabled") and not config:
        raise ValueError("Impossible d'activer un service sans configuration.")

    now = now_ts()
    encoded = json.dumps(config, ensure_ascii=False, separators=(",", ":"))

    with transaction() as cx:
        current = cx.execute(
            "SELECT version FROM vpn_profiles WHERE network_id=?",
            (network_id,),
        ).fetchone()
        version = int(current["version"] if current else 0) + 1
        cx.execute(
            """
            INSERT INTO vpn_profiles(
                network_id, display_name, protocol, enabled, maintenance, priority,
                version, config_json, updated_at
            ) VALUES(?,?,?,?,?,?,?,?,?)
            ON CONFLICT(network_id) DO UPDATE SET
                display_name=excluded.display_name,
                protocol=excluded.protocol,
                enabled=excluded.enabled,
                maintenance=excluded.maintenance,
                priority=excluded.priority,
                version=excluded.version,
                config_json=excluded.config_json,
                updated_at=excluded.updated_at
            """,
            (
                network_id,
                str(payload["display_name"]).strip(),
                protocol,
                1 if payload.get("enabled") else 0,
                1 if payload.get("maintenance") else 0,
                int(payload.get("priority", 100)),
                version,
                encoded,
                now,
            ),
        )

    return next(item for item in list_admin_profiles() if item["network_id"] == network_id)
