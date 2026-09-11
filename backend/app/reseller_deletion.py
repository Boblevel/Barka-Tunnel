"""Remove a reseller's credit atomically, preserving other grants and audit history."""
from .reseller_audit import record_event


def remove_reseller_access(cx, reseller_id: int, now: int) -> None:
    rows = cx.execute("SELECT * FROM activation_codes WHERE created_by_reseller_id=? AND deleted_at IS NULL", (reseller_id,)).fetchall()
    devices = {r["redeemed_device_id"] for r in rows if r["redeemed_device_id"]}
    for device_id in devices:
        device = cx.execute("SELECT * FROM devices WHERE device_id=?", (device_id,)).fetchone()
        if not device:
            continue
        expiry = int(device["subscription_expires_at"] or 0)
        intervals = sorted((max(now, int(r["applied_from"] or r["redeemed_at"] or now)), min(expiry, int(r["applied_until"] or 0)))
                           for r in rows if r["redeemed_device_id"] == device_id)
        merged = []
        for start, end in intervals:
            if end <= start:
                continue
            if merged and start <= merged[-1][1]:
                merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
            else:
                merged.append((start, end))
        def compact(value):
            if value is None:
                return None
            return value - sum(max(0, min(value, end) - start) for start, end in merged)
        new_expiry = compact(expiry)
        for table, owner in (("activation_codes", "redeemed_device_id"), ("redeem_usages", "device_id")):
            grants = cx.execute(f"SELECT id,applied_from,applied_until FROM {table} WHERE {owner}=? AND applied_until>?", (device_id, now)).fetchall()
            for grant in grants:
                cx.execute(f"UPDATE {table} SET applied_from=?,applied_until=? WHERE id=?", (compact(grant["applied_from"]), compact(grant["applied_until"]), grant["id"]))
        for claim in cx.execute("SELECT week_start,applied_until FROM weekly_trial_claims WHERE device_id=? AND applied_until>?", (device_id, now)).fetchall():
            cx.execute("UPDATE weekly_trial_claims SET applied_until=? WHERE device_id=? AND week_start=?", (compact(claim["applied_until"]), device_id, claim["week_start"]))
        cx.execute("UPDATE devices SET subscription_expires_at=?, subscription_started_at=CASE WHEN ?>? THEN subscription_started_at ELSE NULL END, access_revision=access_revision+1 WHERE device_id=?", (new_expiry, new_expiry, now, device_id))
    cx.execute("UPDATE activation_codes SET status='revoked',deleted_at=? WHERE created_by_reseller_id=? AND deleted_at IS NULL", (now, reseller_id))
    for device_id in devices:
        other = cx.execute("SELECT 1 FROM activation_codes WHERE redeemed_device_id=? AND deleted_at IS NULL AND status='revoked' LIMIT 1", (device_id,)).fetchone()
        if not other:
            cx.execute("UPDATE devices SET access_disabled=0 WHERE device_id=?", (device_id,))
    record_event(cx, reseller_id, "panel_deleted", detail=f"{len(rows)} codes retirés")
