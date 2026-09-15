import uuid
from datetime import datetime, timezone
import pytest
from test_saspay_resellers_updates import env, client_for


def buyer(months=1):
    return dict(device_id='purchase-device-123456',owner_key=uuid.uuid4().hex+uuid.uuid4().hex,request_id=uuid.uuid4().hex,months=months)

@pytest.fixture
def provider(env,monkeypatch):
    from app import saspay
    calls=[]
    async def create(**kw):
        calls.append(kw)
        return dict(id=uuid.uuid4().hex,checkout_url='https://example.org/checkout')
    async def get(ref):return dict(id=ref,status='PAID')
    monkeypatch.setattr(saspay,'create_payment',create);monkeypatch.setattr(saspay,'get_payment',get)
    return calls

@pytest.mark.parametrize('months',[1,2])
def test_purchase_paid_idempotent_and_private(env,provider,months):
    db,svc,rs,_,_,main,_=env;body=buyer(months)
    with client_for(main) as c:
        route='/v1/reseller-purchases/'
        first=c.post(route+'start',json=body);assert first.status_code==200,first.text
        ref=first.json()['payment_reference']
        assert c.post(route+'start',json=body).json()['payment_reference']==ref
        assert len(provider)==1 and provider[0]['amount']==5000*months
        assert c.post(route+'account',json=body).json()['account'] is None
        bad={**body,'owner_key':'x'*64,'payment_reference':ref}
        assert c.post(route+'status',json=bad).status_code==404
        result=c.post(route+'status',json={**body,'payment_reference':ref})
        assert result.status_code==200,result.text
        account=result.json()['account'];assert account['password']
        assert result.headers['cache-control']=='no-store'
        before=account['expires_at'];svc.mark_payment_paid(ref);svc.mark_payment_paid(ref)
        svc.mark_payment_failed(ref)
        assert svc.get_payment_for_device(ref,body['device_id'])['status']=='paid'
        assert svc.activation_code_for_payment(ref) is None
        assert rs.reseller_credentials(account['id'])['expires_at']==before
        assert rs.login_reseller(account['username'],account['password'])
        assert any(a['id']==account['id'] for a in c.get('/v1/admin/resellers').json())
        with db.connect() as cx:
            assert cx.execute('SELECT COUNT(*) FROM activation_codes WHERE payment_reference=?',(ref,)).fetchone()[0]==0
            assert cx.execute('SELECT COUNT(*) FROM reseller_purchases WHERE reference=?',(ref,)).fetchone()[0]==1
        assert not svc.access_state(body['device_id'])['allowed']
        assert c.post(route+'account',json=bad).json()['account'] is None


def test_renew_expired_preserves_account_codes_and_dates(env,provider):
    db,svc,rs,_,_,main,a=env;body=buyer(2)
    code=rs.generate_reseller_subscription(a['id'],'24h')
    assert svc.redeem_activation_code('code-buyer-device-123',code)[0]
    with db.connect() as cx:before=dict(cx.execute('SELECT * FROM activation_codes WHERE redeemed_device_id=?',('code-buyer-device-123',)).fetchone())
    with db.transaction() as cx:cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(svc.now_ts()-50,a['id']))
    assert not svc.access_state('code-buyer-device-123')['allowed']
    with client_for(main) as c:
        route='/v1/reseller-purchases/'
        restored=c.post(route+'restore',json={**body,'username':a['username'],'password':a['password']});assert restored.status_code==200,restored.text
        ref=c.post(route+'start',json=body).json()['payment_reference']
        result=c.post(route+'status',json={**body,'payment_reference':ref}).json()['account']
        assert result['id']==a['id'] and result['password']==a['password'] and result['status']=='active'
        assert svc.access_state('code-buyer-device-123')['allowed']
        with db.connect() as cx:assert dict(cx.execute('SELECT * FROM activation_codes WHERE id=?',(before['id'],)).fetchone())==before


def test_failed_and_pending_never_deliver_account(env,provider,monkeypatch):
    *_,main,a=env
    from app import saspay
    for state in ['PENDING','CANCELLED','EXPIRED']:
        async def get(ref):return {'status':state}
        monkeypatch.setattr(saspay,'get_payment',get)
        body=buyer()
        with client_for(main) as c:
            ref=c.post('/v1/reseller-purchases/start',json=body).json()['payment_reference']
            response=c.post('/v1/reseller-purchases/status',json={**body,'payment_reference':ref}).json()
            assert response['account'] is None and response['status']!='paid'


def test_calendar_and_deleted_or_manual_frozen(env,provider):
    from app.reseller_purchases import add_months
    start=int(datetime(2028,1,31,12,tzinfo=timezone.utc).timestamp())
    assert datetime.fromtimestamp(add_months(start,1),timezone.utc).day==29
    assert datetime.fromtimestamp(add_months(start,2),timezone.utc).month==3
    db,svc,rs,_,_,main,a=env;body=buyer()
    with client_for(main) as c:
        r='/v1/reseller-purchases/'
        assert c.post(r+'restore',json={**body,'username':a['username'],'password':'wrong-password'}).status_code==401
        assert c.post(r+'restore',json={**body,'username':a['username'],'password':a['password']}).status_code==200
        rs.freeze_reseller(a['id'])
        assert c.post(r+'start',json=body).status_code==403
        rs.reactivate_reseller(a['id'])
        ref=c.post(r+'start',json=body).json()['payment_reference']
        rs.delete_reseller(a['id'])
        response=c.post(r+'status',json={**body,'payment_reference':ref}).json()
        assert response['status']=='paid' and response['account'] is None
        assert 'supprimé' in response['message']


def test_restore_cannot_switch_pending_target(env,provider):
    db,svc,rs,_,_,main,a=env;body=buyer()
    with client_for(main) as c:
        c.post('/v1/reseller-purchases/start',json=body)
        result=c.post('/v1/reseller-purchases/restore',json={**body,'username':a['username'],'password':a['password']})
        assert result.status_code==409


def test_checkout_creation_failure_allows_explicit_retry_without_account(env,provider,monkeypatch):
    from app import saspay
    *_,main,a=env;body=buyer()
    async def fail(**kw):raise saspay.SasPayError('Timeout')
    monkeypatch.setattr(saspay,'create_payment',fail)
    with client_for(main) as c:
        response=c.post('/v1/reseller-purchases/start',json=body).json()
        assert response['status']=='error' and response['checkout_url'] is None
        assert c.post('/v1/reseller-purchases/account',json=body).json()['account'] is None
        retry={**body,'request_id':uuid.uuid4().hex}
        second=c.post('/v1/reseller-purchases/start',json=retry)
        assert second.status_code==200 and second.json()['payment_reference']!=response['payment_reference']
