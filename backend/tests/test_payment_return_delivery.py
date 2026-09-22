import pytest
from test_saspay_resellers_updates import env, client_for
from test_reseller_purchases import buyer, provider


@pytest.mark.parametrize('plan', ['24h', '1w', '2w', '1m'])
def test_return_delivers_one_subscription_only_to_its_buyer(env, provider, plan):
    db, svc, _, _, _, main, _ = env
    device = 'return-purchase-device-123'
    ref = svc.create_payment_record(device, plan)
    svc.update_payment_created(ref, 'provider-id', 'https://example.org/checkout')
    with client_for(main) as c:
        page = c.get('/payment-return', params={'reference': ref})
        assert page.status_code == 200
        code = svc.activation_code_for_payment(ref)
        assert code and code not in page.text
        assert 'intent://payment-return' in page.text and 'wa.me' not in page.text
        assert page.headers['cache-control'] == 'no-store'
        result = c.post('/v1/payments/status', json={'device_id': device, 'payment_reference': ref}).json()
        assert result['status'] == 'paid' and result['activation_code'] == code
        c.get('/payment-return', params={'reference': ref})
        with db.connect() as cx:
            rows = cx.execute('SELECT * FROM activation_codes WHERE payment_reference=?', (ref,)).fetchall()
            assert len(rows) == 1 and rows[0]['plan_id'] == plan
        assert c.post('/v1/payments/status', json={'device_id': 'another-device-123456', 'payment_reference': ref}).status_code == 404
        assert any(row['code'] == code for row in c.get('/v1/admin/codes').json())


@pytest.mark.parametrize('months', [1, 2])
def test_return_delivers_reseller_credentials_without_duplicate_account(env, provider, months):
    db, svc, _, _, _, main, _ = env
    body = buyer(months)
    with client_for(main) as c:
        ref = c.post('/v1/reseller-purchases/start', json=body).json()['payment_reference']
        page = c.get('/payment-return', params={'reference': ref})
        result = c.post('/v1/reseller-purchases/status', json={**body, 'payment_reference': ref}).json()
        assert result['status'] == 'paid'
        account = result['account']
        assert account['username'] and account['password'] and account['panel_url']
        assert account['password'] not in page.text and account['username'] not in page.text
        c.get('/payment-return', params={'reference': ref})
        again = c.post('/v1/reseller-purchases/status', json={**body, 'payment_reference': ref}).json()['account']
        assert again == account
        assert any(a['id'] == account['id'] for a in c.get('/v1/admin/resellers').json())
        assert svc.activation_code_for_payment(ref) is None


def test_return_does_not_trust_browser_payment_claim_or_reflect_html(env, provider, monkeypatch):
    from app import saspay
    async def pending(ref): return {'status': 'PENDING'}
    monkeypatch.setattr(saspay, 'get_payment', pending)
    _, svc, _, _, _, main, _ = env
    ref = svc.create_payment_record('return-pending-device-123', '24h')
    svc.update_payment_created(ref, 'provider-pending', 'https://example.org/checkout')
    with client_for(main) as c:
        c.get('/payment-return', params={'reference': ref, 'status': 'PAID'})
        assert svc.activation_code_for_payment(ref) is None
        evil = '\"><script>alert(1)</script>'
        assert evil not in c.get('/payment-return', params={'reference': evil}).text
