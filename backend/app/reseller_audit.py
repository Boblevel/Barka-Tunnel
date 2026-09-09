from __future__ import annotations

from .services import now_ts


def record_event(cx, reseller_id: int, event: str, client_ip: str | None = None, detail: str = "") -> None:
    cx.execute(
        "INSERT INTO reseller_events(reseller_id,event,occurred_at,client_ip,detail) VALUES(?,?,?,?,?)",
        (reseller_id, event, now_ts(), (client_ip or "")[:64] or None, detail[:160]),
    )
