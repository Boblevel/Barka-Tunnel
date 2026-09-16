"""Achats de sous-panels : droits délivrés atomiquement après confirmation SasPay."""
from __future__ import annotations
import calendar
import hashlib
import secrets
import uuid
from datetime import datetime, timezone
from typing import Literal
from fastapi import APIRouter, HTTPException, Response
from pydantic import Field
from .models import DeviceRequest
from .db import connect, transaction
from . import services, saspay, resellers
from .reseller_audit import record_event

router = APIRouter(prefix='/v1/reseller-purchases')

class OwnerRequest(DeviceRequest):
    owner_key: str = Field(min_length=40, max_length=128, pattern=r'^[a-zA-Z0-9_-]+$')

class StartRequest(OwnerRequest):
    months: Literal[1, 2]
    request_id: str = Field(min_length=32, max_length=64, pattern=r'^[a-zA-Z0-9-]+$')

class StatusRequest(OwnerRequest):
    payment_reference: str = Field(min_length=8, max_length=100)

class RestoreRequest(OwnerRequest):
    username: str = Field(min_length=1, max_length=200)
    password: str = Field(min_length=12, max_length=200)

def key_hash(key):
    return hashlib.sha256(key.encode()).hexdigest()

def add_months(timestamp, months):
    start = datetime.fromtimestamp(timestamp, timezone.utc)
    index = start.year * 12 + start.month - 1 + months
    year, month = index // 12, index % 12 + 1
    return int(start.replace(year=year, month=month, day=min(start.day, calendar.monthrange(year, month)[1])).timestamp())

def fulfill(cx, payment):
    """Called inside the payment transaction; duplicate callbacks never add time twice."""
    order = cx.execute('SELECT * FROM reseller_purchases WHERE reference=?', (payment['reference'],)).fetchone()
    if not order:
        raise ValueError('Commande revendeur introuvable')
    if order['fulfilled_at'] is not None:
        return
    now = services.now_ts()
    target = order['reseller_id']
    if target is None:
        bound = cx.execute('SELECT reseller_id FROM reseller_purchase_owners WHERE owner_hash=?', (order['owner_hash'],)).fetchone()
        target = bound['reseller_id'] if bound else None
    if target is not None:
        account = cx.execute('SELECT * FROM reseller_accounts WHERE id=?', (target,)).fetchone()
        if not account or account['deleted_at'] is not None:
            # Preserve the paid receipt for support; never recreate an admin-deleted panel.
            cx.execute("UPDATE reseller_purchases SET fulfillment_error='Sous-panel supprimé. Contactez le support avec la référence du paiement.' WHERE reference=?", (payment['reference'],))
            return
        expiry = add_months(max(now, account['expires_at']), order['months'])
        cx.execute('UPDATE reseller_accounts SET expires_at=?, updated_at=? WHERE id=?', (expiry, now, target))
        resellers._revoke_sessions(cx, target, now)
        record_event(cx, target, 'renewed', detail=services.iso(expiry))
    else:
        nonce = secrets.token_hex(32)
        password = resellers._recoverable_password(nonce)
        salt = secrets.token_bytes(16)
        username = 'rev-' + uuid.uuid4().hex[:20]
        target = cx.execute('''INSERT INTO reseller_accounts(username,password_hash,password_salt,status,expires_at,created_at,updated_at,password_nonce)
                              VALUES(?,?,?,'active',?,?,?,?)''',
                            (username, resellers._encode(resellers._password_digest(password,salt)), resellers._encode(salt), add_months(now,order['months']), now,now,nonce)).lastrowid
        record_event(cx,target,'created',detail='Achat dans l’application')
    cx.execute('INSERT OR IGNORE INTO reseller_purchase_owners(owner_hash,reseller_id) VALUES(?,?)',(order['owner_hash'],target))
    cx.execute('UPDATE reseller_purchases SET reseller_id=?,fulfilled_at=?,fulfillment_error=NULL WHERE reference=?',(target,now,payment['reference']))

def account_for_owner(owner):
    cx=connect()
    try:
        row=cx.execute('SELECT reseller_id FROM reseller_purchase_owners WHERE owner_hash=?',(owner,)).fetchone()
    finally:cx.close()
    if not row:return None
    try:return resellers.reseller_credentials(row['reseller_id'])
    except ValueError:return None

@router.post('/account')
def account(body: OwnerRequest, response: Response):
    response.headers['Cache-Control']='no-store'
    return {'account':account_for_owner(key_hash(body.owner_key))}

@router.post('/restore')
def restore(body: RestoreRequest, response: Response):
    response.headers['Cache-Control']='no-store'
    with transaction() as cx:
        row=cx.execute('SELECT * FROM reseller_accounts WHERE lower(username)=? AND deleted_at IS NULL',(body.username.strip().lower(),)).fetchone()
        valid = resellers._verify_password(body.password,row) if row else False
        if not row:
            resellers._password_digest(body.password,b'\0'*16)
        if not valid:raise HTTPException(401,'Identifiants incorrects.')
        owner=key_hash(body.owner_key)
        current=cx.execute('SELECT reseller_id FROM reseller_purchase_owners WHERE owner_hash=?',(owner,)).fetchone()
        if current and current['reseller_id']!=row['id']:raise HTTPException(409,'Cet appareil est déjà associé à un autre sous-panel.')
        pending=cx.execute("SELECT 1 FROM reseller_purchases r JOIN payments p ON p.reference=r.reference WHERE r.owner_hash=? AND p.status IN ('creating','pending')",(owner,)).fetchone()
        if pending:raise HTTPException(409,'Terminez le paiement en cours avant de récupérer un autre compte.')
        cx.execute('INSERT OR IGNORE INTO reseller_purchase_owners VALUES(?,?)',(owner,row['id']))
    return {'account':account_for_owner(owner)}

@router.post('/start')
async def start(body: StartRequest, response: Response):
    response.headers['Cache-Control']='no-store'
    services.ensure_device(body.device_id)
    owner=key_hash(body.owner_key)
    created=False
    with transaction() as cx:
        existing=cx.execute('''SELECT p.* FROM reseller_purchases r JOIN payments p ON p.reference=r.reference
                               WHERE r.owner_hash=? AND r.request_id=?''',(owner,body.request_id)).fetchone()
        if existing:
            reference=existing['reference']
        else:
            pending=cx.execute("SELECT 1 FROM reseller_purchases r JOIN payments p ON p.reference=r.reference WHERE r.owner_hash=? AND p.status IN ('creating','pending')",(owner,)).fetchone()
            if pending:raise HTTPException(409,'Un paiement est déjà en cours. Vérifiez sa confirmation.')
            bound=cx.execute('SELECT a.* FROM reseller_purchase_owners o JOIN reseller_accounts a ON a.id=o.reseller_id WHERE o.owner_hash=?',(owner,)).fetchone()
            if bound and (bound['deleted_at'] is not None or bound['status']!='active'):
                raise HTTPException(403,'Sous-panel désactivé par l’administration. Contactez le support.')
            now=services.now_ts();reference='BTR-'+uuid.uuid4().hex
            cx.execute("INSERT INTO payments(reference,device_id,plan_id,amount,currency,status,created_at,updated_at) VALUES(?,?,?,?,'XOF','creating',?,?)",(reference,body.device_id,'reseller_'+str(body.months)+'m',5000*body.months,now,now))
            cx.execute('INSERT INTO reseller_purchases(reference,owner_hash,request_id,months,reseller_id) VALUES(?,?,?,?,?)',(reference,owner,body.request_id,body.months,bound['id'] if bound else None))
            created=True
    if created:
        try:
            provider=await saspay.create_payment(amount=5000*body.months,currency='XOF',description=f'Barka Tunnel — Sous-panel {body.months} mois',external_reference=reference)
            from urllib.parse import urlsplit
            url=str(provider['checkout_url']); parsed=urlsplit(url)
            if parsed.scheme!='https' or not parsed.hostname:raise saspay.SasPayError('Lien de paiement invalide')
            services.update_payment_created(reference,str(provider['id']),url)
        except saspay.SasPayError:
            # No checkout link has been delivered. Keep the receipt, report failure,
            # and require a new explicit user action instead of retrying a purchase.
            services.mark_payment_error(reference, 'Création du paiement non confirmée.')
            return {'payment_reference':reference,'status':'error','checkout_url':None,
                    'message':'Le paiement n’a pas pu être préparé. Connectez-vous à un réseau, puis réessayez.'}
    return payment_result(reference,owner)

def payment_result(reference,owner):
    cx=connect()
    try:
        row=cx.execute('''SELECT p.*, r.reseller_id,r.fulfilled_at,r.fulfillment_error FROM payments p JOIN reseller_purchases r ON r.reference=p.reference
                           WHERE p.reference=? AND r.owner_hash=?''',(reference,owner)).fetchone()
    finally:cx.close()
    if not row:raise HTTPException(404,'Paiement introuvable.')
    credentials=None
    if row['status']=='paid' and row['fulfilled_at'] is not None:
        try:credentials=resellers.reseller_credentials(row['reseller_id'])
        except ValueError:pass
    return {'payment_reference':reference,'status':row['status'],'checkout_url':row['checkout_url'],'amount':row['amount'],'currency':'XOF',
            'account':credentials,'message':row['fulfillment_error'] or {
                'paid':'Paiement confirmé.', 'pending':'Paiement en attente de confirmation.',
                'creating':'Préparation du paiement en cours.', 'failed':'Paiement annulé ou expiré.',
                'error':'Le paiement n’a pas pu être préparé. Connectez-vous à un réseau, puis réessayez.'
            }.get(row['status'],'Vérifiez votre paiement.')}

@router.post('/status')
async def status(body: StatusRequest,response: Response):
    response.headers['Cache-Control']='no-store'
    owner=key_hash(body.owner_key)
    result=payment_result(body.payment_reference,owner)
    if result['status']=='pending':
        cx=connect()
        try:row=cx.execute('SELECT provider_payment_id FROM payments WHERE reference=?',(body.payment_reference,)).fetchone()
        finally:cx.close()
        try:
            remote=await saspay.get_payment(row['provider_payment_id'])
            state=str(remote.get('status','')).upper()
            if state=='PAID':services.mark_payment_paid(body.payment_reference)
            elif state in {'CANCELLED','EXPIRED'}:services.mark_payment_failed(body.payment_reference)
        except saspay.SasPayError:pass
    return payment_result(body.payment_reference,owner)


@router.post('/cancel')
async def cancel(body: StatusRequest, response: Response):
    """On checkout return, cancel only after provider confirmation, never by UI guess."""
    from .reseller_checkout import cancel_checkout
    response.headers['Cache-Control'] = 'no-store'
    owner = key_hash(body.owner_key)
    result = payment_result(body.payment_reference, owner)
    if result['status'] != 'pending':
        return result
    cx = connect()
    try:
        row = cx.execute('SELECT provider_payment_id FROM payments WHERE reference=?',
                         (body.payment_reference,)).fetchone()
    finally:
        cx.close()
    try:
        remote = await saspay.get_payment(row['provider_payment_id'])
        state = str(remote.get('status', '')).upper()
        if state == 'PENDING':
            remote = await cancel_checkout(row['provider_payment_id'])
            state = str(remote.get('status', '')).upper()
        if state == 'PAID':
            services.mark_payment_paid(body.payment_reference)
        elif state in {'CANCELLED', 'EXPIRED'}:
            # The conditional update cannot overwrite a simultaneous paid webhook.
            services.mark_payment_failed(body.payment_reference)
        else:
            raise saspay.SasPayError('Statut non confirmé')
    except saspay.SasPayError:
        result = payment_result(body.payment_reference, owner)
        if result['status'] == 'paid':
            return result
        raise HTTPException(503, 'Connectez-vous à un réseau, puis réessayez.')
    return payment_result(body.payment_reference, owner)
