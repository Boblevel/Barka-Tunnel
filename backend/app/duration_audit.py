"""Read-only audit of long subscription timelines; never changes paid credit."""
from datetime import datetime, timezone
import json
import sqlite3
import time
from .config import settings


def audit():
    cx = sqlite3.connect('file:' + settings.database_path + '?mode=ro', uri=True)
    cx.row_factory = sqlite3.Row
    try:
        now = int(time.time())
        rows = cx.execute('''SELECT a.* FROM activation_codes a
            WHERE a.deleted_at IS NULL AND a.status='redeemed' AND a.plan_id!='unlimited'
              AND (a.applied_until-a.applied_from>a.duration_seconds
                   OR a.applied_from-a.redeemed_at>90*86400)
            ORDER BY a.applied_until DESC LIMIT 30''').fetchall()
        result = []
        for row in rows:
            device = row['redeemed_device_id']
            def iso(value):
                return datetime.fromtimestamp(value,timezone.utc).isoformat() if value is not None else None
            total = cx.execute('''SELECT COUNT(*),COALESCE(SUM(duration_seconds),0)
                FROM activation_codes WHERE redeemed_device_id=? AND status='redeemed'
                AND deleted_at IS NULL AND plan_id!='unlimited' ''',(device,)).fetchone()
            shared = cx.execute('''SELECT COUNT(*),COALESCE(SUM(r.duration_seconds),0)
                FROM redeem_usages u JOIN redeem_codes r ON r.id=u.redeem_code_id
                WHERE u.device_id=? AND r.status='active' AND r.deleted_at IS NULL''',(device,)).fetchone()
            result.append(dict(code_id=row['id'],plan=row['plan_id'],
                created_at=iso(row['created_at']),redeemed_at=iso(row['redeemed_at']),
                applied_from=iso(row['applied_from']),applied_until=iso(row['applied_until']),
                duration_seconds=row['duration_seconds'],
                waiting_seconds=max(0,(row['applied_from'] or 0)-(row['redeemed_at'] or 0)),
                interval_seconds=(row['applied_until'] or 0)-(row['applied_from'] or 0),
                paid_grants_count=total[0],paid_grants_seconds=total[1],
                shared_grants_count=shared[0],shared_grants_seconds=shared[1]))
        return {'read_only':True,'checked_at':now,'long_timelines':result,
                'note':'Une attente peut venir de codes cumulés ; une durée étendue peut venir du gel revendeur. Ne pas réduire automatiquement.'}
    finally:
        cx.close()


if __name__ == '__main__':
    print(json.dumps(audit(),ensure_ascii=False,indent=2))
