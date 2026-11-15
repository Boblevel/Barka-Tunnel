import pytest
from fastapi import HTTPException
from test_saspay_resellers_updates import env, client_for


def test_limit_expiration_deletion_and_reset(env, monkeypatch):
    db, svc, rs, _, _, _, account = env
    rid = account['id']
    rs.adjust_reseller_quota(rid, limit=2)
    first = rs.generate_reseller_subscription(rid, '24h')
    second = rs.generate_reseller_subscription(rid, '24h')
    assert svc.redeem_activation_code('quota33-first', first)[0]
    assert svc.redeem_activation_code('quota33-second', second)[0]
    with pytest.raises(PermissionError): rs.generate_reseller_subscription(rid, '1w')
    assert rs.generate_reseller_test(rid)
    assert rs.delete_reseller_code(rid, first)[0]
    assert rs.reseller_quota(rid)['used'] == 2
    with pytest.raises(PermissionError): rs.generate_reseller_subscription(rid, '1w')
    now = svc.now_ts()
    monkeypatch.setattr(rs, 'now_ts', lambda: now + 86401)
    assert rs.reseller_quota(rid)['used'] == 0
    assert rs.generate_reseller_subscription(rid, '1w')


def test_over_limit_and_admin_actions(env):
    db, svc, rs, _, _, _, account = env
    rid = account['id']
    one = rs.generate_reseller_subscription(rid, '1w')
    two = rs.generate_reseller_subscription(rid, '2w')
    assert svc.redeem_activation_code('quota33-over', one)[0]
    rs.adjust_reseller_quota(rid, limit=1)
    assert rs.reseller_quota(rid)['used'] == 2
    with pytest.raises(PermissionError): rs.generate_reseller_subscription(rid, '1w')
    with db.connect() as cx:
        before = [tuple(x) for x in cx.execute('SELECT code_hash,status,applied_until,deleted_at FROM activation_codes WHERE created_by_reseller_id=? ORDER BY id', (rid,))]
    rs.adjust_reseller_quota(rid, reset=True)
    with db.connect() as cx:
        after = [tuple(x) for x in cx.execute('SELECT code_hash,status,applied_until,deleted_at FROM activation_codes WHERE created_by_reseller_id=? ORDER BY id', (rid,))]
    assert before == after
    assert rs.reseller_quota(rid)['used'] == 0
    with pytest.raises(PermissionError):
        rs.generate_reseller_subscription(rid, '1w')
    rs.adjust_reseller_quota(rid, limit=3)
    assert rs.generate_reseller_subscription(rid, '1w')
    assert svc.redeem_activation_code('quota33-unused', two)[0]


def test_reseller_bulk_deletion_requires_filter(env):
    _, _, rs, _, _, main, account = env
    from app.panel_management import FilterRequest, filtered_codes
    rid = account['id']
    code = rs.generate_reseller_subscription(rid, '1w')
    for delete in (False, True):
        with pytest.raises(HTTPException) as error:
            filtered_codes(FilterRequest(kind='subscription', status='all', query='  '), rid, delete=delete)
        assert error.value.status_code == 400
    body = FilterRequest(kind='subscription', status='all', query=code)
    preview = filtered_codes(body, rid)
    assert preview['count'] == 1
    assert filtered_codes(body.model_copy(update={'fingerprint': preview['fingerprint']}), rid, delete=True)['deleted'] == 1
    assert rs.reseller_quota(rid)['used'] == 1


def test_one_expiration_releases_exactly_one_slot(env, monkeypatch):
    _, svc, rs, _, _, _, account = env
    rid = account['id']
    rs.adjust_reseller_quota(rid, limit=2)
    first = rs.generate_reseller_subscription(rid, '1w')
    second = rs.generate_reseller_subscription(rid, '2w')
    assert svc.redeem_activation_code('quota33-week1', first)[0]
    assert svc.redeem_activation_code('quota33-week2', second)[0]
    now = svc.now_ts()
    monkeypatch.setattr(rs, 'now_ts', lambda: now + 7 * 86400 + 1)
    assert rs.reseller_quota(rid)['remaining'] == 1
    rs.generate_reseller_subscription(rid, '1m')
    with pytest.raises(PermissionError): rs.generate_reseller_subscription(rid, '1m')


def test_admin_reset_keeps_old_codes_under_the_cap_until_expiry(env, monkeypatch):
    _, svc, rs, _, _, _, account = env
    rid = account['id']
    now = svc.now_ts()
    rs.adjust_reseller_quota(rid, limit=2)
    for device in ('lot39-first', 'lot39-second'):
        code = rs.generate_reseller_subscription(rid, '24h')
        assert svc.redeem_activation_code(device, code)[0]

    quota = rs.adjust_reseller_quota(rid, reset=True)
    assert (quota['used'], quota['remaining']) == (0, 0)
    with pytest.raises(PermissionError):
        rs.generate_reseller_subscription(rid, '1w')

    monkeypatch.setattr(rs, 'now_ts', lambda: now + 86410)
    quota = rs.reseller_quota(rid)
    assert (quota['used'], quota['remaining']) == (0, 2)
    assert rs.generate_reseller_subscription(rid, '1w')
    assert rs.reseller_quota(rid)['used'] == 1
