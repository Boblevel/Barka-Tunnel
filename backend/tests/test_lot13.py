import hashlib
import uuid
from datetime import datetime
import pytest
from fastapi import HTTPException
from test_saspay_resellers_updates import env, client_for
from test_reseller_purchases import buyer, provider


def test_support_is_admin_managed_and_url_safe(env):
    *_,main,_=env
    with client_for(main) as c:
        assert c.get('/v1/support/config').json()['whatsapp_number']=='19852212404'
        route='/v1/admin/support/config'
        assert c.put(route,json={'whatsapp_number':'22670000000'},headers={'X-Admin-Token':'wrong'}).status_code==401
        for number in ['+22670000000','javascript:alert(1)','00000000','1','19852212404?x=1']:
            assert c.put(route,json={'whatsapp_number':number}).status_code==422
        assert c.put(route,json={'whatsapp_number':'22670000000'}).status_code==200
        assert c.get('/v1/support/config').json()['whatsapp_number']=='22670000000'


def test_admin_session_short_lived_hashed_and_revocable(env):
    db,svc,rs,_,_,main,a=env
    with client_for(main) as c:
        route=f"/v1/admin/resellers/{a['id']}/session"
        assert c.post(route,headers={'X-Admin-Token':'wrong'}).status_code==401
        result=c.post(route);assert result.status_code==200,result.text
        token=result.json()['token'];assert rs.require_reseller('Bearer '+token)['id']==a['id']
        with db.connect() as cx:
            row=cx.execute('SELECT * FROM reseller_sessions WHERE token_hash=?',(hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
            assert 0 < row['expires_at']-svc.now_ts() <= 900
            assert token not in str(dict(row))
            assert cx.execute("SELECT 1 FROM reseller_events WHERE reseller_id=? AND event='admin_login'",(a['id'],)).fetchone()
        rs.freeze_reseller(a['id']);assert c.post(route).status_code==403
        with pytest.raises(HTTPException):rs.require_reseller('Bearer '+token)
        rs.delete_reseller(a['id']);assert c.post(route).status_code==404


def test_deleted_purchase_can_be_replaced_without_reviving_old_receipt(env,provider):
    db,svc,rs,_,_,main,_=env;body=buyer();root='/v1/reseller-purchases/'
    with client_for(main) as c:
        ref=c.post(root+'start',json=body).json()['payment_reference']
        paid=c.post(root+'status',json={**body,'payment_reference':ref}).json()
        first=paid['account'];assert '-' in first['username'] and not first['username'].startswith('rev-')
        rs.delete_reseller(first['id'])
        status=c.post(root+'status',json={**body,'payment_reference':ref}).json()
        assert status['account_deleted'] and status['fulfilled'] and status['account'] is None
        assert c.post(root+'account',json=body).json()['account'] is None
        new={**body,'request_id':uuid.uuid4().hex}
        ref2=c.post(root+'start',json=new).json()['payment_reference']
        second=c.post(root+'status',json={**new,'payment_reference':ref2}).json()['account']
        assert second['id']!=first['id'] and second['username']!=first['username']
        svc.mark_payment_paid(ref)
        assert c.post(root+'account',json=body).json()['account']['id']==second['id']


def test_names_collision_and_expired_admin_session(env,monkeypatch):
    from app import reseller_purchases
    db,svc,rs,_,_,main,a=env
    monkeypatch.setattr(reseller_purchases.secrets,'choice',lambda values:values[0])
    with db.transaction() as cx:name=reseller_purchases.readable_username(cx)
    rs.create_reseller(name,datetime.fromisoformat(a['expires_at'].replace('Z','+00:00')))
    with db.transaction() as cx:
        assert reseller_purchases.readable_username(cx).startswith(name+'-')
        cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(svc.now_ts()-1,a['id']))
    with client_for(main) as c:assert c.post(f"/v1/admin/resellers/{a['id']}/session").status_code==403


def test_suggestion_name_is_persisted_and_idempotent(env):
    *_,main,_=env
    with client_for(main) as c:
        body={'device_id':'suggestion-name-device123','request_id':str(uuid.uuid4()),'sender_name':'  Awa  ','message':'Un message de test'}
        first=c.post('/v1/suggestions',json=body);assert first.status_code==200,first.text
        assert c.post('/v1/suggestions',json=body).json()['id']==first.json()['id']
        assert c.post('/v1/suggestions',json={**body,'sender_name':'Issa'}).status_code==409
        assert c.get('/v1/admin/suggestions').json()['items'][0]['sender_name']=='Awa'
