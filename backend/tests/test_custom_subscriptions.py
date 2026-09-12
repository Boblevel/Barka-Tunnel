from datetime import datetime, timedelta, timezone
import importlib

import pytest
from fastapi.testclient import TestClient
from test_core import load_modules


def env(tmp_path):
    db, services = load_modules(tmp_path)
    from app import custom_subscriptions, resellers, admin_ops, panel_management, main
    for module in (custom_subscriptions, admin_ops, resellers, panel_management, main):
        importlib.reload(module)
    return db, services, resellers, admin_ops, panel_management, main


def test_custom_code_uses_same_lifetime_and_single_device_rules(tmp_path):
    db, services, rs, ops, pm, main = env(tmp_path)
    with TestClient(main.app, headers={'X-Admin-Token':'test-admin-token'}) as client:
        response=client.post('/v1/admin/codes/custom',json={'days':9})
        assert response.status_code==200
        code=response.json()['codes'][0]
        assert response.json()['plan_id']=='custom_9d'
        with db.transaction() as cx:
            cx.execute('UPDATE activation_codes SET created_at=1')
        listed=client.get('/v1/admin/codes').json()[0]
        assert listed['status']=='issued' and listed['expires_at'] is None
        assert listed['remaining_seconds']==9*86400
        ok,_,access=services.redeem_activation_code('custom-device-12345',code)
        assert ok and 9*86400-3<=access['remaining_seconds']<=9*86400
        assert services.redeem_activation_code('second-device-12345',code)[0] is False
        assert services.redeem_activation_code('custom-device-12345',code)[2]['expires_at']==access['expires_at']
        assert client.post('/v1/admin/codes/revoke',json={'code':code}).json()['success']
        assert not services.access_state('custom-device-12345')['allowed']
        assert client.post('/v1/admin/codes/reactivate',json={'code':code}).json()['success']
        assert services.access_state('custom-device-12345')['allowed']
        assert client.post('/v1/admin/codes/delete',json={'code':code}).json()['success']
        assert not services.access_state('custom-device-12345')['allowed']
        assert not services.redeem_activation_code('new-device-123456',code)[0]


def test_custom_code_extends_and_is_separate_from_resellers(tmp_path):
    _, services, rs, ops, _, main=env(tmp_path)
    a=rs.create_reseller('vendeur-a',datetime.now(timezone.utc)+timedelta(days=30))
    reseller_code=rs.generate_reseller_subscription(a['id'],'24h')
    with TestClient(main.app,headers={'X-Admin-Token':'test-admin-token'}) as client:
        assert client.post('/v1/admin/codes/custom',json={'days':2},headers={'X-Admin-Token':''}).status_code==401
        for days in [0,-1,3651,1.5,'2',True,None]:
            assert client.post('/v1/admin/codes/custom',json={'days':days}).status_code==422
        code=client.post('/v1/admin/codes/custom',json={'days':2}).json()['codes'][0]
        assert [c['code'] for c in client.get('/v1/admin/codes').json()]==[code]
        first=services.redeem_activation_code('extend-device-12345',reseller_code)[2]
        second=services.redeem_activation_code('extend-device-12345',code)[2]
        assert second['remaining_seconds'] >= first['remaining_seconds'] + 2*86400 - 3
        assert [c['code'] for c in rs.list_reseller_codes(a['id'])]==[reseller_code]


def test_expired_reseller_can_resume_after_manual_expiry_change(tmp_path):
    db,services,rs,_,_,main=env(tmp_path)
    a=rs.create_reseller('vendeur-a',datetime.now(timezone.utc)+timedelta(days=1))
    token=rs.login_reseller(a['username'],a['password'])['token']
    with db.transaction() as cx:cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(services.now_ts()-1,a['id']))
    with pytest.raises(PermissionError):rs.generate_reseller_test(a['id'])
    with TestClient(main.app,headers={'X-Admin-Token':'test-admin-token'}) as client:
        assert client.post('/v1/reseller/codes/test',headers={'Authorization':'Bearer '+token}).status_code==401
        date=(datetime.now(timezone.utc)+timedelta(days=30)).isoformat()
        response=client.post(f"/v1/admin/resellers/{a['id']}/expiry",json={'expires_at':date})
        assert response.status_code==200 and response.json()['status']=='active'
        new=rs.login_reseller(a['username'],a['password'])['token']
        assert client.post('/v1/reseller/codes/test',headers={'Authorization':'Bearer '+new}).status_code==200
