import importlib
import json
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient
from test_custom_subscriptions import env


@pytest.mark.parametrize('hours', [2, 3, 6])
def test_test_codes_duration_ownership_and_freeze(tmp_path, hours):
    db, services, rs, ops, pm, main = env(tmp_path)
    account = rs.create_reseller('test-vendeur', datetime.now(timezone.utc) + timedelta(days=30))
    token = rs.login_reseller(account['username'], account['password'])['token']
    with TestClient(main.app, headers={'Authorization': 'Bearer ' + token}) as client:
        response = client.post('/v1/reseller/codes/test', json={'duration_hours': hours})
        assert response.status_code == 200, response.text
        data = response.json()
        assert data['plan_id'] == f'test_{hours}h'
        assert data['duration_seconds'] == hours * 3600
        ok, _, access = services.redeem_activation_code('test-code-device-123', data['code'])
        assert ok and hours * 3600 - 3 <= access['remaining_seconds'] <= hours * 3600
        assert not services.redeem_activation_code('other-code-device-123', data['code'])[0]
        rs.freeze_reseller(account['id'])
        assert client.post('/v1/reseller/codes/test', json={'duration_hours': hours}).status_code == 401


def test_test_codes_validation_and_legacy(tmp_path):
    db, services, rs, ops, pm, main = env(tmp_path)
    account = rs.create_reseller('test-vendeur', datetime.now(timezone.utc) + timedelta(days=30))
    token = rs.login_reseller(account['username'], account['password'])['token']
    with TestClient(main.app) as client:
        assert client.post('/v1/reseller/codes/test', json={'duration_hours': 6}).status_code == 401
        client.headers['Authorization'] = 'Bearer ' + token
        for invalid in [0, 1, 4, 24, -1, True, 3.0, '6']:
            assert client.post('/v1/reseller/codes/test', json={'duration_hours': invalid}).status_code == 422
        assert client.post('/v1/reseller/codes/test', json={'duration_hours': 6, 'free': True}).status_code == 422
        assert client.post('/v1/reseller/codes/test').json()['duration_seconds'] == 7200


def test_default_migration_is_idempotent_and_preserves_custom_settings(tmp_path):
    db, *_ = env(tmp_path)
    with db.transaction() as cx:
        row = cx.execute("SELECT * FROM vpn_profiles WHERE network_id='global_world'").fetchone()
        cfg = json.loads(row['config_json'])
        cfg['host'] = 'ahmed-vidgg-294397537401.us-central1.run.app'
        cx.execute("UPDATE vpn_profiles SET config_json=? WHERE network_id='global_world'", (json.dumps(cfg),))
        old_version = row['version']
    db.init_db()
    db.init_db()
    with db.transaction() as cx:
        row = cx.execute("SELECT * FROM vpn_profiles WHERE network_id='global_world'").fetchone()
        assert row['version'] == old_version + 1
        assert json.loads(row['config_json'])['host'] == 'sangoku-149521136708.us-central1.run.app'
        cfg['path'] = '/custom-owner-path'
        cx.execute("UPDATE vpn_profiles SET config_json=? WHERE network_id='global_world'", (json.dumps(cfg),))
    db.init_db()
    with db.connect() as cx:
        assert json.loads(cx.execute("SELECT config_json FROM vpn_profiles WHERE network_id='global_world'").fetchone()[0]) == cfg


def test_paid_subscription_activates_without_client_redeem(tmp_path):
    db, services, *_ = env(tmp_path)
    device = 'automatic-payment-device-123'
    ref = services.create_payment_record(device, '24h')
    code = services.mark_payment_paid(ref)
    state = services.access_state(device)
    assert state['allowed'] and state['access_type'] == 'SUBSCRIPTION'
    assert 86395 <= state['remaining_seconds'] <= 86400
    assert services.mark_payment_paid(ref) == code
    assert services.access_state(device)['expires_at'] == state['expires_at']
    assert not services.redeem_activation_code('other-payment-device-123', code)[0]
