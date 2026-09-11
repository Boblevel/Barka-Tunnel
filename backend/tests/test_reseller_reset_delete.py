import importlib
from concurrent.futures import ThreadPoolExecutor
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from test_panel_management import env


def test_reset_confirmation_auth_scope_and_audit(env):
    db, services, ops, rs, pm, a, b=env
    from app import main
    importlib.reload(main);client=TestClient(main.app)
    code=rs.generate_reseller_test(a['id']);rs.generate_reseller_test(b['id'])
    services.redeem_activation_code('reset-device-123456',code)
    before=services.access_state('reset-device-123456')
    auth={'Authorization':'Bearer '+rs.login_reseller(a['username'],a['password'])['token']}
    admin={'X-Admin-Token':'test-admin-token'}
    for path,headers in [('/v1/reseller/stats/reset',auth),('/v1/admin/stats/reset',admin)]:
        assert client.post(path,json={'confirmation':'oui'}).status_code in (401,403)
        for value in ('','non','REINITIALISER','OUI',' oui ',None):
            assert client.post(path,headers=headers,json={'confirmation':value}).status_code==422
    assert rs.reseller_dashboard_stats(a['id'])['total']==1
    assert client.post('/v1/reseller/stats/reset',headers=auth,json={'confirmation':'oui','reseller_id':b['id']}).status_code==200
    assert rs.reseller_dashboard_stats(a['id'])['total']==0
    assert rs.reseller_dashboard_stats(b['id'])['total']==1
    assert len(rs.list_reseller_codes(a['id']))==1
    assert services.access_state('reset-device-123456')['expires_at']==before['expires_at']
    assert pm.reseller_details(a['id'])['stats']['created']==1
    assert any(e['event']=='stats_reset' for e in pm.reseller_details(a['id'])['events'])
    rs.generate_reseller_test(a['id']);assert rs.reseller_dashboard_stats(a['id'])['total']==1
    rs.freeze_reseller(a['id'])
    assert client.post('/v1/reseller/stats/reset',headers=auth,json={'confirmation':'oui'}).status_code==401


def test_delete_cascades_and_preserves_others(env):
    db,services,ops,rs,pm,a,b=env
    unused=rs.generate_reseller_test(a['id']);used=rs.generate_reseller_subscription(a['id'],'24h')
    other=rs.generate_reseller_test(b['id']);test=rs.generate_reseller_test(a['id'])
    services.redeem_activation_code('delete-owned-device-1',used);services.redeem_activation_code('delete-owned-device-2',test)
    services.redeem_activation_code('delete-other-device-1',other)
    before=services.access_state('delete-other-device-1')
    token=rs.login_reseller(a['username'],a['password'])['token']
    rs.delete_reseller(a['id']);assert rs.list_reseller_codes(a['id'])==[]
    for device in ('delete-owned-device-1','delete-owned-device-2'):
        access=services.access_state(device)
        assert not access['allowed'] and access['remaining_seconds']==0 and access['access_revision']==1
    for code in (unused,used,test):assert not services.redeem_activation_code('delete-owned-device-1',code)[0]
    assert services.access_state('delete-other-device-1')['expires_at']==before['expires_at']
    assert services.access_state('delete-other-device-1')['access_revision']==0
    assert rs.list_reseller_codes(b['id'])[0]['code']==other
    with pytest.raises(HTTPException):rs.require_reseller('Bearer '+token)
    with pytest.raises(PermissionError):rs.generate_reseller_test(a['id'])


def test_delete_preserves_stacked_credit_and_gift(env,monkeypatch):
    db,services,ops,rs,pm,a,b=env
    now=services.now_ts()
    for module in (services,ops,rs):monkeypatch.setattr(module,'now_ts',lambda:now)
    device='stacked-credit-device'
    ca=rs.generate_reseller_subscription(a['id'],'24h');cb=rs.generate_reseller_test(b['id']);ca2=rs.generate_reseller_test(a['id'])
    for code in (ca,cb,ca2):assert services.redeem_activation_code(device,code)[0]
    services.start_trial(device);rs.delete_reseller(a['id']);state=services.access_state(device)
    assert state['remaining_seconds']==14400 and state['access_revision']==1
    assert rs.list_reseller_codes(b['id'])[0]['remaining_seconds']==7200
    assert not services.start_trial(device)[1]
    assert rs.delete_reseller_code(b['id'],cb)[0]
    assert services.access_state(device)['remaining_seconds']==7200


def test_delete_confirmation_atomic_generation_race(env):
    db,services,ops,rs,pm,a,b=env
    from app import main
    importlib.reload(main);client=TestClient(main.app);path=f'/v1/admin/resellers/{a["id"]}';headers={'X-Admin-Token':'test-admin-token'}
    for body in ({},{'confirmation':'non'}):assert client.request('DELETE',path,headers=headers,json=body).status_code==422
    assert client.request('DELETE',path,json={'confirmation':'oui'}).status_code in (401,403)
    rs.generate_reseller_test(a['id'])
    def generate():
        try:return rs.generate_reseller_test(a['id'])
        except PermissionError:return None
    with ThreadPoolExecutor(max_workers=2) as pool:
        creating=pool.submit(generate);deleting=pool.submit(rs.delete_reseller,a['id']);creating.result();deleting.result()
    assert rs.list_reseller_codes(a['id'])==[]
    assert client.request('DELETE',path,headers=headers,json={'confirmation':'oui'}).status_code==404


def test_delete_rollback_keeps_panel_and_codes(env,monkeypatch):
    db,services,ops,rs,pm,a,b=env
    code=rs.generate_reseller_test(a['id']);services.redeem_activation_code('rollback-device-123',code)
    original=rs.remove_reseller_access
    def fail(cx,who,now):
        original(cx,who,now);raise RuntimeError('simulated failure')
    monkeypatch.setattr(rs,'remove_reseller_access',fail)
    with pytest.raises(RuntimeError):rs.delete_reseller(a['id'])
    assert any(r['id']==a['id'] for r in rs.list_resellers())
    assert rs.list_reseller_codes(a['id'])[0]['code']==code
    assert services.access_state('rollback-device-123')['allowed']
