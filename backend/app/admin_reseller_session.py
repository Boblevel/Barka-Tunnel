"""Audited short-lived reseller session, authenticated as administrator first."""
import hashlib
import secrets
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from .db import transaction
from .security import require_admin
from .services import now_ts, iso
from .reseller_audit import record_event
router = APIRouter()
@router.post('/v1/admin/resellers/{reseller_id}/session', dependencies=[Depends(require_admin)])
def open_session(reseller_id: int, request: Request, response: Response):
    now = now_ts(); token = secrets.token_urlsafe(32)
    with transaction() as cx:
        row = cx.execute('SELECT * FROM reseller_accounts WHERE id=? AND deleted_at IS NULL', (reseller_id,)).fetchone()
        if not row: raise HTTPException(404, 'Sous-panel introuvable.')
        if row['status'] != 'active' or row['expires_at'] <= now:
            raise HTTPException(403, 'Réactivez ou prolongez ce compte avant de l’ouvrir.')
        expiry = min(row['expires_at'], now + 900)
        cx.execute('INSERT INTO reseller_sessions(reseller_id,token_hash,created_at,expires_at) VALUES(?,?,?,?)', (reseller_id,hashlib.sha256(token.encode()).hexdigest(),now,expiry))
        record_event(cx,reseller_id,'admin_login',request.client.host if request.client else None,detail='Session administrateur limitée à 15 minutes')
    response.headers['Cache-Control'] = 'no-store'
    return {'token':token,'session_expires_at':iso(expiry)}
