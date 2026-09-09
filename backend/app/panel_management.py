from __future__ import annotations

import hashlib
import hmac
import json
from typing import Literal

from fastapi import HTTPException
from pydantic import BaseModel, Field

from .admin_ops import delete_activation_code, delete_redeem_code, list_activation_codes, list_redeem_codes
from .db import connect, transaction
from .resellers import public_reseller_account
from .services import iso, now_ts


class FilterRequest(BaseModel):
    kind: Literal["subscription", "redeem"]
    status: Literal["issued", "redeemed", "expired", "revoked", "active"]
    query: str = Field(default="", max_length=200)
    fingerprint: str | None = Field(default=None, min_length=64, max_length=64)


def filtered_codes(body: FilterRequest, reseller_id: int | None = None, *, delete: bool = False) -> dict:
    if reseller_id is not None and body.kind != "subscription":
        raise HTTPException(403, "Action non autorisée.")
    allowed = {"issued", "redeemed", "expired", "revoked"} if body.kind == "subscription" else {"active", "expired", "revoked"}
    if body.status not in allowed:
        raise HTTPException(400, "Filtre de statut invalide.")
    # Preview and confirmation use the same calculation. Lock writers during the
    # final check and all deletions: a changed list requires fresh confirmation.
    with transaction() as cx:
        if reseller_id is not None:
            account = cx.execute(
                "SELECT id FROM reseller_accounts WHERE id=? AND deleted_at IS NULL AND status='active' AND expires_at>?",
                (reseller_id, now_ts()),
            ).fetchone()
            if not account:
                raise HTTPException(403, "Sous-panel indisponible.")
        rows = (list_activation_codes(None, reseller_id, connection=cx)
                if body.kind == "subscription" else list_redeem_codes(None, connection=cx))
        fields = ("code", "plan_id", "status", "redeemed_device_id", "source_type", "reseller_username", "code_type") if body.kind == "subscription" else ("code", "status")
        query = body.query.strip().lower()
        def matches(row):
            values = [row.get(k) for k in fields]
            if reseller_id is not None:
                plan_label = {"24h": "24 HEURES", "1w": "1 SEMAINE", "2w": "2 SEMAINES", "1m": "1 MOIS", "test_2h": "TEST 2 H"}.get(row["plan_id"], row["plan_id"])
                status_label = {"issued": "Disponible", "redeemed": "Actif / utilisé", "expired": "EXPIRÉ", "revoked": "Désactivé"}.get(row["status"], row["status"])
                values = [row["code"], row["plan_id"], plan_label, row["status"], status_label, row["code_type"]]
            return row["status"] == body.status and (not query or any(query in str(v or "").lower() for v in values))
        rows = [r for r in rows if matches(r)]
        snapshot = [{k: v for k, v in row.items() if k != "remaining_seconds"} for row in rows]
        raw = json.dumps([body.kind, body.status, query, reseller_id, snapshot], sort_keys=True).encode()
        fingerprint = hashlib.sha256(raw).hexdigest()
        if not delete:
            return {"count": len(rows), "fingerprint": fingerprint}
        if body.fingerprint is None or not hmac.compare_digest(body.fingerprint, fingerprint):
            raise HTTPException(409, "La liste a changé. Actualisez puis confirmez à nouveau ; aucun code supprimé.")
        for row in rows:
            success, _ = (delete_activation_code(row["code"], reseller_id, connection=cx)
                          if body.kind == "subscription" else delete_redeem_code(row["code"], connection=cx))
            if not success:
                raise HTTPException(409, "Suppression annulée : la liste a changé.")
    return {"success": True, "deleted": len(rows), "message": f"{len(rows)} code(s) du filtre supprimé(s)."}


def reseller_details(reseller_id: int) -> dict:
    cx = connect()
    try:
        cx.execute("BEGIN")
        account = cx.execute("SELECT * FROM reseller_accounts WHERE id=? AND deleted_at IS NULL", (reseller_id,)).fetchone()
        if not account:
            raise HTTPException(404, "Sous-panel introuvable.")
        now = now_ts()
        totals = dict(cx.execute("""
            SELECT COUNT(*) AS created,
                COALESCE(SUM(redeemed_at IS NOT NULL),0) AS used,
                COALESCE(SUM(deleted_at IS NULL AND status='issued'),0) AS unused,
                COALESCE(SUM(deleted_at IS NULL AND status='redeemed' AND applied_until>?),0) AS active,
                COALESCE(SUM(deleted_at IS NULL AND status='redeemed' AND applied_until<=?),0) AS expired,
                COALESCE(SUM(deleted_at IS NULL AND status='revoked'),0) AS disabled,
                COALESCE(SUM(deleted_at IS NOT NULL),0) AS deleted
            FROM activation_codes WHERE created_by_reseller_id=?
        """, (now, now, reseller_id)).fetchone())
        security = dict(cx.execute("""
            SELECT COALESCE(SUM(event='login_success'),0) AS login_success,
                   COALESCE(SUM(event='login_failed'),0) AS login_failed
            FROM reseller_events WHERE reseller_id=?
        """, (reseller_id,)).fetchone())
        events = [dict(r) for r in cx.execute("""
            SELECT event,occurred_at,client_ip,detail FROM reseller_events
            WHERE reseller_id=? ORDER BY occurred_at DESC,id DESC LIMIT 100
        """, (reseller_id,)).fetchall()]
        # Existing code timestamps give genuine historical activity, even before
        # the new audit journal was installed. Never invent missing login history.
        activations = [dict(r) for r in cx.execute("""
            SELECT plan_id,created_at,redeemed_at,deleted_at FROM activation_codes
            WHERE created_by_reseller_id=?
            ORDER BY MAX(created_at,COALESCE(redeemed_at,0),COALESCE(deleted_at,0)) DESC LIMIT 50
        """, (reseller_id,)).fetchall()]
        for event in events:
            event["occurred_at"] = iso(event["occurred_at"])
        for code in activations:
            for key in ("created_at", "redeemed_at", "deleted_at"):
                code[key] = iso(code[key])
        return {"account": public_reseller_account(account), "stats": totals, "security": security,
                "events": events, "recent_codes": activations}
    finally:
        cx.close()
