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
            SELECT source_ref, plan_id, status, created_at, redeemed_at,
                   redeemed_device_id, applied_until
            FROM activation_codes
            WHERE deleted_at IS NULL
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
                "expires_at": iso(row["applied_until"]),
                "redeemed_device_id": row["redeemed_device_id"],
                "source_type": "PAIEMENT" if source_ref.startswith("PAYMENT:") else "ABONNEMENT",
            }
        )
    return result


def revoke_activation_code(code: str) -> tuple[bool, str]:
    hashed = code_hash(code)
    with transaction() as cx:
        row = cx.execute(
            "SELECT id, status, redeemed_device_id FROM activation_codes WHERE code_hash=? AND deleted_at IS NULL",
            (hashed,),
        ).fetchone()
        if not row:
            return False, "Code introuvable."
        if row["status"] == "revoked":
            return True, "Ce code est déjà désactivé."
        cx.execute(
            "UPDATE activation_codes SET status='revoked' WHERE id=?",
            (row["id"],),
        )
        if row["redeemed_device_id"]:
            cx.execute(
                "UPDATE devices SET access_disabled=1 WHERE device_id=?",
                (row["redeemed_device_id"],),
            )
    return True, "Accès désactivé. Les profils opérateurs de cet appareil sont gelés."


def reactivate_activation_code(code: str) -> tuple[bool, str]:
    hashed = code_hash(code)
    with transaction() as cx:
        row = cx.execute(
            "SELECT id, status, redeemed_device_id FROM activation_codes WHERE code_hash=? AND deleted_at IS NULL",
            (hashed,),
        ).fetchone()
        if not row:
            return False, "Code introuvable."
        if row["status"] != "revoked":
            return True, "Ce code est déjà actif."

        restored_status = "redeemed" if row["redeemed_device_id"] else "issued"
        cx.execute(
            "UPDATE activation_codes SET status=? WHERE id=?",
            (restored_status, row["id"]),
        )
        if row["redeemed_device_id"]:
            other_revoked = cx.execute(
                """
                SELECT COUNT(*) AS n FROM activation_codes
                WHERE redeemed_device_id=? AND status='revoked' AND deleted_at IS NULL
                """,
                (row["redeemed_device_id"],),
            ).fetchone()["n"]
            if int(other_revoked) == 0:
                cx.execute(
                    "UPDATE devices SET access_disabled=0 WHERE device_id=?",
                    (row["redeemed_device_id"],),
                )
    return True, "Accès réactivé."


def delete_activation_code(code: str) -> tuple[bool, str]:
    hashed = code_hash(code)
    now = now_ts()
    with transaction() as cx:
        row = cx.execute(
            """
            SELECT id, status, redeemed_device_id, duration_seconds,
                   applied_from, applied_until
            FROM activation_codes
            WHERE code_hash=? AND deleted_at IS NULL
            """,
            (hashed,),
        ).fetchone()
        if not row:
            return False, "Code introuvable."

        device_id = row["redeemed_device_id"]
        if device_id:
            device = cx.execute(
                "SELECT subscription_expires_at FROM devices WHERE device_id=?",
                (device_id,),
            ).fetchone()
            current_exp = int(device["subscription_expires_at"] or 0) if device else 0
            applied_from = int(row["applied_from"] or row["applied_until"] or now)
            applied_until = int(row["applied_until"] or (applied_from + int(row["duration_seconds"])))
            remaining_from_code = max(0, applied_until - max(now, applied_from))
            if current_exp > now and remaining_from_code > 0:
                new_exp = max(now, current_exp - remaining_from_code)
                cx.execute(
                    """
                    UPDATE devices
                    SET subscription_expires_at=?,
                        subscription_started_at=CASE WHEN ?>? THEN subscription_started_at ELSE NULL END
                    WHERE device_id=?
                    """,
                    (new_exp, new_exp, now, device_id),
                )

        cx.execute(
            "UPDATE activation_codes SET status='revoked', deleted_at=? WHERE id=?",
            (now, row["id"]),
        )

        if device_id:
            remaining_revoked_codes = cx.execute(
                """
                SELECT COUNT(*) AS n FROM activation_codes
                WHERE redeemed_device_id=? AND deleted_at IS NULL AND status='revoked'
                """,
                (device_id,),
            ).fetchone()["n"]
            if int(remaining_revoked_codes) == 0:
                cx.execute(
                    "UPDATE devices SET access_disabled=0 WHERE device_id=?",
                    (device_id,),
                )

    return True, "Code supprimé et temps restant associé retiré."


def list_redeem_codes(limit: int = 100) -> list[dict]:
    safe_limit = max(1, min(int(limit), 200))
    cx = connect()
    try:
        rows = cx.execute(
            """
            SELECT r.id, r.source_ref, r.duration_seconds, r.max_users, r.status,
                   r.created_at, COUNT(u.id) AS usage_count,
                   MAX(u.redeemed_at) AS last_redeemed_at,
                   MAX(u.applied_until) AS last_expires_at
            FROM redeem_codes r
            LEFT JOIN redeem_usages u ON u.redeem_code_id=r.id
            WHERE r.deleted_at IS NULL
            GROUP BY r.id
            ORDER BY r.id DESC
            LIMIT ?
            """,
            (safe_limit,),
        ).fetchall()
    finally:
        cx.close()

    return [
        {
            "code": activation_code_for_source(str(row["source_ref"])),
            "status": row["status"],
            "duration_seconds": int(row["duration_seconds"]),
            "max_users": int(row["max_users"]),
            "usage_count": int(row["usage_count"]),
            "created_at": iso(int(row["created_at"])),
            "last_redeemed_at": iso(row["last_redeemed_at"]),
            "last_expires_at": iso(row["last_expires_at"]),
        }
        for row in rows
    ]


def revoke_redeem_code(code: str) -> tuple[bool, str]:
    hashed = code_hash(code)
    with transaction() as cx:
        row = cx.execute(
            "SELECT id, status FROM redeem_codes WHERE code_hash=? AND deleted_at IS NULL",
            (hashed,),
        ).fetchone()
        if not row:
            return False, "Code Redeem introuvable."
        if row["status"] == "revoked":
            return True, "Ce code Redeem est déjà désactivé."
        cx.execute(
            "UPDATE redeem_codes SET status='revoked' WHERE id=?",
            (row["id"],),
        )
    return True, "Code Redeem désactivé. Les nouvelles activations sont bloquées."


def reactivate_redeem_code(code: str) -> tuple[bool, str]:
    hashed = code_hash(code)
    with transaction() as cx:
        row = cx.execute(
            "SELECT id, status FROM redeem_codes WHERE code_hash=? AND deleted_at IS NULL",
            (hashed,),
        ).fetchone()
        if not row:
            return False, "Code Redeem introuvable."
        if row["status"] == "active":
            return True, "Ce code Redeem est déjà actif."
        cx.execute(
            "UPDATE redeem_codes SET status='active' WHERE id=?",
            (row["id"],),
        )
    return True, "Code Redeem réactivé."


def delete_redeem_code(code: str) -> tuple[bool, str]:
    hashed = code_hash(code)
    now = now_ts()
    with transaction() as cx:
        row = cx.execute(
            "SELECT id FROM redeem_codes WHERE code_hash=? AND deleted_at IS NULL",
            (hashed,),
        ).fetchone()
        if not row:
            return False, "Code Redeem introuvable."

        usages = cx.execute(
            """
            SELECT device_id, applied_from, applied_until
            FROM redeem_usages
            WHERE redeem_code_id=?
            """,
            (row["id"],),
        ).fetchall()
        for usage in usages:
            device = cx.execute(
                "SELECT subscription_expires_at FROM devices WHERE device_id=?",
                (usage["device_id"],),
            ).fetchone()
            current_exp = int(device["subscription_expires_at"] or 0) if device else 0
            applied_from = int(usage["applied_from"] or now)
            applied_until = int(usage["applied_until"] or applied_from)
            remaining_from_code = max(0, applied_until - max(now, applied_from))
            if current_exp > now and remaining_from_code > 0:
                new_exp = max(now, current_exp - remaining_from_code)
                cx.execute(
                    """
                    UPDATE devices
                    SET subscription_expires_at=?,
                        subscription_started_at=CASE WHEN ?>? THEN subscription_started_at ELSE NULL END
                    WHERE device_id=?
                    """,
                    (new_exp, new_exp, now, usage["device_id"]),
                )

        cx.execute(
            "UPDATE redeem_codes SET status='revoked', deleted_at=? WHERE id=?",
            (now, row["id"]),
        )
    return True, "Code Redeem supprimé et temps restant associé retiré aux utilisateurs concernés."


def admin_stats_extended() -> dict:
    now = now_ts()
    cx = connect()
    try:
        devices = cx.execute("SELECT COUNT(*) AS n FROM devices").fetchone()["n"]
        users = cx.execute(
            "SELECT COUNT(*) AS n FROM devices WHERE last_connect_attempt_at IS NOT NULL"
        ).fetchone()["n"]
        paid = cx.execute("SELECT COUNT(*) AS n FROM payments WHERE status='paid'").fetchone()["n"]
        pending = cx.execute("SELECT COUNT(*) AS n FROM payments WHERE status='pending'").fetchone()["n"]
        issued = cx.execute(
            "SELECT COUNT(*) AS n FROM activation_codes WHERE status='issued' AND deleted_at IS NULL"
        ).fetchone()["n"]
        redeemed = cx.execute(
            "SELECT COUNT(*) AS n FROM activation_codes WHERE status='redeemed' AND deleted_at IS NULL"
        ).fetchone()["n"]
        active_subscriptions = cx.execute(
            "SELECT COUNT(*) AS n FROM devices WHERE subscription_expires_at>? AND access_disabled=0",
            (now,),
        ).fetchone()["n"]
        active_trials = cx.execute(
            """SELECT COUNT(*) AS n FROM devices
               WHERE trial_expires_at>?
                 AND (subscription_expires_at IS NULL OR subscription_expires_at<=?)""",
            (now, now),
        ).fetchone()["n"]
        enabled_profiles = cx.execute("SELECT COUNT(*) AS n FROM vpn_profiles WHERE enabled=1").fetchone()["n"]
        redeem_active = cx.execute(
            "SELECT COUNT(*) AS n FROM redeem_codes WHERE status='active' AND deleted_at IS NULL"
        ).fetchone()["n"]
        redeem_usages = cx.execute("SELECT COUNT(*) AS n FROM redeem_usages").fetchone()["n"]
    finally:
        cx.close()
    return {
        "devices": int(devices),
        "users": int(users),
        "payments_paid": int(paid),
        "payments_pending": int(pending),
        "codes_issued": int(issued),
        "codes_redeemed": int(redeemed),
        "active_subscriptions": int(active_subscriptions),
        "active_trials": int(active_trials),
        "vpn_services_enabled": int(enabled_profiles),
        "redeem_codes_active": int(redeem_active),
        "redeem_usages": int(redeem_usages),
    }
