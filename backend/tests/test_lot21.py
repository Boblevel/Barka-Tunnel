from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import uuid

import pytest
from test_saspay_resellers_updates import env, client_for
from test_reseller_purchases import provider, buyer


@pytest.mark.parametrize('limit', [30, 70])
def test_quota_all_durations_test_exemption_and_idempotency(env, limit):
    db, svc, rs, ops, _, _, _ = env
    account = rs.create_reseller('quota-' + str(limit), datetime.now(timezone.utc) + timedelta(days=40), limit)
    identity = account['id']
    request = uuid.uuid4().hex
    first = rs.generate_reseller_subscription(identity, '1m', request)
    assert rs.generate_reseller_subscription(identity, '1m', request) == first
    assert rs.reseller_quota(identity)['used'] == 1
    with pytest.raises(ValueError):
        rs.generate_reseller_subscription(identity, '1w', request)
    for index in range(limit - 1):
        rs.generate_reseller_subscription(identity, ('1w', '2w', '1m')[index % 3])
    assert rs.reseller_quota(identity)['remaining'] == 0
    with pytest.raises(PermissionError):
        rs.generate_reseller_subscription(identity, '24h')
    assert rs.generate_reseller_subscription(identity, '1m', request) == first
    assert rs.generate_reseller_test(identity, 6)
    rs.reset_reseller_stats(identity)
    assert rs.delete_reseller_code(identity, first)[0]
    db.init_db()
    assert rs.reseller_quota(identity)['remaining'] == 1


def test_parallel_generation_cannot_exceed_quota(env):
    _, _, rs, _, _, _, account = env
    identity = account['id']
    for _ in range(29):
        rs.generate_reseller_subscription(identity, '1w')
    def generate(_):
        try:
            return rs.generate_reseller_subscription(identity, '1m')
        except PermissionError:
            return None
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(generate, range(8)))
    assert len([code for code in results if code]) == 1
    assert rs.reseller_quota(identity)['used'] == 30


def test_parallel_same_request_issues_one_code(env):
    db, _, rs, _, _, _, account = env
    request = uuid.uuid4().hex
    with ThreadPoolExecutor(max_workers=8) as pool:
        codes = list(pool.map(lambda _: rs.generate_reseller_subscription(account['id'], '1m', request), range(8)))
    assert len(set(codes)) == 1
    assert rs.reseller_quota(account['id'])['used'] == 1


def test_test_code_cannot_stack_and_is_not_consumed_on_rejection(env, monkeypatch):
    db, svc, rs, _, _, _, account = env
    clock = svc.now_ts()
    monkeypatch.setattr(svc, 'now_ts', lambda: clock)
    device = 'test-device-lot21-1234'
    first, second = rs.generate_reseller_test(account['id']), rs.generate_reseller_test(account['id'], 6)
    assert svc.redeem_activation_code(device, first)[0]
    assert svc.redeem_activation_code(device, first)[0]
    assert not svc.redeem_activation_code(device, second)[0]
    with db.connect() as cx:
        assert cx.execute('SELECT status FROM activation_codes WHERE code_hash=?', (svc.code_hash(second),)).fetchone()[0] == 'issued'
    clock += 7200
    assert not svc.redeem_activation_code(device, first)[0]
    result = svc.redeem_activation_code(device, second)
    assert result[0] and result[2]['remaining_seconds'] == 21600


def test_expired_paid_and_shared_codes_never_report_success(env, monkeypatch):
    _, svc, _, _, _, _, _ = env
    clock = svc.now_ts()
    monkeypatch.setattr(svc, 'now_ts', lambda: clock)
    for code in (svc.issue_activation_code('LOT21:expired', '24h'), svc.issue_redeem_code('LOT21:shared', 86400, 2)):
        device = 'expired-' + uuid.uuid4().hex
        assert svc.redeem_activation_code(device, code)[0]
        clock += 86401
        result = svc.redeem_activation_code(device, code)
        assert not result[0] and not result[2]['allowed']


@pytest.mark.parametrize('limit,amount', [(30,5000), (70,10000)])
def test_paid_renewal_resets_quota_immediately_once(env, provider, limit, amount, monkeypatch):
    db, svc, rs, _, _, main, _ = env
    from app.reseller_purchases import add_months
    clock = svc.now_ts()
    monkeypatch.setattr(svc, 'now_ts', lambda: clock)
    monkeypatch.setattr(rs, 'now_ts', lambda: clock)
    body = {**buyer(2), 'quota_limit': limit}
    route = '/v1/reseller-purchases/'
    with client_for(main) as client:
        reply = client.post(route+'start-monthly', json=body)
        assert reply.status_code == 200, reply.text
        reference = reply.json()['payment_reference']
        account = client.post(route+'status', json={**body, 'payment_reference': reference}).json()['account']
        assert provider[-1]['amount'] == amount
        expiry = int(datetime.fromisoformat(account['expires_at'].replace('Z', '+00:00')).timestamp())
        assert expiry == add_months(clock, 1)
        rs.generate_reseller_subscription(account['id'], '1m')
        assert rs.reseller_quota(account['id'])['remaining'] == limit-1
        # Renewal activates the purchased tier and resets occupied slots immediately.
        next_limit = 70 if limit == 30 else 30
        body.update(request_id=uuid.uuid4().hex, quota_limit=next_limit)
        reply = client.post(route+'start-monthly', json=body)
        reference = reply.json()['payment_reference']
        client.post(route+'status', json={**body, 'payment_reference': reference})
        assert rs.reseller_quota(account['id'])['remaining'] == next_limit
        rs.generate_reseller_subscription(account['id'], '1w')
        svc.mark_payment_paid(reference)
        assert rs.reseller_quota(account['id'])['used'] == 1
        with db.connect() as cx:
            assert cx.execute('SELECT COUNT(*) FROM reseller_quota_periods WHERE reseller_id=?', (account['id'],)).fetchone()[0] == 1
        clock = expiry
        assert rs.reseller_quota(account['id'])['remaining'] == next_limit-1


def test_quota_expires_with_account(env, monkeypatch):
    _, svc, rs, _, _, _, _ = env
    clock = svc.now_ts()
    monkeypatch.setattr(rs, 'now_ts', lambda: clock)
    account = rs.create_reseller('monthly-lot21', datetime.fromtimestamp(clock+90*86400, timezone.utc), 70)
    rs.generate_reseller_subscription(account['id'], '1m')
    current = rs.reseller_quota(account['id'])
    clock = current['ends_at']
    assert rs.reseller_quota(account['id'])['remaining'] == 0
