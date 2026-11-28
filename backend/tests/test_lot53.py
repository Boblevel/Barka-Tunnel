from datetime import datetime, timezone
from test_panel_management import env


def test_redeem_creation_is_not_activation_and_full_is_not_expired(env, monkeypatch):
    db, svc, ops, *_ = env
    clock = int(datetime(2026, 11, 27, 22, 52, 25, tzinfo=timezone.utc).timestamp())
    for module in (svc, ops): monkeypatch.setattr(module, 'now_ts', lambda: clock)
    code = svc.issue_redeem_code('lot53-single', 86400, 1)
    created = ops.list_redeem_codes()[0]['created_at']
    assert ops.list_redeem_codes()[0]['activation_dates'] == []
    clock += 600
    assert svc.redeem_activation_code('lot53-device-00001', code)[0]
    activation = ops.list_redeem_codes()[0]['activation_dates'][0]
    assert activation != created
    clock += 10 * 3600
    row = ops.list_redeem_codes()[0]
    assert row['status'] == 'active' and not row['all_users_finished']
    assert row['activation_dates'] == [activation] and row['created_at'] == created
    clock += 14 * 3600
    row = ops.list_redeem_codes()[0]
    assert row['status'] == 'expired' and row['all_users_finished']


def test_redeem_available_slot_stays_active_and_last_user_controls_expiry(env, monkeypatch):
    db, svc, ops, *_ = env
    clock = svc.now_ts()
    for module in (svc, ops): monkeypatch.setattr(module, 'now_ts', lambda: clock)
    code = svc.issue_redeem_code('lot53-multi', 86400, 2)
    assert svc.redeem_activation_code('lot53-first-000001', code)[0]
    clock += 86400
    row = ops.list_redeem_codes()[0]
    assert row['status'] == 'active' and not row['all_users_finished']
    assert row['unfinished_usage_count'] == 0
    assert svc.redeem_activation_code('lot53-second-00001', code)[0]
    row = ops.list_redeem_codes()[0]
    assert len(row['activation_dates']) == 2 and row['activation_dates'][0] != row['activation_dates'][1]
    assert row['status'] == 'active' and row['unfinished_usage_count'] == 1
    clock += 86400
    assert ops.list_redeem_codes()[0]['status'] == 'expired'


def test_reseller_token_cannot_control_prices_or_payments(env):
    import importlib
    from fastapi.testclient import TestClient
    from app import main
    db, svc, ops, rs, pm, account, _ = env
    importlib.reload(main)
    token = rs.login_reseller(account['username'], account['password'])['token']
    with TestClient(main.app) as client:
        catalog = client.get('/v1/pricing').json()
        body = {'revision': catalog['revision'], 'prices': catalog['base_prices'], 'subscription_payments_enabled': False}
        for headers in ({}, {'Authorization': 'Bearer '+token}, {'X-Admin-Token': token}):
            assert client.put('/v1/admin/pricing', json=body, headers=headers).status_code == 401
        assert client.get('/v1/pricing').json()['subscription_payments_enabled'] is True


def test_server_blocks_new_orders_but_keeps_acquired_access(env):
    import importlib
    from fastapi.testclient import TestClient
    from app import main
    from test_reseller_purchases import buyer
    db, svc, *_ = env
    importlib.reload(main)
    device='lot53-paid-access-00001'
    code=svc.issue_activation_code('lot53-paid-access','1m')
    assert svc.redeem_activation_code(device,code)[0]
    expiry=svc.access_state(device)['expires_at']
    with TestClient(main.app) as client:
        catalog=client.get('/v1/pricing').json()
        body={'revision':catalog['revision'],'prices':catalog['base_prices'],'subscription_payments_enabled':False,'reseller_payments_enabled':False}
        assert client.put('/v1/admin/pricing',json=body,headers={'X-Admin-Token':'test-admin-token'}).status_code==200
        assert client.post('/v1/payments/start',json={'device_id':device,'plan_id':'24h'}).status_code==403
        assert client.post('/v1/reseller-purchases/start',json=buyer()).status_code==403
        assert svc.access_state(device)['allowed'] and svc.access_state(device)['expires_at']==expiry
        with db.connect() as cx: assert cx.execute('SELECT COUNT(*) FROM payments').fetchone()[0]==0


def test_new_reseller_password_is_eight_chars_and_legacy_password_survives(env):
    import base64, hashlib, hmac
    db, svc, ops, rs, pm, account, _ = env
    assert len(account['password']) == 8
    assert rs.reseller_credentials(account['id'])['password'] == account['password']
    assert rs.login_reseller(account['username'], account['password'])['token']
    nonce='1a'*32
    digest=hmac.new(rs.settings.code_secret.encode(),('barka-reseller-password-v1:'+nonce).encode(),hashlib.sha256).digest()
    legacy='Bt7!'+base64.urlsafe_b64encode(digest).decode()[:24]
    salt=b'1234567890123456'
    with db.transaction() as cx:
        cx.execute('UPDATE reseller_accounts SET password_nonce=?,password_hash=?,password_salt=? WHERE id=?',
                   (nonce,rs._encode(rs._password_digest(legacy,salt)),rs._encode(salt),account['id']))
    assert rs.reseller_credentials(account['id'])['password']==legacy
    assert rs.login_reseller(account['username'],legacy)['token']
