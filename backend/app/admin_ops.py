from __future__ import annotations

from .db import connect, transaction
from .security import activation_code_for_source, code_hash
from .services import iso, now_ts


def list_activation_codes(limit: int = 100) -> list[dict]:
    safe_limit = max(1, min(int(limit), 200))
    cx = connect()
    try:
        rows = cx.execute(
            """
            SELECT source_ref, plan_id, status, created_at,
                   redeemed_at, redeemed_device_id
            FROM activation_codes
            ORDER BY id DESC
            LIMIT ?
            """,
            (safe_limit,),
        ).fetchall()
    finally:
        cx.close()

    result: list[dict] = []
    for row in rows:
        source_ref = str(row["source_ref"])
        result.append(
            {
                "code": activation_code_for_source(source_ref),
                "plan_id": row["plan_id"],
                "status": row["status"],
                "created_at": iso(int(row["created_at"])),
                "redeemed_at": iso(row["redeemed_at"]),
                "redeemed_device_id": row["redeemed_device_id"],
                "source_type": "PAIEMENT" if source_ref.startswith("PAYMENT:") else "MANUEL",
            }
        )
    return result


def revoke_activation_code(code: str) -> tuple[bool, str]:
    hashed = code_hash(code)
    with transaction() as cx:
        row = cx.execute(
            "SELECT id, status FROM activation_codes WHERE code_hash=?",
            (hashed,),
        ).fetchone()
        if not row:
            return False, "Code introuvable."
        if row["status"] == "redeemed":
            return False, "Un code déjà utilisé ne peut pas être révoqué."
        if row["status"] == "revoked":
            return True, "Ce code est déjà désactivé."
        cx.execute(
            "UPDATE activation_codes SET status='revoked' WHERE id=?",
            (row["id"],),
        )
    return True, "Code désactivé."


def admin_stats_extended() -> dict:
    now = now_ts()
    cx = connect()
    try:
        devices = cx.execute("SELECT COUNT(*) AS n FROM devices").fetchone()["n"]
        paid = cx.execute("SELECT COUNT(*) AS n FROM payments WHERE status='paid'").fetchone()["n"]
        pending = cx.execute("SELECT COUNT(*) AS n FROM payments WHERE status='pending'").fetchone()["n"]
        issued = cx.execute("SELECT COUNT(*) AS n FROM activation_codes WHERE status='issued'").fetchone()["n"]
        redeemed = cx.execute("SELECT COUNT(*) AS n FROM activation_codes WHERE status='redeemed'").fetchone()["n"]
        active_subscriptions = cx.execute(
            "SELECT COUNT(*) AS n FROM devices WHERE subscription_expires_at>?", (now,)
        ).fetchone()["n"]
        active_trials = cx.execute(
            """SELECT COUNT(*) AS n FROM devices
               WHERE trial_expires_at>?
                 AND (subscription_expires_at IS NULL OR subscription_expires_at<=?)""",
            (now, now),
        ).fetchone()["n"]
        enabled_profiles = cx.execute("SELECT COUNT(*) AS n FROM vpn_profiles WHERE enabled=1").fetchone()["n"]
    finally:
        cx.close()
    return {
        "devices": int(devices),
        "payments_paid": int(paid),
        "payments_pending": int(pending),
        "codes_issued": int(issued),
        "codes_redeemed": int(redeemed),
        "active_subscriptions": int(active_subscriptions),
        "active_trials": int(active_trials),
        "vpn_services_enabled": int(enabled_profiles),
    }
