"""Expiration revendeur : suspension calculée, sans modifier les durées des codes."""
from __future__ import annotations

RENEWAL_MESSAGE = "Votre sous-panel a expiré et ses codes sont gelés. Veuillez vous réabonner auprès de l’administrateur."


def code_suspended(cx, reseller_id: int | None, now: int) -> bool:
    if reseller_id is None:
        return False
    row = cx.execute("SELECT expires_at, deleted_at FROM reseller_accounts WHERE id=?", (reseller_id,)).fetchone()
    return row is None or row["deleted_at"] is not None or int(row["expires_at"]) <= now


def subscription_expiry(cx, device_id: str, stored_expiry: int | None, now: int) -> int | None:
    """Clip only credit intervals supplied by expired resellers, without extending dates."""
    if stored_expiry is None or int(stored_expiry) <= now:
        return stored_expiry
    expiry = int(stored_expiry)
    rows = cx.execute(
        """SELECT a.applied_from, a.applied_until, a.redeemed_at,
                  r.expires_at, r.deleted_at, r.id AS reseller_id
           FROM activation_codes a
           LEFT JOIN reseller_accounts r ON r.id=a.created_by_reseller_id
           WHERE a.redeemed_device_id=? AND a.created_by_reseller_id IS NOT NULL
             AND a.status='redeemed' AND a.deleted_at IS NULL AND a.applied_until>?""",
        (device_id, now),
    ).fetchall()
    for row in rows:
        start = int(row["applied_from"] or row["redeemed_at"] or now)
        end = int(row["applied_until"])
        blocked_from = start if row["reseller_id"] is None or row["deleted_at"] is not None else max(start, int(row["expires_at"]))
        if blocked_from < end:
            if blocked_from <= now:
                return now
            expiry = min(expiry, blocked_from)
    return expiry
