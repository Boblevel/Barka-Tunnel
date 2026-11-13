import pytest
from test_saspay_resellers_updates import env, client_for


def test_admin_quota_reset_customize_and_isolation(env):
    db, svc, rs, _, _, main, account = env
    rid = account['id']
    code = rs.generate_reseller_subscription(rid, '2w', 'same-request')
    before = rs.reseller_quota(rid)
    route = f'/v1/admin/resellers/{rid}/quota'
    with client_for(main) as client:
        assert client.put(route, json={'limit': 5}, headers={'X-Admin-Token':'wrong'}).status_code == 401
        for limit in (-1, 100001, True, 1.5, '10'):
            assert client.put(route, json={'limit':limit}).status_code == 422
        q = client.put(route, json={'limit':5}).json()
        assert (q['limit'], q['used'], q['remaining']) == (5,1,4)
        assert q['ends_at'] == before['ends_at']
        assert client.post(route+'/reset', json={'confirmation':'non'}).status_code == 422
        q = client.post(route+'/reset', json={'confirmation':'oui'}).json()
        assert (q['limit'], q['used'], q['remaining'], q['revision']) == (5,0,5,2)
        assert rs.generate_reseller_subscription(rid,'2w','same-request') == code
        assert rs.reseller_quota(rid)['used'] == 0
        assert svc.redeem_activation_code('lot23-existing-code',code)[0]
        assert client.put(route,json={'limit':0}).json()['remaining'] == 0
        with pytest.raises(PermissionError):
            rs.generate_reseller_subscription(rid,'1m')
        assert rs.generate_reseller_test(rid)
        db.init_db()
        assert rs.reseller_quota(rid)['limit'] == 0


def test_custom_quota_only_current_cycle(env,monkeypatch):
    _, svc, rs, _, _, _, account = env
    now = svc.now_ts()
    from datetime import datetime,timezone
    rs.update_reseller_expiry(account['id'],datetime.fromtimestamp(now+90*86400,timezone.utc))
    q = rs.adjust_reseller_quota(account['id'],limit=99)
    monkeypatch.setattr(rs,'now_ts',lambda:q['ends_at'])
    assert rs.reseller_quota(account['id'])['limit'] == 30


def test_redeem_all_finished_even_with_unused_seats(env,monkeypatch):
    _, svc, _, ops, _, main, _ = env
    now = svc.now_ts()
    monkeypatch.setattr(svc,'now_ts',lambda:now)
    monkeypatch.setattr(ops,'now_ts',lambda:now)
    code = svc.issue_redeem_code('lot23-shared',3600,3)
    assert not ops.list_redeem_codes()[0]['all_users_finished']
    assert svc.redeem_activation_code('lot23-shared-a',code)[0]
    now += 3601
    row = ops.list_redeem_codes()[0]
    assert row['all_users_finished'] and row['status'] == 'active'
    with client_for(main) as client:
        reply=client.get('/v1/admin/redeem-codes')
        assert reply.status_code == 200 and reply.json()[0]['all_users_finished']
    assert svc.redeem_activation_code('lot23-shared-b',code)[0]
    assert not ops.list_redeem_codes()[0]['all_users_finished']


def test_two_weeks_exact_and_stacking_explained(env,monkeypatch):
    db,svc,rs,ops,_,_,account=env
    now=svc.now_ts()
    monkeypatch.setattr(svc,'now_ts',lambda:now)
    for owner in ('admin','reseller'):
        device='lot23-duration-'+owner
        for index in range(2):
            code=svc.issue_activation_code('lot23-admin-'+str(index),'2w') if owner=='admin' else rs.generate_reseller_subscription(account['id'],'2w')
            assert svc.redeem_activation_code(device,code)[0]
            with db.connect() as cx:
                r=cx.execute('SELECT * FROM activation_codes WHERE code_hash=?',(svc.code_hash(code),)).fetchone()
                assert r['applied_until']-r['applied_from']==14*86400
                assert r['applied_until']==now+(index+1)*14*86400
            for _ in range(3):
                ops.list_activation_codes()
            with db.connect() as cx:
                assert cx.execute('SELECT applied_until FROM activation_codes WHERE id=?',(r['id'],)).fetchone()[0]==r['applied_until']


def test_duration_audit_readonly_and_no_private_code(env,monkeypatch):
    db,svc,rs,_,_,_,account=env
    from app import duration_audit
    monkeypatch.setattr(duration_audit,'settings',db.settings)
    code=rs.generate_reseller_subscription(account['id'],'2w')
    assert svc.redeem_activation_code('lot23-audit-device',code)[0]
    with db.transaction() as cx:
        cx.execute('UPDATE activation_codes SET applied_from=applied_from+100*86400,applied_until=applied_until+100*86400 WHERE code_hash=?',(svc.code_hash(code),))
    with db.connect() as cx:
        before=[tuple(r) for r in cx.execute('SELECT * FROM activation_codes')]
    report=duration_audit.audit()
    assert report['read_only'] and report['long_timelines'][0]['duration_seconds']==14*86400
    assert 'lot23-audit-device' not in str(report) and code not in str(report)
    with db.connect() as cx:
        assert before==[tuple(r) for r in cx.execute('SELECT * FROM activation_codes')]


def test_expired_reseller_cannot_reset_even_with_open_quota_period(env):
    db,svc,rs,_,_,_,account=env
    rs.generate_reseller_subscription(account['id'],'2w')
    with db.transaction() as cx:
        cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(svc.now_ts()-1,account['id']))
    with pytest.raises(ValueError):
        rs.adjust_reseller_quota(account['id'],reset=True)
