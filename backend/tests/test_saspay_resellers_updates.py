from __future__ import annotations

import importlib
import hashlib
import hmac
import json
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from test_core import load_modules


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv('SASPAY_SECRET_KEY', 'fake-key-for-tests')
    monkeypatch.setenv('SASPAY_WEBHOOK_SECRET', 'fake-webhook-for-tests')
    db, services = load_modules(tmp_path)
    from app import resellers, admin_ops, panel_management, app_updates, saspay, main
    for module in (admin_ops, resellers, panel_management, app_updates, saspay, main):
        importlib.reload(module)
    account = resellers.create_reseller('vendeur-a', datetime.now(timezone.utc) + timedelta(days=30))
    return db, services, resellers, admin_ops, panel_management, main, account


def client_for(main):
    return TestClient(main.app, headers={'X-Admin-Token': 'test-admin-token'})


def test_credentials_reconsult_reset_old_and_revoke_sessions(env):
    db, _, rs, _, _, main, a = env
    with client_for(main) as client:
        r = client.get(f"/v1/admin/resellers/{a['id']}/credentials")
        assert r.status_code == 200 and r.json()['password'] == a['password']
        assert r.headers['cache-control'] == 'no-store'
        assert 'password' not in client.get('/v1/admin/resellers').json()[0]
        assert client.get(f"/v1/admin/resellers/{a['id']}/credentials", headers={'X-Admin-Token':'wrong'}).status_code == 401
        token = rs.login_reseller(a['username'], a['password'])['token']
        with db.transaction() as cx:
            cx.execute('UPDATE reseller_accounts SET password_nonce=NULL WHERE id=?', (a['id'],))
        old = client.get(f"/v1/admin/resellers/{a['id']}/credentials").json()
        assert old['password'] is None and old['password_reset_required']
        assert rs.login_reseller(a['username'], a['password'])
        route = f"/v1/admin/resellers/{a['id']}/password"
        assert client.post(route, json={'confirmation':'non'}).status_code == 422
        assert client.post(route, json={}).status_code == 422
        fresh = client.post(route, json={'confirmation':'oui'}).json()
        assert fresh['password'] != a['password']
        assert rs.reseller_credentials(a['id'])['password'] == fresh['password']
        with pytest.raises(HTTPException): rs.require_reseller('Bearer '+token)
        with pytest.raises(ValueError): rs.login_reseller(a['username'], a['password'])
        assert rs.login_reseller(a['username'], fresh['password'])
        with db.connect() as cx:
            assert fresh['password'] not in str(dict(cx.execute('SELECT * FROM reseller_accounts WHERE id=?',(a['id'],)).fetchone()))


def test_expiry_blocks_issuance_and_renewal_restores_without_changing_codes(env):
    db, services, rs, _, _, main, a = env
    code = rs.generate_reseller_subscription(a['id'], '24h')
    token = rs.login_reseller(a['username'], a['password'])['token']
    with db.transaction() as cx:
        cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?', (services.now_ts()-100, a['id']))
    with pytest.raises(PermissionError): rs.generate_reseller_subscription(a['id'], '24h')
    with pytest.raises(PermissionError): rs.generate_reseller_test(a['id'])
    with pytest.raises(HTTPException): rs.require_reseller('Bearer '+token)
    assert not services.redeem_activation_code('unused-after-expiry-123', code)[0]
    with client_for(main) as client:
        route = f"/v1/admin/resellers/{a['id']}/renew"
        assert client.post(route,json={'confirmation':'non'}).status_code==422
        renewed=client.post(route,json={'confirmation':'oui'})
        assert renewed.status_code==200 and renewed.json()['status']=='active'
    assert services.redeem_activation_code('unused-after-expiry-123', code)[0]
    assert rs.generate_reseller_test(a['id'])
    rs.freeze_reseller(a['id']);rs.renew_reseller_month(a['id'])
    with pytest.raises(PermissionError):rs.generate_reseller_test(a['id'])


def test_calendar_month_and_existing_remaining_days_preserved(env):
    db, services, rs, _, _, _, a = env
    future=datetime(datetime.now(timezone.utc).year+1,1,31,12,30,tzinfo=timezone.utc)
    rs.update_reseller_expiry(a['id'],future)
    renewed=rs.renew_reseller_month(a['id'])
    end=datetime.fromisoformat(renewed['expires_at'].replace('Z','+00:00'))
    assert end.month==2 and end.day in (28,29) and end.hour==12 and end.minute==30
    rs.delete_reseller(a['id'])
    with pytest.raises(ValueError):rs.renew_reseller_month(a['id'])
    with pytest.raises(ValueError):rs.reseller_credentials(a['id'])


def test_admin_and_reseller_lists_and_bulk_delete_are_separate(env):
    db, services, rs, ops, pm, main, a = env
    own = services.issue_activation_code('MANUAL:OWN','24h')
    other = rs.generate_reseller_subscription(a['id'],'24h')
    with client_for(main) as client:
        assert [c['code'] for c in client.get('/v1/admin/codes').json()]==[own]
        assert [c['code'] for c in client.get(f"/v1/admin/resellers/{a['id']}/codes").json()]==[other]
    body=pm.FilterRequest(kind='subscription',status='issued')
    preview=pm.filtered_codes(body)
    assert preview['count']==1
    assert pm.filtered_codes(body.model_copy(update={'fingerprint':preview['fingerprint']}),delete=True)['deleted']==1
    assert rs.list_reseller_codes(a['id'])[0]['code']==other
    assert services.redeem_activation_code('device-other-owner-123',other)[0]


@pytest.mark.parametrize('kind',['manual','payment','reseller','test','redeem'])
def test_unused_codes_have_no_age_expiration(env,kind):
    db, services, rs, ops, _, _, a=env
    if kind=='manual': code=services.issue_activation_code('MANUAL:OLD','24h')
    elif kind=='payment': code=services.issue_activation_code('PAYMENT:OLD','24h')
    elif kind=='reseller':code=rs.generate_reseller_subscription(a['id'],'24h')
    elif kind=='test':code=rs.generate_reseller_test(a['id'])
    else:code=services.issue_redeem_code('REDEEM:OLD',7200,5)
    with db.transaction() as cx:
        table='redeem_codes' if kind=='redeem' else 'activation_codes'
        cx.execute(f'UPDATE {table} SET created_at=1')
    ok,_,access=services.redeem_activation_code('device-old-unused-123',code)
    assert ok and access['remaining_seconds'] >= (7195 if kind in ('test','redeem') else 86395)


def test_redeem_unused_slots_remain_available_after_first_usage_expired(env):
    db, services, _, ops, _, _, _=env
    code=services.issue_redeem_code('REDEEM:SLOTS',7200,5)
    assert services.redeem_activation_code('first-device-12345',code)[0]
    with db.transaction() as cx:cx.execute('UPDATE redeem_usages SET applied_until=1')
    assert ops.list_redeem_codes()[0]['status']=='active'
    assert services.redeem_activation_code('second-device-12345',code)[0]


@pytest.mark.parametrize('mandatory',[False,True])
def test_update_visibility_old_current_new_and_no_cache(env,mandatory):
    _,_,_,_,_,main,_=env
    with client_for(main) as client:
        assert client.post('/v1/admin/app-update',json={'enabled':True,'mandatory':mandatory,'latest_version_code':106,'latest_version_name':'2.0.3.6','apk_url':'https://apkpure.com/p/com.barkatunnel.app','message':'Nouvelle version'}).status_code==200
        for version,expected in [(1,True),(68,True),(105,True),(106,False),(107,False)]:
            r=client.get('/v1/app/update',params={'version_code':version})
            assert r.status_code==200 and 'no-store' in r.headers['cache-control']
            assert r.json()['update_available']==expected
            assert r.json()['enabled']==expected
            assert r.json()['force_update']==(expected and mandatory)


def test_saspay_pending_paid_code_once_and_device_ownership(env,monkeypatch):
    db, services, _, _, _, main, _=env
    async def create(**kw):
        assert kw['amount']==300 and kw['currency']=='XOF'
        return {'id':'session-test-123','checkout_url':'https://checkout.saspay.me/test'}
    async def paid(_):return {'status':'PAID'}
    monkeypatch.setattr(main,'create_payment',create)
    with client_for(main) as client:
        start=client.post('/v1/payments/start',json={'device_id':'device-payment-123','plan_id':'24h'})
        assert start.status_code==200 and start.json()['success']
        ref=start.json()['payment_reference']
        pending=client.post('/v1/payments/status',json={'device_id':'device-payment-123','payment_reference':ref,'sync_provider':False}).json()
        assert pending['status']=='pending' and pending['activation_code'] is None
        assert client.post('/v1/payments/status',json={'device_id':'wrong-device-123','payment_reference':ref,'sync_provider':False}).status_code==404
        monkeypatch.setattr(main,'get_payment',paid)
        body={'device_id':'device-payment-123','payment_reference':ref,'sync_provider':True}
        one=client.post('/v1/payments/status',json=body).json()
        two=client.post('/v1/payments/status',json=body).json()
        assert one['status']=='paid' and one['activation_code'].startswith('BARKA-')
        assert one['activation_code']==two['activation_code']
        with db.connect() as cx:assert cx.execute('SELECT COUNT(*) FROM activation_codes').fetchone()[0]==1
        assert client.post('/v1/webhooks/saspay',json={}).status_code==401


def test_saspay_webhook_signature_idempotence(env,monkeypatch):
    _,services,_,_,_,main,_=env
    body=json.dumps({'event':'transaction.success','data':{'id':'event-123'}}).encode()
    ts=str(services.now_ts())
    signature=hmac.new(b'fake-webhook-for-tests',ts.encode()+b'.'+body,hashlib.sha256).hexdigest()
    with client_for(main) as client:
        headers={'X-Webhook-Signature':signature,'X-Webhook-Timestamp':ts,'X-Webhook-Event':'transaction.success'}
        assert client.post('/v1/webhooks/saspay',content=body+b' ',headers=headers).status_code==401
        first=client.post('/v1/webhooks/saspay',content=body,headers=headers)
        assert first.status_code==200 and not first.json().get('duplicate')
        assert client.post('/v1/webhooks/saspay',content=body,headers=headers).json()['duplicate']
