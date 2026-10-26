import pytest
from test_saspay_resellers_updates import env, client_for
from test_reseller_purchases import buyer, provider


def configure(c, now, **overrides):
    current=c.get('/v1/admin/pricing').json()
    promotions={k:{'amount':max(1,v//2),'starts_at':now+10,'ends_at':now+20} for k,v in current['base_prices'].items()}
    promotions.update(overrides)
    return c.put('/v1/admin/pricing',json={'revision':current['revision'],'prices':current['base_prices'],'promotions':promotions})


def test_all_promotions_start_end_and_preserve_normal_prices(env, monkeypatch):
    from app import pricing
    now=2_000_000_000;monkeypatch.setattr(pricing.time,'time',lambda:now)
    *_,main,_=env
    with client_for(main) as c:
        original=c.get('/v1/pricing').json()['prices']
        assert configure(c,now).status_code==200
        assert c.get('/v1/pricing').json()['prices']==original
        now+=10
        active=c.get('/v1/pricing').json()
        assert active['prices']=={k:v//2 for k,v in original.items()}
        assert active['base_prices']==original
        now+=10
        expired=c.get('/v1/pricing').json()
        assert expired['prices']==original and expired['revision']==active['revision']


@pytest.mark.parametrize('promo',[{'amount':300,'starts_at':10,'ends_at':20},{'amount':0,'starts_at':10,'ends_at':20},{'amount':100,'starts_at':20,'ends_at':10},{'amount':True,'starts_at':10,'ends_at':20}])
def test_invalid_promotion_is_atomic(env,promo):
    *_,main,_=env
    with client_for(main) as c:
        before=c.get('/v1/pricing').json()
        assert configure(c,0,**{'24h':promo}).status_code==422
        after=c.get('/v1/pricing').json()
        assert after['revision']==before['revision'] and after['prices']==before['prices']


def test_reseller_payment_keeps_promotion_quote_after_expiry(env, provider, monkeypatch):
    from app import pricing
    now=2_000_000_000;monkeypatch.setattr(pricing.time,'time',lambda:now)
    db,svc,_,_,_,main,_=env
    with client_for(main) as c:
        assert configure(c,now-10).status_code==200
        body=buyer(1)
        start=c.post('/v1/reseller-purchases/start',json={**body,'expected_amount':2500})
        assert start.status_code==200,start.text
        reference=start.json()['payment_reference'];assert provider[-1]['amount']==2500
        now+=11
        assert c.get('/v1/pricing').json()['prices']['reseller_1m']==5000
        retry=c.post('/v1/reseller-purchases/start',json={**body,'expected_amount':2500})
        assert retry.json()['payment_reference']==reference and len(provider)==1
        stale=c.post('/v1/reseller-purchases/start',json={**buyer(1),'expected_amount':2500})
        assert stale.status_code==409


def test_legacy_save_preserves_promotions_and_rejects_conflicting_base(env):
    *_,main,_=env
    with client_for(main) as c:
        assert configure(c,2_000_000_000).status_code==200
        current=c.get('/v1/pricing').json()
        body={'revision':current['revision'],'prices':current['base_prices']}
        assert c.put('/v1/admin/pricing',json=body).json()['promotions']==current['promotions']
        body['revision']+=1;body['prices']['24h']=100
        assert c.put('/v1/admin/pricing',json=body).status_code==422
