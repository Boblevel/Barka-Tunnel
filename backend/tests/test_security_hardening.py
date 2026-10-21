import asyncio
import importlib
from concurrent.futures import ThreadPoolExecutor
import httpx
import pytest
from fastapi import HTTPException
from test_saspay_resellers_updates import env, client_for


def test_rate_limit_is_atomic_persistent_and_expires(env):
    from app import request_protection as guard
    def attempt(_):
        try:
            guard.consume_limits([('test', 'identity', 5, 300)], now=1000)
            return True
        except HTTPException as error:
            assert error.status_code == 429
            return False
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(attempt, range(20))) == 5
    importlib.reload(guard)
    with pytest.raises(HTTPException): guard.consume_limits([('test', 'identity', 5, 300)], now=1200)
    guard.consume_limits([('test', 'identity', 5, 300)], now=1300)


def test_bad_admin_tokens_cannot_lock_out_valid_admin(env):
    *_, main, account = env
    with client_for(main) as client:
        for _ in range(20):
            assert client.get('/v1/admin/stats', headers={'X-Admin-Token':'wrong'}).status_code == 401
        assert client.get('/v1/admin/stats', headers={'X-Admin-Token':'wrong'}).status_code == 429
        assert client.get('/v1/admin/stats').status_code == 200


def test_guessing_activation_and_password_is_limited(env):
    *_, main, account = env
    with client_for(main) as client:
        body = {'device_id':'security-device-abcdefghijkl','code':'BARKA-AAAA-BBBB-CCCC'}
        for _ in range(20): assert client.post('/v1/activation/redeem', json=body).status_code == 200
        response = client.post('/v1/activation/redeem', json=body)
        assert response.status_code == 429 and int(response.headers['retry-after']) > 0
        for _ in range(10):
            assert client.post('/v1/reseller/login', json={'username':account['username'],'password':'invalid-password'}).status_code == 401
        assert client.post('/v1/reseller/login', json={'username':account['username'],'password':'invalid-password'}).status_code == 429


def test_cross_origin_mutations_denied_and_sensitive_responses_not_cached(env):
    *_, main, account = env
    with client_for(main) as client:
        assert client.post('/v1/admin/codes', json={'plan_id':'24h','count':1}, headers={'Origin':'https://evil.example'}).status_code == 403
        assert client.post('/v1/admin/codes', json={'plan_id':'24h','count':1}, headers={'Origin':'https://api.test.local'}).status_code == 200
        response = client.get('/v1/admin/stats')
        assert response.headers['cache-control'] == 'no-store'
        assert response.headers['x-frame-options'] == 'DENY'
        assert "base-uri 'none'" in client.get('/admin').headers['content-security-policy']


@pytest.mark.parametrize('field,value', [('amount','1'),('amount','NaN'),('currency','EUR'),('id','other'),('metadata',{'barka_reference':'other'})])
def test_forged_paid_fields_do_not_grant_access(env, field, value):
    db, services, *_ = env
    from app import saspay
    reference = services.create_payment_record('security-payment-abcdefgh', '24h')
    services.update_payment_created(reference, 'provider-security-id', 'https://checkout.example')
    payload = {'id':'provider-security-id','status':'PAID','amount':'300.00','currency':'XOF','metadata':{'barka_reference':reference}}
    saspay.validate_paid_session('provider-security-id', payload)
    payload[field] = value
    with pytest.raises(saspay.SasPayError): saspay.validate_paid_session('provider-security-id', payload)
    assert not services.access_state('security-payment-abcdefgh')['allowed']
    with db.connect() as cx:
        assert cx.execute('SELECT status FROM payments WHERE reference=?',(reference,)).fetchone()['status'] == 'pending'


def test_provider_http_response_must_pass_quote_validation(env, monkeypatch):
    db, services, *_ = env
    from app import saspay
    reference = services.create_payment_record('security-http-abcdefgh', '24h')
    services.update_payment_created(reference, 'provider-http-id', 'https://checkout.example')
    payload = {'id':'provider-http-id','status':'PAID','amount':'1.00','currency':'XOF'}
    real = httpx.AsyncClient
    monkeypatch.setattr(saspay.httpx, 'AsyncClient', lambda **kw:real(transport=httpx.MockTransport(lambda req:httpx.Response(200,json=payload)), **kw))
    with pytest.raises(saspay.SasPayError): asyncio.run(saspay.get_payment('provider-http-id'))
    payload['amount'] = '300.00'
    assert asyncio.run(saspay.get_payment('provider-http-id'))['status'] == 'PAID'
