import asyncio
import uuid
import httpx
import pytest
from test_reseller_purchases import buyer, provider
from test_saspay_resellers_updates import env, client_for
R='/v1/reseller-purchases/'


def test_cancel_unpaid_is_owned_idempotent_and_allows_new_plan(env,provider,monkeypatch):
    from app import saspay,reseller_checkout
    calls=[]
    async def get(ref):return {'status':'PENDING'}
    async def cancel(ref):calls.append(ref);return {'status':'CANCELLED'}
    monkeypatch.setattr(saspay,'get_payment',get);monkeypatch.setattr(reseller_checkout,'cancel_checkout',cancel)
    db,svc,rs,_,_,main,_=env;body=buyer()
    with client_for(main) as c:
        ref=c.post(R+'start',json=body).json()['payment_reference'];req={**body,'payment_reference':ref}
        assert c.post(R+'cancel',json={**req,'owner_key':'z'*64}).status_code==404 and calls==[]
        result=c.post(R+'cancel',json=req)
        assert result.status_code==200 and result.json()['status']=='failed' and result.json()['account'] is None
        assert result.headers['cache-control']=='no-store'
        assert c.post(R+'cancel',json=req).json()['status']=='failed' and len(calls)==1
        next_order=c.post(R+'start',json={**body,'request_id':uuid.uuid4().hex,'months':2}).json()
        assert next_order['payment_reference']!=ref and next_order['amount']==10000
        assert not svc.access_state(body['device_id'])['allowed']


def test_paid_return_never_cancels_or_double_credits(env,provider,monkeypatch):
    from app import reseller_checkout
    async def forbidden(ref):raise AssertionError('Paid checkout cannot be cancelled')
    monkeypatch.setattr(reseller_checkout,'cancel_checkout',forbidden)
    *_,main,_=env;body=buyer()
    with client_for(main) as c:
        ref=c.post(R+'start',json=body).json()['payment_reference'];req={**body,'payment_reference':ref}
        paid=c.post(R+'cancel',json=req).json()
        assert paid['status']=='paid' and paid['account']['password']
        assert c.post(R+'cancel',json=req).json()['account']['expires_at']==paid['account']['expires_at']


@pytest.mark.parametrize('remote',['PENDING','UNKNOWN'])
def test_uncertain_return_keeps_receipt_and_blocks_double_purchase(env,provider,monkeypatch,remote):
    from app import saspay,reseller_checkout
    async def get(ref):return {'status':remote}
    async def fail(ref):raise saspay.SasPayError('timeout')
    monkeypatch.setattr(saspay,'get_payment',get);monkeypatch.setattr(reseller_checkout,'cancel_checkout',fail)
    *_,main,_=env;body=buyer()
    with client_for(main) as c:
        ref=c.post(R+'start',json=body).json()['payment_reference']
        assert c.post(R+'cancel',json={**body,'payment_reference':ref}).status_code==503
        assert c.post(R+'start',json={**body,'request_id':uuid.uuid4().hex}).status_code==409
        assert c.post(R+'account',json=body).json()['account'] is None


def test_simultaneous_paid_webhook_wins(env,provider,monkeypatch):
    from app import saspay,reseller_checkout
    db,svc,rs,_,_,main,_=env;body=buyer();reference=None
    async def get(ref):return {'status':'PENDING'}
    async def cancel(ref):svc.mark_payment_paid(reference);return {'status':'CANCELLED'}
    monkeypatch.setattr(saspay,'get_payment',get);monkeypatch.setattr(reseller_checkout,'cancel_checkout',cancel)
    with client_for(main) as c:
        reference=c.post(R+'start',json=body).json()['payment_reference']
        result=c.post(R+'cancel',json={**body,'payment_reference':reference}).json()
        assert result['status']=='paid' and result['account']['password']
        before=result['account']['expires_at'];svc.mark_payment_paid(reference)
        assert rs.reseller_credentials(result['account']['id'])['expires_at']==before


@pytest.mark.parametrize('code,state',[(200,'CANCELLED'),(409,'PAID'),(409,'CANCELLED')])
def test_cancel_http_contract_and_conflict_readback(env,monkeypatch,code,state):
    from app import reseller_checkout,saspay
    requests=[];reads=[]
    def respond(req):requests.append(req);return httpx.Response(code,json={'status':state} if code==200 else {'code':'not_pending'})
    real=httpx.AsyncClient
    monkeypatch.setattr(reseller_checkout.httpx,'AsyncClient',lambda **kw:real(transport=httpx.MockTransport(respond),**kw))
    async def get(ref):reads.append(ref);return {'status':state}
    monkeypatch.setattr(saspay,'get_payment',get)
    assert asyncio.run(reseller_checkout.cancel_checkout('provider-id'))['status']==state
    assert len(requests)==1 and requests[0].method=='POST' and requests[0].content==b'{}'
    assert requests[0].url.path=='/api/v1/checkout-sessions/provider-id/cancel/'
    assert reads==(['provider-id'] if code==409 else [])
