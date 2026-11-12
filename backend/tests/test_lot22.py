import json
from test_saspay_resellers_updates import env, client_for
from test_reseller_purchases import buyer, provider

def switch(c, **flags):
    current = c.get('/v1/admin/pricing').json()
    result = c.put('/v1/admin/pricing', json={'revision': current['revision'], 'prices': current['base_prices'], **flags})
    assert result.status_code == 200, result.text
    return result.json()

def test_payment_switches_independent_persistent_and_existing_orders_preserved(env, provider, monkeypatch):
    db, svc, _, _, _, main, _ = env
    from app import saspay
    monkeypatch.setattr(main, "create_payment", saspay.create_payment)
    with client_for(main) as c:
        initial = c.get('/v1/pricing').json()
        assert initial['subscription_payments_enabled'] and initial['reseller_payments_enabled']
        pending_body = buyer()
        pending = c.post('/v1/reseller-purchases/start-monthly', json={**pending_body, 'quota_limit': 30}).json()
        switch(c, subscription_payments_enabled=False)
        assert c.post('/v1/payments/start', json={'device_id': 'payment-disabled-test-123', 'plan_id': '1w'}).status_code == 403
        assert c.post('/v1/reseller-purchases/start', json=buyer()).status_code == 200
        switch(c, reseller_payments_enabled=False)
        for route in ('start', 'start-monthly'):
            assert c.post('/v1/reseller-purchases/'+route, json={**buyer(), 'quota_limit': 70}).status_code == 403
        calls = len(provider)
        assert c.post('/v1/reseller-purchases/start-monthly', json={**pending_body, 'quota_limit': 30}).json()['payment_reference'] == pending['payment_reference']
        assert len(provider) == calls
        result = c.post('/v1/reseller-purchases/status', json={**pending_body, 'payment_reference': pending['payment_reference']})
        assert result.status_code == 200 and result.json()['account']
        db.init_db()
        saved = switch(c)
        assert not saved['subscription_payments_enabled'] and not saved['reseller_payments_enabled']
        switch(c, subscription_payments_enabled=True, reseller_payments_enabled=True)
        assert c.post('/v1/payments/start', json={'device_id': 'payment-enabled-test-123', 'plan_id': '1w'}).status_code == 200

def test_payment_switch_requires_admin_and_boolean(env):
    *_, main, _ = env
    with client_for(main) as c:
        catalog = c.get('/v1/pricing').json()
        body = {'revision': catalog['revision'], 'prices': catalog['base_prices'], 'subscription_payments_enabled': False}
        assert c.put('/v1/admin/pricing', json=body, headers={'X-Admin-Token': 'invalid'}).status_code == 401
        for value in ('false', 0, []):
            assert c.put('/v1/admin/pricing', json={**body, 'subscription_payments_enabled': value}).status_code == 422

def test_v2_copy_once_and_independent_config_without_global_changes(env):
    db, _, _, _, _, _, _ = env
    with db.transaction() as cx:
        before = dict(cx.execute("SELECT * FROM vpn_profiles WHERE network_id='global_world'").fetchone())
        cx.execute("DELETE FROM vpn_profiles WHERE network_id IN ('moov_bf_v2','orange_bf_v2')")
        cx.execute("UPDATE vpn_profiles SET config_json=?,enabled=1 WHERE network_id='orange_bf'", (json.dumps({'address': 'first.example', 'port': 443}),))
    db.init_db()
    with db.transaction() as cx:
        original = dict(cx.execute("SELECT * FROM vpn_profiles WHERE network_id='orange_bf'").fetchone())
        copy = dict(cx.execute("SELECT * FROM vpn_profiles WHERE network_id='orange_bf_v2'").fetchone())
        assert copy['config_json'] == original['config_json'] and copy['enabled'] == original['enabled']
        cx.execute("UPDATE vpn_profiles SET config_json=? WHERE network_id='orange_bf_v2'", (json.dumps({'address': 'second.example', 'port': 443}),))
    db.init_db()
    with db.connect() as cx:
        assert dict(cx.execute("SELECT * FROM vpn_profiles WHERE network_id='orange_bf'").fetchone()) == original
        assert json.loads(cx.execute("SELECT config_json FROM vpn_profiles WHERE network_id='orange_bf_v2'").fetchone()[0])['address'] == 'second.example'
        assert dict(cx.execute("SELECT * FROM vpn_profiles WHERE network_id='global_world'").fetchone()) == before
