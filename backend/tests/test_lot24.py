"""Generation recovery keeps server quotas authoritative."""
import pytest
from test_saspay_resellers_updates import env, client_for


def test_reseller_retry_after_lost_response_keeps_code_and_quota(env):
    _, _, rs, _, _, main, account = env
    with client_for(main) as client:
        login = client.post('/v1/reseller/login', json={
            'username': account['username'], 'password': account['password']})
        assert login.status_code == 200, login.text
        headers = {'Authorization': 'Bearer ' + login.json()['token']}
        path = '/v1/reseller/codes/subscription'
        request = {'plan_id': '2w', 'request_id': 'lot24-interrupted-generation'}
        first = client.post(path, json=request, headers=headers)
        assert first.status_code == 200, first.text
        retry = client.post(path, json=request, headers=headers)
        assert retry.status_code == 200
        assert retry.json()['code'] == first.json()['code']
        assert retry.json()['quota']['used'] == 1
        assert retry.json()['quota']['remaining'] == 29
        other = client.post(path, json={'plan_id': '1m', 'request_id': 'lot24-next-generation'}, headers=headers)
        assert other.status_code == 200
        assert other.json()['quota']['used'] == 2
        assert other.json()['code'] != first.json()['code']


def test_available_quota_does_not_override_frozen_account(env):
    db, _, rs, _, _, _, account = env
    assert rs.reseller_quota(account['id'])['remaining'] == 30
    with db.transaction() as cx:
        cx.execute("UPDATE reseller_accounts SET status='frozen' WHERE id=?", (account['id'],))
    with pytest.raises(PermissionError):
        rs.generate_reseller_subscription(account['id'], '1w')
    assert rs.reseller_quota(account['id'])['used'] == 0
