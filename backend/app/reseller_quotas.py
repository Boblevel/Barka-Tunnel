"""Monthly issuance quotas, independent of code deletion and dashboard statistics."""
from __future__ import annotations

import calendar
from datetime import datetime, timezone


def next_month(timestamp: int) -> int:
    start = datetime.fromtimestamp(timestamp, timezone.utc)
    year, month = (start.year + 1, 1) if start.month == 12 else (start.year, start.month + 1)
    return int(start.replace(year=year, month=month,
                            day=min(start.day, calendar.monthrange(year, month)[1])).timestamp())


def current_period(cx, account, now: int):
    row = cx.execute(
        "SELECT * FROM reseller_quota_periods WHERE reseller_id=? AND starts_at<=? AND ends_at>? ORDER BY starts_at DESC LIMIT 1",
        (account['id'], now, now),
    ).fetchone()
    if row:
        return effective_period(cx, row)
    if int(account['expires_at']) <= now:
        return None
    previous = cx.execute(
        "SELECT * FROM reseller_quota_periods WHERE reseller_id=? AND ends_at<=? ORDER BY ends_at DESC LIMIT 1",
        (account['id'], now),
    ).fetchone()
    start = int(previous['ends_at'] if previous else account['created_at'])
    limit = int(previous['code_limit'] if previous else 30)
    while next_month(start) <= now:
        start = next_month(start)
    end = min(next_month(start), int(account['expires_at']))
    # Existing accounts retain their dates. Count earlier issuance in this cycle,
    # including deleted/revoked codes; tests never consume the paid allowance.
    used = cx.execute(
        "SELECT COUNT(*) FROM activation_codes WHERE created_by_reseller_id=? AND created_at>=? AND created_at<? AND plan_id NOT IN ('test_2h','test_3h','test_6h')",
        (account['id'], start, end),
    ).fetchone()[0]
    cx.execute(
        "INSERT INTO reseller_quota_periods(reseller_id,starts_at,ends_at,code_limit,used) VALUES(?,?,?,?,?)",
        (account['id'], start, end, limit, used),
    )
    return effective_period(cx, cx.execute("SELECT * FROM reseller_quota_periods WHERE reseller_id=? AND starts_at=?",
                      (account['id'], start)).fetchone())


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
            'remaining': max(0, period['code_limit'] - period['used']),
            'starts_at': period['starts_at'], 'ends_at': period['ends_at'], 'revision': period['revision']}


def effective_period(cx, row):
    result = dict(row)
    adjustment = cx.execute("SELECT * FROM reseller_quota_adjustments WHERE period_id=?", (row['id'],)).fetchone()
    result['revision'] = adjustment['revision'] if adjustment else 0
    if adjustment and adjustment['custom_limit'] is not None:
        result['code_limit'] = adjustment['custom_limit']
    return result
