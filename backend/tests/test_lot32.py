from test_saspay_resellers_updates import env,client_for

def test_reset_preserves_redeemed_and_unredeemed_codes(env):
    db,svc,rs,_,_,_,account=env;rid=account['id']
    active=rs.generate_reseller_subscription(rid,'1w');unused=rs.generate_reseller_subscription(rid,'2w')
    assert svc.redeem_activation_code('device-active-32',active)[0]
    with db.connect() as cx:
        before=[tuple(row) for row in cx.execute('SELECT code_hash,status,applied_from,applied_until,deleted_at FROM activation_codes WHERE created_by_reseller_id=? ORDER BY id',(rid,))]
    assert rs.adjust_reseller_quota(rid,reset=True)['used']==0
    with db.connect() as cx:
        after=[tuple(row) for row in cx.execute('SELECT code_hash,status,applied_from,applied_until,deleted_at FROM activation_codes WHERE created_by_reseller_id=? ORDER BY id',(rid,))]
    assert before==after
    assert svc.redeem_activation_code('device-unused-32',unused)[0]
    assert svc.redeem_activation_code('device-active-32',active)[0]

def test_share_link_requires_admin_and_is_independent(env):
    *_, main, _ = env
    with client_for(main) as c:
        assert c.put('/v1/admin/share-link',headers={'X-Admin-Token':'wrong'},json={'url':'https://example.org/a'}).status_code==401
        assert c.put('/v1/admin/share-link',json={'url':'http://example.org/a'}).status_code==422
        assert c.put('/v1/admin/share-link',json={'url':'https://example.org/a'}).status_code==200
        assert c.get('/v1/share-link').json()['url']=='https://example.org/a'
