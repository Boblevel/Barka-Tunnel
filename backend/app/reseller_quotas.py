"""Reusable slots aligned with account expiry; renewal resets usage atomically."""
from __future__ import annotations

import calendar
from datetime import datetime, timezone


def next_month(timestamp: int) -> int:
    start = datetime.fromtimestamp(timestamp, timezone.utc)
    year, month = (start.year + 1, 1) if start.month == 12 else (start.year, start.month + 1)
    return int(start.replace(year=year, month=month,
                            day=min(start.day, calendar.monthrange(year, month)[1])).timestamp())


def current_period(cx, account, now: int):
    if int(account['expires_at']) <= now:
        return None
    row = cx.execute("SELECT * FROM reseller_quota_periods WHERE reseller_id=? ORDER BY starts_at DESC LIMIT 1", (account['id'],)).fetchone()
    if not row:
        cx.execute("INSERT INTO reseller_quota_periods(reseller_id,starts_at,ends_at,code_limit,used) VALUES(?,?,?,?,0)",
                   (account['id'], now, account['expires_at'], 30))
        row = cx.execute("SELECT * FROM reseller_quota_periods WHERE reseller_id=? ORDER BY id DESC LIMIT 1", (account['id'],)).fetchone()
    if row['ends_at'] != account['expires_at']:
        # Repair old extensions using the recorded account change time. Codes
        # generated after that extension continue consuming slots.
        if account['expires_at'] > row['ends_at'] and account['updated_at'] > account['created_at']:
            reset_slots(cx, account['id'], now, before=account['updated_at'])
        cx.execute("UPDATE reseller_quota_periods SET ends_at=? WHERE id=?", (account['expires_at'],row['id']))
    row = cx.execute("SELECT * FROM reseller_quota_periods WHERE id=?",(row['id'],)).fetchone()
    period = effective_period(cx, row)
    baseline = cx.execute("SELECT value FROM admin_state WHERE key=?", (f"quota_reset_{account['id']}",)).fetchone()
    baseline_id = int(baseline[0]) if baseline else 0
    used = cx.execute("""SELECT COUNT(*) FROM activation_codes WHERE created_by_reseller_id=? AND id>?
        AND deleted_at IS NULL AND plan_id NOT IN ('test_2h','test_3h','test_6h')
        AND (status='issued' OR (status IN ('redeemed','revoked') AND (applied_until IS NULL OR applied_until>?)))""",
        (account['id'],baseline_id,now)).fetchone()[0]
    # A used code retains its reservation until its original expiry.
    # Deleting an unused code releases its slot immediately.
    used += cx.execute("""SELECT COUNT(*) FROM activation_codes a JOIN admin_state s
        ON s.key='quota_deleted_' || a.id WHERE a.created_by_reseller_id=? AND a.id>?
        AND a.deleted_at IS NOT NULL
        AND (CAST(s.value AS INTEGER)>? OR (CAST(s.value AS INTEGER)=0 AND a.redeemed_at IS NOT NULL))""",
        (account['id'],baseline_id,now)).fetchone()[0]
    used = settle_pre_reset_expirations(cx, account['id'], baseline_id, now, used)
    if used != period['used']:
        cx.execute("UPDATE reseller_quota_periods SET used=? WHERE id=?",(used,row['id']))
        bump_revision(cx,row['id'])
    result = effective_period(cx, cx.execute("SELECT * FROM reseller_quota_periods WHERE id=?", (row['id'],)).fetchone())
    result['remaining'] = max(0, result['code_limit'] - used)
    return result


def settle_pre_reset_expirations(cx, reseller_id, baseline_id, now, occupied):
    key = f"quota_reset_expirations_{reseller_id}"
    state = cx.execute("SELECT value,updated_at FROM admin_state WHERE key=?", (key,)).fetchone()
    if state is None:
        # Existing manual resets start tracking future expirations at first use.
        if cx.execute("DELETE FROM admin_state WHERE key=?", (f"quota_manual_cap_{reseller_id}",)).rowcount:
            cx.execute("INSERT INTO admin_state(key,value,updated_at) VALUES(?,'0',?)", (key, now))
        return occupied
    recorded, cutoff = int(state['value']), state['updated_at']
    expired = cx.execute("""INSERT OR IGNORE INTO admin_state(key,value,updated_at)
        SELECT 'quota_expiry_counted_' || a.id, '1', ?
        FROM activation_codes a LEFT JOIN admin_state d ON d.key='quota_deleted_' || a.id
        WHERE a.created_by_reseller_id=? AND a.id<=?
          AND a.plan_id NOT IN ('test_2h','test_3h','test_6h')
          AND (CASE WHEN a.deleted_at IS NULL AND a.status IN ('redeemed','revoked')
                    THEN a.applied_until ELSE CAST(d.value AS INTEGER) END) BETWEEN ? AND ?""",
        (now, reseller_id, baseline_id, cutoff + 1, now)).rowcount
    # Generation settles expirations before adding a slot. Saturation consumes
    # expirations at zero without carrying them over to subsequent generations.
    released = min(occupied, recorded + expired)
    if released != recorded:
        cx.execute("UPDATE admin_state SET value=? WHERE key=?", (str(released), key))
    return occupied - released


def bump_revision(cx, period_id):
    cx.execute("INSERT OR IGNORE INTO reseller_quota_adjustments(period_id) VALUES(?)",(period_id,))
    cx.execute("UPDATE reseller_quota_adjustments SET revision=revision+1 WHERE period_id=?",(period_id,))


def reset_slots(cx, reseller_id, now, before=None, *, manual=False):
    cutoff = now if before is None else before
    last_id = cx.execute("SELECT COALESCE(MAX(id),0) FROM activation_codes WHERE created_by_reseller_id=? AND created_at<=?",(reseller_id,cutoff)).fetchone()[0]
    cx.execute("""INSERT INTO admin_state(key,value,updated_at) VALUES(?,?,?) ON CONFLICT(key)
        DO UPDATE SET value=CAST(MAX(CAST(admin_state.value AS INTEGER),CAST(excluded.value AS INTEGER)) AS TEXT),updated_at=excluded.updated_at""",
        (f"quota_reset_{reseller_id}",str(last_id),now))
    cx.execute("DELETE FROM admin_state WHERE key=?", (f"quota_manual_cap_{reseller_id}",))
    key = f"quota_reset_expirations_{reseller_id}"
    if manual:
        cx.execute("INSERT OR REPLACE INTO admin_state(key,value,updated_at) VALUES(?,'0',?)", (key, now))
    else:
        cx.execute("DELETE FROM admin_state WHERE key=?", (key,))



def renewed(cx, reseller_id, now, limit=None):
    account=cx.execute("SELECT * FROM reseller_accounts WHERE id=?",(reseller_id,)).fetchone()
    period=current_period(cx,account,now)
    if period is None:
        return
    reset_slots(cx,reseller_id,now)
    cx.execute("UPDATE reseller_quota_periods SET used=0,ends_at=? WHERE id=?",(account['expires_at'],period['id']))
    if limit is not None:
        cx.execute("UPDATE reseller_quota_periods SET code_limit=? WHERE id=?",(limit,period['id']))
        cx.execute("UPDATE reseller_quota_adjustments SET custom_limit=NULL WHERE period_id=?",(period['id'],))
    bump_revision(cx,period['id'])


def grant_month(cx, reseller_id: int, start: int, end: int, limit: int):
    cx.execute(
        "INSERT INTO reseller_quota_periods(reseller_id,starts_at,ends_at,code_limit,used) VALUES(?,?,?,?,0)",
        (reseller_id, start, end, limit),
    )


def summary(cx, account, now: int) -> dict:
    period = current_period(cx, account, now)
    if period is None:
        return {'limit': 0, 'used': 0, 'remaining': 0, 'starts_at': None, 'ends_at': None}
    return {'limit': period['code_limit'], 'used': period['used'],
            'remaining': period['remaining'],
            'starts_at': period['starts_at'], 'ends_at': period['ends_at'], 'revision': period['revision']}


def effective_period(cx, row):
    result = dict(row)
    adjustment = cx.execute("SELECT * FROM reseller_quota_adjustments WHERE period_id=?", (row['id'],)).fetchone()
    result['revision'] = adjustment['revision'] if adjustment else 0
    if adjustment and adjustment['custom_limit'] is not None:
        result['code_limit'] = adjustment['custom_limit']
    return result
