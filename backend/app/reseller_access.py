"""Expiration revendeur : suspension calculée, sans modifier les durées des codes."""
from __future__ import annotations

RENEWAL_MESSAGE = "Votre sous-panel a expiré et ses codes sont gelés. Veuillez vous réabonner auprès de l’administrateur."


def code_suspended(cx, reseller_id: int | None, now: int) -> bool:
    if reseller_id is None:
        return False
    row = cx.execute("SELECT expires_at, deleted_at FROM reseller_accounts WHERE id=?", (reseller_id,)).fetchone()
    return row is None or row["deleted_at"] is not None or int(row["expires_at"]) <= now


def _blocked_from(cx, device_id, now):
    return cx.execute("""SELECT MIN(MAX(COALESCE(a.applied_from,a.redeemed_at),r.expires_at))
        FROM activation_codes a JOIN reseller_accounts r ON r.id=a.created_by_reseller_id
        WHERE a.redeemed_device_id=? AND a.status='redeemed' AND a.deleted_at IS NULL
          AND r.deleted_at IS NULL AND r.expires_at<=?
          AND a.applied_until>MAX(COALESCE(a.applied_from,a.redeemed_at),r.expires_at)""", (device_id,now)).fetchone()[0]


def settle_device_pause(cx, device_id, now):
    start = _blocked_from(cx, device_id, now)
    if start is None or start > now:
        return
    device = cx.execute("SELECT pause_accounted_until FROM devices WHERE device_id=?", (device_id,)).fetchone()
    start = max(int(start), int(device[0] or 0))
    delta = now - start
    if delta <= 0:
        return
    cx.execute("""UPDATE activation_codes SET
        applied_from=CASE WHEN applied_from>=? THEN applied_from+? ELSE applied_from END,
        applied_until=applied_until+?
        WHERE redeemed_device_id=? AND status='redeemed' AND deleted_at IS NULL AND applied_until>?""", (start,delta,delta,device_id,start))
    cx.execute("""UPDATE redeem_usages SET
        applied_from=CASE WHEN applied_from>=? THEN applied_from+? ELSE applied_from END,
        applied_until=applied_until+? WHERE device_id=? AND applied_until>?
        AND redeem_code_id IN (SELECT id FROM redeem_codes WHERE status='active' AND deleted_at IS NULL)""", (start,delta,delta,device_id,start))
    cx.execute("UPDATE weekly_trial_claims SET applied_until=applied_until+? WHERE device_id=? AND applied_until>?", (delta,device_id,start))
    cx.execute("""UPDATE devices SET pause_accounted_until=?,
        subscription_expires_at=CASE WHEN subscription_expires_at>? THEN subscription_expires_at+? ELSE subscription_expires_at END
        WHERE device_id=?""", (now,start,delta,device_id))


def settle_reseller_pauses(cx, reseller_id, now):
    query = "SELECT DISTINCT redeemed_device_id FROM activation_codes WHERE status='redeemed' AND deleted_at IS NULL AND created_by_reseller_id IS NOT NULL AND redeemed_device_id IS NOT NULL"
    params = ()
    if reseller_id is not None:
        query += " AND created_by_reseller_id=?"
        params = (reseller_id,)
    for row in cx.execute(query,params).fetchall():
        settle_device_pause(cx,row[0],now)


def subscription_expiry(cx, device_id, stored_expiry, now):
    start = _blocked_from(cx,device_id,now)
    return now if start is not None and start <= now else stored_expiry
