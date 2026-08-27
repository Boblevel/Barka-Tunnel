from __future__ import annotations

import time
import uuid
from datetime import datetime, timezone

from .config import settings
from .db import connect, transaction
from .plans import get_plan
from .security import activation_code_for_source, code_hash


def now_ts() -> int:
    return int(time.time())


def iso(ts: int | None) -> str | None:
    if ts is None:
        return None
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def ensure_device(device_id: str) -> None:
    now = now_ts()
    with transaction() as cx:
        cx.execute(
            """
            INSERT INTO devices(device_id, created_at, last_seen_at)
            VALUES(?,?,?)
            ON CONFLICT(device_id) DO UPDATE SET last_seen_at=excluded.last_seen_at
            """,
            (device_id, now, now),
        )


def mark_connection_attempt(device_id: str) -> None:
    ensure_device(device_id)
    now = now_ts()
    with transaction() as cx:
        cx.execute(
            "UPDATE devices SET last_connect_attempt_at=?, last_seen_at=? WHERE device_id=?",
            (now, now, device_id),
        )


def access_state(device_id: str) -> dict:
    ensure_device(device_id)
    now = now_ts()
    cx = connect()
    try:
        row = cx.execute(
            "SELECT * FROM devices WHERE device_id=?",
            (device_id,),
        ).fetchone()
    finally:
        cx.close()

    if bool(row["access_disabled"]):
        return {
            "allowed": False,
            "access_type": "NONE",
            "server_time": iso(now),
            "started_at": None,
            "expires_at": None,
            "remaining_seconds": 0,
        }

    sub_exp = row["subscription_expires_at"]
    if sub_exp is not None and int(sub_exp) > now:
        start = row["subscription_started_at"]
        return {
            "allowed": True,
            "access_type": "SUBSCRIPTION",
            "server_time": iso(now),
            "started_at": iso(start),
            "expires_at": iso(sub_exp),
            "remaining_seconds": max(0, int(sub_exp) - now),
        }

    trial_exp = row["trial_expires_at"]
    if trial_exp is not None and int(trial_exp) > now:
        start = row["trial_started_at"]
        return {
            "allowed": True,
            "access_type": "TRIAL",
            "server_time": iso(now),
            "started_at": iso(start),
            "expires_at": iso(trial_exp),
            "remaining_seconds": max(0, int(trial_exp) - now),
        }

    return {
        "allowed": False,
        "access_type": "NONE",
        "server_time": iso(now),
        "started_at": None,
        "expires_at": None,
        "remaining_seconds": 0,
    }


def start_trial(device_id: str) -> tuple[dict, bool, str]:
    ensure_device(device_id)
    current = access_state(device_id)
    cx = connect()
    try:
        suspended = cx.execute(
            "SELECT access_disabled FROM devices WHERE device_id=?",
            (device_id,),
        ).fetchone()
    finally:
        cx.close()
    if suspended and bool(suspended["access_disabled"]):
        return current, False, "Accès désactivé par l’administration."
    if current["access_type"] == "SUBSCRIPTION":
        return current, False, "Un abonnement est déjà actif."

    now = now_ts()
    with transaction() as cx:
        row = cx.execute(
            "SELECT trial_started_at, trial_expires_at FROM devices WHERE device_id=?",
            (device_id,),
        ).fetchone()

        if row["trial_started_at"] is None:
            expires = now + 2 * 60 * 60
            cx.execute(
                """
                UPDATE devices
                SET trial_started_at=?, trial_expires_at=?, last_seen_at=?
                WHERE device_id=?
                """,
                (now, expires, now, device_id),
            )
            started_now = True
            message = "Essai gratuit de 2 heures démarré."
        else:
            started_now = False
            message = "L'essai gratuit de cet appareil a déjà été utilisé."

    return access_state(device_id), started_now, message


def issue_activation_code(
    source_ref: str,
    plan_id: str,
    payment_reference: str | None = None,
) -> str:
    plan = get_plan(plan_id)
    if not plan:
        raise ValueError("Plan inconnu")

    code = activation_code_for_source(source_ref)
    hashed = code_hash(code)
    now = now_ts()

    with transaction() as cx:
        cx.execute(
            """
            INSERT OR IGNORE INTO activation_codes(
                code_hash, source_ref, plan_id, duration_seconds,
                payment_reference, status, created_at
            ) VALUES(?,?,?,?,?,'issued',?)
            """,
            (
                hashed,
                source_ref,
                plan_id,
                int(plan["duration_seconds"]),
                payment_reference,
                now,
            ),
        )
    return code


def issue_redeem_code(source_ref: str, duration_seconds: int, max_users: int) -> str:
    duration = int(duration_seconds)
    limit = int(max_users)
    if duration <= 0:
        raise ValueError("Durée invalide")
    if limit <= 0:
        raise ValueError("Limite utilisateurs invalide")

    code = activation_code_for_source(source_ref)
    hashed = code_hash(code)
    now = now_ts()
    with transaction() as cx:
        cx.execute(
            """
            INSERT OR IGNORE INTO redeem_codes(
                code_hash, source_ref, duration_seconds, max_users, status, created_at
            ) VALUES(?,?,?,?,'active',?)
            """,
            (hashed, source_ref, duration, limit, now),
        )
    return code


def _redeem_shared_code(cx, device_id: str, hashed: str, now: int) -> tuple[bool, str] | None:
    row = cx.execute(
        "SELECT * FROM redeem_codes WHERE code_hash=? AND deleted_at IS NULL",
        (hashed,),
    ).fetchone()
    if not row:
        return None
    if row["status"] != "active":
        return False, "Ce code Redeem a été désactivé."

    existing = cx.execute(
        "SELECT applied_until FROM redeem_usages WHERE redeem_code_id=? AND device_id=?",
        (row["id"], device_id),
    ).fetchone()
    if existing:
        return True, "Ce code Redeem a déjà été utilisé sur cet appareil."

    usage_count = int(cx.execute(
        "SELECT COUNT(*) AS n FROM redeem_usages WHERE redeem_code_id=?",
        (row["id"],),
    ).fetchone()["n"])
    if usage_count >= int(row["max_users"]):
        return False, "La limite d’utilisateurs de ce code Redeem est atteinte."

    device = cx.execute(
        "SELECT * FROM devices WHERE device_id=?",
        (device_id,),
    ).fetchone()
    current_exp = device["subscription_expires_at"]
    if current_exp is not None and int(current_exp) > now:
        base = int(current_exp)
        started = int(device["subscription_started_at"] or now)
    else:
        base = now
        started = now
    new_exp = base + int(row["duration_seconds"])

    cx.execute(
        """
        UPDATE devices
        SET subscription_started_at=?, subscription_expires_at=?, last_seen_at=?
        WHERE device_id=?
        """,
        (started, new_exp, now, device_id),
    )
    cx.execute(
        """
        INSERT INTO redeem_usages(
            redeem_code_id, device_id, redeemed_at, applied_from, applied_until
        ) VALUES(?,?,?,?,?)
        """,
        (row["id"], device_id, now, base, new_exp),
    )
    return True, "Code Redeem activé avec succès."


def redeem_activation_code(device_id: str, code: str) -> tuple[bool, str, dict]:
    ensure_device(device_id)
    hashed = code_hash(code)
    now = now_ts()

    success = False
    message = "Code invalide."

    cx = connect()
    try:
        device = cx.execute(
            "SELECT access_disabled FROM devices WHERE device_id=?",
            (device_id,),
        ).fetchone()
    finally:
        cx.close()
    if device and bool(device["access_disabled"]):
        return False, "Accès désactivé par l’administration.", access_state(device_id)

    with transaction() as cx:
        row = cx.execute(
            "SELECT * FROM activation_codes WHERE code_hash=?",
            (hashed,),
        ).fetchone()

        if not row:
            shared_result = _redeem_shared_code(cx, device_id, hashed, now)
            if shared_result is None:
                success = False
                message = "Code invalide."
            else:
                success, message = shared_result
        elif row["status"] == "redeemed":
            if row["redeemed_device_id"] == device_id:
                success = True
                message = "Ce code est déjà actif sur cet appareil."
            else:
                success = False
                message = "Ce code a déjà été utilisé."
        elif row["status"] != "issued":
            success = False
            message = "Ce code a été désactivé."
        else:
            device = cx.execute(
                "SELECT * FROM devices WHERE device_id=?",
                (device_id,),
            ).fetchone()

            current_exp = device["subscription_expires_at"]
            if current_exp is not None and int(current_exp) > now:
                base = int(current_exp)
                started = int(device["subscription_started_at"] or now)
            else:
                base = now
                started = now

            new_exp = base + int(row["duration_seconds"])

            cx.execute(
                """
                UPDATE devices
                SET subscription_started_at=?,
                    subscription_expires_at=?,
                    last_seen_at=?
                WHERE device_id=?
                """,
                (started, new_exp, now, device_id),
            )

            cx.execute(
                """
                UPDATE activation_codes
                SET status='redeemed', redeemed_at=?, redeemed_device_id=?,
                    applied_from=?, applied_until=?
                WHERE id=?
                """,
                (now, device_id, base, new_exp, row["id"]),
            )

            success = True
            message = "Abonnement activé avec succès."

    return success, message, access_state(device_id)


def create_payment_record(device_id: str, plan_id: str) -> str:
    ensure_device(device_id)
    plan = get_plan(plan_id)
    if not plan:
        raise ValueError("Plan inconnu")

    now = now_ts()
    reference = f"BT-{now}-{uuid.uuid4().hex[:10].upper()}"

    with transaction() as cx:
        cx.execute(
            """
            INSERT INTO payments(
                reference, device_id, plan_id, amount, currency,
                status, created_at, updated_at
            ) VALUES(?,?,?,?,?,'creating',?,?)
            """,
            (
                reference,
                device_id,
                plan_id,
                int(plan["amount"]),
                plan["currency"],
                now,
                now,
            ),
        )
    return reference


def recent_pending_payment(device_id: str, plan_id: str, max_age_seconds: int = 900):
    cutoff = now_ts() - max_age_seconds
    cx = connect()
    try:
        return cx.execute(
            """
            SELECT * FROM payments
            WHERE device_id=? AND plan_id=? AND status='pending'
              AND created_at>=? AND checkout_url IS NOT NULL
            ORDER BY created_at DESC LIMIT 1
            """,
            (device_id, plan_id, cutoff),
        ).fetchone()
    finally:
        cx.close()


def update_payment_created(
    reference: str,
    provider_payment_id: str,
    checkout_url: str,
) -> None:
    now = now_ts()
    with transaction() as cx:
        cx.execute(
            """
            UPDATE payments
            SET provider_payment_id=?, checkout_url=?,
                status='pending', updated_at=?
            WHERE reference=?
            """,
            (provider_payment_id, checkout_url, now, reference),
        )


def mark_payment_error(reference: str, message: str) -> None:
    now = now_ts()
    with transaction() as cx:
        cx.execute(
            """
            UPDATE payments
            SET status='error', provider_error=?, updated_at=?
            WHERE reference=?
            """,
            (message[:1000], now, reference),
        )


def get_payment_for_device(reference: str, device_id: str):
    cx = connect()
    try:
        return cx.execute(
            """
            SELECT * FROM payments WHERE reference=? AND device_id=?
            """,
            (reference, device_id),
        ).fetchone()
    finally:
        cx.close()


def get_payment_by_any_reference(
    *,
    local_reference: str | None = None,
    provider_payment_id: str | None = None,
):
    cx = connect()
    try:
        if local_reference:
            row = cx.execute(
                "SELECT * FROM payments WHERE reference=?",
                (local_reference,),
            ).fetchone()
            if row:
                return row
        if provider_payment_id:
            return cx.execute(
                "SELECT * FROM payments WHERE provider_payment_id=?",
                (provider_payment_id,),
            ).fetchone()
        return None
    finally:
        cx.close()


def mark_payment_paid(reference: str) -> str:
    now = now_ts()
    with transaction() as cx:
        payment = cx.execute(
            "SELECT * FROM payments WHERE reference=?",
            (reference,),
        ).fetchone()
        if not payment:
            raise ValueError("Paiement local introuvable")

        if payment["status"] != "paid":
            source_ref = payment["activation_source_ref"] or f"PAYMENT:{reference}"
            cx.execute(
                """
                UPDATE payments
                SET status='paid', activation_source_ref=?, updated_at=?
                WHERE reference=?
                """,
                (source_ref, now, reference),
            )
        else:
            source_ref = payment["activation_source_ref"] or f"PAYMENT:{reference}"

    return issue_activation_code(source_ref, payment["plan_id"], reference)


def mark_payment_failed(reference: str) -> None:
    with transaction() as cx:
        cx.execute(
            "UPDATE payments SET status='failed', updated_at=? WHERE reference=?",
            (now_ts(), reference),
        )


def activation_code_for_payment(reference: str) -> str | None:
    cx = connect()
    try:
        payment = cx.execute(
            "SELECT * FROM payments WHERE reference=?",
            (reference,),
        ).fetchone()
    finally:
        cx.close()
    if not payment or payment["status"] != "paid":
        return None
    source_ref = payment["activation_source_ref"] or f"PAYMENT:{reference}"
    return activation_code_for_source(source_ref)


def register_webhook_event(event_id: str) -> bool:
    with transaction() as cx:
        existing = cx.execute(
            "SELECT event_id FROM webhook_events WHERE event_id=?",
            (event_id,),
        ).fetchone()
        if existing:
            return False
        cx.execute(
            "INSERT INTO webhook_events(event_id, received_at) VALUES(?,?)",
            (event_id, now_ts()),
        )
        return True
