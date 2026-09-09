from __future__ import annotations

import importlib
from datetime import datetime, timedelta, timezone
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from test_core import load_modules


@pytest.fixture
def env(tmp_path):
    db, services = load_modules(tmp_path)
    from app import reseller_audit, admin_ops, resellers, panel_management
    for module in (reseller_audit, admin_ops, resellers, panel_management):
        importlib.reload(module)
    expiry = datetime.now(timezone.utc) + timedelta(days=30)
    a = resellers.create_reseller('vendeur-a', expiry)
    b = resellers.create_reseller('vendeur-b', expiry)
    return db, services, admin_ops, resellers, panel_management, a, b


def filtered(pm, kind='subscription', status='issued', query='', **kw):
    return pm.FilterRequest(kind=kind, status=status, query=query, **kw)


def test_filtered_delete_all_235_owned_only_and_preserves_weekly_ledger(env):
    db, services, ops, rs, pm, a, b = env
    for _ in range(235):
        rs.generate_reseller_test(a['id'])
    other = rs.generate_reseller_test(b['id'])
    subscription = rs.generate_reseller_subscription(a['id'], '24h')
    services.start_trial('weekly-device-123456')
    body = filtered(pm, query='test_2h')
    preview = pm.filtered_codes(body, a['id'])
    assert preview['count'] == 235
    result = pm.filtered_codes(body.model_copy(update={'fingerprint': preview['fingerprint']}), a['id'], delete=True)
    assert result['deleted'] == 235
    assert [r['code'] for r in rs.list_reseller_codes(a['id'])] == [subscription]
    assert rs.list_reseller_codes(b['id'])[0]['code'] == other
    assert services.start_trial('weekly-device-123456')[1] is False
    with db.connect() as cx:
        assert cx.execute('SELECT COUNT(*) FROM reseller_events WHERE event="code_deleted"').fetchone()[0] == 235


def test_changed_filter_list_rolls_back_entire_delete(env):
    db, services, ops, rs, pm, a, b = env
    first = rs.generate_reseller_test(a['id'])
    second = rs.generate_reseller_test(a['id'])
    body = filtered(pm)
    preview = pm.filtered_codes(body, a['id'])
    services.redeem_activation_code('device-changed-12345', first)
    with pytest.raises(HTTPException) as error:
        pm.filtered_codes(body.model_copy(update={'fingerprint': preview['fingerprint']}), a['id'], delete=True)
    assert error.value.status_code == 409
    assert len(rs.list_reseller_codes(a['id'])) == 2


@pytest.mark.parametrize('kind', ['subscription', 'redeem'])
def test_expired_filtered_delete_preserves_active_and_unused(env, kind):
    db, services, ops, rs, pm, a, b = env
    issue = lambda tag: (services.issue_activation_code(tag, '24h') if kind == 'subscription' else services.issue_redeem_code(tag, 7200, 1))
    dead, active, unused = [issue('FILTER:'+kind+':'+n) for n in ['expired', 'active', 'unused']]
    for code, dev in [(dead, 'device-dead-123456'), (active, 'device-live-123456')]:
        assert services.redeem_activation_code(dev, code)[0]
    with db.transaction() as cx:
        if kind == 'subscription':
            cx.execute('UPDATE activation_codes SET applied_until=? WHERE redeemed_device_id=?', (services.now_ts()-1, 'device-dead-123456'))
        else:
            cx.execute('UPDATE redeem_usages SET applied_until=? WHERE device_id=?', (services.now_ts()-1, 'device-dead-123456'))
    before = services.access_state('device-live-123456')['expires_at']
    body = filtered(pm, kind, 'expired');preview = pm.filtered_codes(body)
    assert preview['count'] == 1
    pm.filtered_codes(body.model_copy(update={'fingerprint': preview['fingerprint']}), delete=True)
    listing = ops.list_activation_codes() if kind == 'subscription' else ops.list_redeem_codes()
    assert {r['code'] for r in listing} == {active, unused}
    assert services.access_state('device-live-123456')['expires_at'] == before


def test_reseller_forbidden_redeem_and_other_scope_fingerprint(env):
    _, _, _, rs, pm, a, b = env
    rs.generate_reseller_test(a['id']);rs.generate_reseller_test(b['id'])
    with pytest.raises(HTTPException) as error:
        pm.filtered_codes(filtered(pm, 'redeem', 'active'), a['id'])
    assert error.value.status_code == 403
    body=filtered(pm);preview=pm.filtered_codes(body, a['id'])
    with pytest.raises(HTTPException) as error:
        pm.filtered_codes(body.model_copy(update={'fingerprint':preview['fingerprint']}), b['id'], delete=True)
    assert error.value.status_code == 409
    assert len(rs.list_reseller_codes(a['id'])) == len(rs.list_reseller_codes(b['id'])) == 1


def test_missing_confirmation_expired_account_and_invalid_filter(env):
    db, services, _, rs, pm, a, _ = env
    rs.generate_reseller_test(a['id'])
    with pytest.raises(HTTPException):pm.filtered_codes(filtered(pm), a['id'], delete=True)
    with pytest.raises(HTTPException):pm.filtered_codes(filtered(pm, status='active'))
    with db.transaction() as cx:cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(services.now_ts()-1,a['id']))
    with pytest.raises(HTTPException) as error:pm.filtered_codes(filtered(pm),a['id'])
    assert error.value.status_code==403
    assert pm.reseller_details(a['id'])['account']['status']=='expired'


def test_login_audit_stats_no_secrets_and_pagination(env):
    _, services, ops, rs, pm, a, b = env
    with pytest.raises(ValueError):rs.login_reseller(a['username'], 'wrong-password-123', '192.0.2.1')
    session=rs.login_reseller(a['username'], a['password'], '192.0.2.2')
    code=rs.generate_reseller_test(a['id']);services.redeem_activation_code('stats-device-12345',code)
    rs.generate_reseller_subscription(a['id'],'1w')
    detail=pm.reseller_details(a['id'])
    assert detail['stats']['created']==2 and detail['stats']['used']==1 and detail['stats']['active']==1
    assert detail['security']=={'login_success':1,'login_failed':1}
    assert {e['client_ip'] for e in detail['events'] if e['event'].startswith('login_')}=={'192.0.2.1','192.0.2.2'}
    assert a['password'] not in str(detail) and session['token'] not in str(detail)
    first=ops.list_activation_codes(1,a['id']);second=ops.list_activation_codes(1,a['id'],offset=1)
    assert first[0]['code']!=second[0]['code']
    assert pm.reseller_details(b['id'])['events']==[]


def test_concurrent_confirm_only_one_deletes(env):
    _, _, _, rs, pm, a, _ = env
    rs.generate_reseller_test(a['id']);body=filtered(pm)
    preview=pm.filtered_codes(body,a['id']);body=body.model_copy(update={'fingerprint':preview['fingerprint']})
    def run():
        try:return pm.filtered_codes(body,a['id'],delete=True)['deleted']
        except HTTPException as e:return e.status_code
    with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:run(),range(2)))
    assert sorted(results)==[1,409]


def test_http_routes_enforce_admin_and_reseller_auth(env):
    _, _, _, rs, pm, a, b=env
    from app import main
    importlib.reload(main)
    client=TestClient(main.app)
    admin={'X-Admin-Token':'test-admin-token'}
    session=rs.login_reseller(a['username'],a['password']);auth={'Authorization':'Bearer '+session['token']}
    assert client.get(f'/v1/admin/resellers/{a["id"]}/details',headers=auth).status_code in (401,403)
    assert client.get(f'/v1/admin/resellers/{a["id"]}/details',headers=admin).status_code==200
    body=filtered(pm).model_dump()
    assert client.post('/v1/admin/codes/delete-filtered',json=body).status_code in (401,403)
    assert client.post('/v1/reseller/codes/filter-preview',json=body,headers=auth).status_code==200
    assert client.post('/v1/reseller/codes/filter-preview',json={**body,'kind':'redeem','status':'active'},headers=auth).status_code==403
    assert client.post('/v1/reseller/login',json={'username':a['username'],'password':a['password']}).status_code==200


def test_redeem_filter_preserves_unfilled_places_and_last_active_user(env, monkeypatch):
    db, services, ops, rs, pm, a, b = env
    now = services.now_ts()
    monkeypatch.setattr(ops, "now_ts", lambda: now)
    codes = {name: services.issue_redeem_code("CAPACITY:" + name, 7200, 5)
             for name in ("empty", "partial", "last_active", "expired", "disabled")}
    for name, count in (("partial", 2), ("last_active", 5), ("expired", 5), ("disabled", 2)):
        for i in range(count):
            assert services.redeem_activation_code(f"capacity-{name}-device-{i}", codes[name])[0]
    with db.transaction() as cx:
        cx.execute("UPDATE redeem_usages SET applied_until=?", (now,))
        cx.execute("UPDATE redeem_usages SET applied_until=? WHERE device_id=?",
                   (now + 1, "capacity-last_active-device-4"))
    ops.revoke_redeem_code(codes["disabled"])
    rows = {r["code"]: r for r in ops.list_redeem_codes()}
    assert rows[codes["partial"]]["status"] == "active"
    assert rows[codes["last_active"]]["status"] == "active"
    assert rows[codes["expired"]]["status"] == "expired"
    assert rows[codes["disabled"]]["status"] == "revoked"
    before = services.access_state("capacity-last_active-device-4")["expires_at"]
    body = filtered(pm, "redeem", "expired")
    preview = pm.filtered_codes(body)
    assert preview["count"] == 1
    result = pm.filtered_codes(body.model_copy(update={"fingerprint": preview["fingerprint"]}), delete=True)
    assert result["deleted"] == 1
    assert {r["code"] for r in ops.list_redeem_codes()} == set(codes.values()) - {codes["expired"]}
    assert services.access_state("capacity-last_active-device-4")["expires_at"] == before
    # Once the last user's time reaches zero, the full code expires as well.
    monkeypatch.setattr(ops, "now_ts", lambda: now + 1)
    assert next(r for r in ops.list_redeem_codes() if r["code"] == codes["last_active"])["status"] == "expired"
    # Available places remain usable after the earlier users finished.
    assert services.redeem_activation_code("capacity-new-device-123", codes["partial"])[0]
    assert not services.redeem_activation_code("capacity-blocked-device", codes["disabled"])[0]
