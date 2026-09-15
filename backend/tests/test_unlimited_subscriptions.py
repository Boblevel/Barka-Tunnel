from datetime import datetime, timedelta, timezone
from fastapi.testclient import TestClient
from test_custom_subscriptions import env


def test_unlimited_is_admin_only_and_remains_single_device(tmp_path, monkeypatch):
    db, services, rs, ops, _, main = env(tmp_path)
    with TestClient(main.app, headers={'X-Admin-Token':'test-admin-token'}) as client:
        body={'plan_id':'unlimited','count':1}
        assert client.post('/v1/admin/codes',json=body,headers={'X-Admin-Token':''}).status_code==401
        reply=client.post('/v1/admin/codes',json=body)
        assert reply.status_code==200,reply.text
        code=reply.json()['codes'][0]
        device='unlimited-device-12345'
        result=client.post('/v1/activation/redeem',json={'device_id':device,'code':code}).json()
        assert result['success'] and result['access']['unlimited'] and result['access']['expires_at'] is None
        assert services.redeem_activation_code('other-device-12345',code)[0] is False
        assert services.redeem_activation_code(device,code)[0]
        assert ops.admin_stats_extended()['active_subscriptions']==1
        now=services.now_ts()
        monkeypatch.setattr(services,'now_ts',lambda:now+100*365*86400)
        access=services.access_state(device)
        assert access['allowed'] and access['unlimited'] and access['expires_at'] is None
        assert client.get('/v1/admin/codes').json()[0]['status']=='redeemed'
        assert ops.revoke_activation_code(code)[0]
        assert not services.access_state(device)['allowed']
        assert ops.reactivate_activation_code(code)[0]
        assert services.access_state(device)['unlimited']
        assert ops.delete_activation_code(code)[0]
        assert not services.access_state(device)['allowed']
        assert client.post('/v1/payments/start',json={'device_id':device,'plan_id':'unlimited'}).status_code==422
        assert all(p['id']!='unlimited' for p in client.get('/v1/plans').json())


def test_removing_unlimited_preserves_existing_finite_credit(tmp_path):
    db, services, rs, ops, _, main = env(tmp_path)
    device='mixed-unlimited-device'
    finite=services.issue_activation_code('MANUAL:finite','24h')
    expiry=services.redeem_activation_code(device,finite)[2]['expires_at']
    unlimited=services.issue_activation_code('MANUAL:unlimited','unlimited')
    assert services.redeem_activation_code(device,unlimited)[2]['unlimited']
    assert ops.delete_activation_code(unlimited)[0]
    access=services.access_state(device)
    assert access['allowed'] and not access.get('unlimited',False) and access['expires_at']==expiry
    reseller=rs.create_reseller('finite-only',datetime.now(timezone.utc)+timedelta(days=30))
    with TestClient(main.app) as client:
        token=rs.login_reseller(reseller['username'],reseller['password'])['token']
        response=client.post('/v1/reseller/codes/subscription',json={'plan_id':'unlimited'},headers={'Authorization':'Bearer '+token})
        assert response.status_code==422
