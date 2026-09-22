import importlib
import pytest
from test_saspay_resellers_updates import env, client_for
from test_reseller_purchases import buyer, provider


def set_prices(client, **changes):
    current=client.get('/v1/admin/pricing').json()
    response=client.put('/v1/admin/pricing',json={'revision':current['revision'],'prices':{**current['prices'],**changes}})
    assert response.status_code==200,response.text
    return response.json()


def test_catalog_admin_only_save_and_persistent_six_prices(env):
    db,_,_,_,_,main,_=env
    with client_for(main) as c:
        initial=c.get('/v1/pricing');assert initial.headers['cache-control'].startswith('no-store')
        assert len(initial.json()['prices'])==6 and initial.json()['prices']['24h']==300
        saved=set_prices(c, **{'24h':450,'1w':900,'2w':1400,'1m':3000,'reseller_1m':6500,'reseller_2m':12500})
        assert c.get('/v1/pricing').json()==saved
        assert c.get('/v1/admin/pricing',headers={'X-Admin-Token':'invalid'}).status_code==401
        assert c.put('/v1/admin/pricing',headers={'X-Admin-Token':'invalid'},json={'revision':saved['revision'],'prices':saved['prices']}).status_code==401
        assert c.put('/v1/admin/pricing',json={'revision':0,'prices':saved['prices']}).status_code==409
        db.init_db()
        from app import pricing
        importlib.reload(pricing)
        assert pricing.get_catalog()==saved
        plans={p['id']:p for p in c.get('/v1/plans').json()}
        assert plans['24h']['amount']==450 and plans['1m']['duration_seconds']==30*86400
        assert c.get('/v1/pricing').json()==saved


@pytest.mark.parametrize('invalid',[0,-1,1.5,True,'500',10_000_001,None])
def test_invalid_price_cannot_partially_change_catalog(env,invalid):
    *_,main,_=env
    with client_for(main) as c:
        before=c.get('/v1/pricing').json()
        values={**before['prices'],'24h':invalid,'1m':9999}
        assert c.put('/v1/admin/pricing',json={'revision':0,'prices':values}).status_code==422
        assert c.get('/v1/pricing').json()==before


@pytest.mark.parametrize('key',['missing','extra'])
def test_catalog_requires_exact_offers(env,key):
    *_,main,_=env
    with client_for(main) as c:
        values=c.get('/v1/pricing').json()['prices']
        if key=='missing':values.pop('24h')
        else:values['unlimited']=200
        assert c.put('/v1/admin/pricing',json={'revision':0,'prices':values}).status_code==422


@pytest.mark.parametrize('plan',['24h','1w','2w','1m'])
def test_subscription_uses_saved_price_and_delivers_original_duration(env,provider,monkeypatch,plan):
    db,svc,_,_,_,main,_=env
    from app import saspay
    monkeypatch.setattr(main,'create_payment',saspay.create_payment)
    with client_for(main) as c:
        set_prices(c,**{plan:1700})
        body={'device_id':'pricing-subscription-device','plan_id':plan,'expected_amount':1700}
        start=c.post('/v1/payments/start',json=body);assert start.status_code==200,start.text
        ref=start.json()['payment_reference']
        assert start.json()['amount']==1700 and provider[-1]['amount']==1700
        set_prices(c,**{plan:2200})
        assert c.post('/v1/payments/start',json=body).json()['payment_reference']==ref
        assert len(provider)==1
        row=svc.get_payment_for_device(ref,body['device_id']);assert row['amount']==1700
        code=svc.mark_payment_paid(ref)
        assert code==svc.mark_payment_paid(ref)
        with db.connect() as cx:
            stored=cx.execute('SELECT * FROM activation_codes WHERE payment_reference=?',(ref,)).fetchone()
            assert stored['plan_id']==plan
        stale=c.post('/v1/payments/start',json={**body,'device_id':'pricing-other-device-123'})
        assert stale.status_code==409 and stale.json()['detail']=='PRICING_CHANGED'
        assert len(provider)==1


@pytest.mark.parametrize('months',[1,2])
def test_reseller_quote_stays_fixed_and_retry_never_duplicates(env,provider,months):
    db,svc,_,_,_,main,_=env;body=buyer(months)
    with client_for(main) as c:
        key=f'reseller_{months}m';set_prices(c,**{key:7654})
        route='/v1/reseller-purchases/'
        start=c.post(route+'start',json={**body,'expected_amount':7654});assert start.status_code==200,start.text
        ref=start.json()['payment_reference'];assert provider[-1]['amount']==7654
        set_prices(c,**{key:9000})
        repeated=c.post(route+'start',json={**body,'expected_amount':7654}).json()
        assert repeated['payment_reference']==ref and repeated['amount']==7654 and len(provider)==1
        result=c.post(route+'status',json={**body,'payment_reference':ref}).json()
        assert result['status']=='paid' and result['account']['password']
        assert svc.get_payment_for_device(ref,body['device_id'])['amount']==7654
        other=buyer(months)
        stale=c.post(route+'start',json={**other,'expected_amount':7654})
        assert stale.status_code==409 and len(provider)==1
        fresh=c.post(route+'start',json={**other,'expected_amount':9000})
        assert fresh.status_code==200 and provider[-1]['amount']==9000


def test_price_change_while_provider_is_creating_cannot_change_quote(env,monkeypatch):
    _,svc,_,_,_,main,_=env
    from app import pricing
    from fastapi import Response
    async def create(**kw):
        current=pricing.get_catalog()
        pricing.save_pricing(pricing.PricingUpdate(revision=current['revision'],prices={**current['prices'],'24h':999}),Response())
        assert kw['amount']==300
        return {'id':'concurrent-price-provider','checkout_url':'https://example.org/checkout'}
    monkeypatch.setattr(main,'create_payment',create)
    with client_for(main) as c:
        response=c.post('/v1/payments/start',json={'device_id':'concurrent-pricing-device','plan_id':'24h','expected_amount':300})
        assert response.status_code==200,response.text
        assert response.json()['amount']==300
        assert svc.get_payment_for_device(response.json()['payment_reference'],'concurrent-pricing-device')['amount']==300
        assert c.get('/v1/pricing').json()['prices']['24h']==999
