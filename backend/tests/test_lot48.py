from datetime import datetime, timezone
import importlib
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from test_panel_management import env


def test_redeem_25_expires_after_last_grant_without_changing_accounts(env, monkeypatch):
    db, svc, ops, rs, pm, a, b = env
    clock = int(datetime(2026, 11, 22, 12, tzinfo=timezone.utc).timestamp())
    for module in (svc, ops):
        monkeypatch.setattr(module, 'now_ts', lambda: clock)
    code = svc.issue_redeem_code('lot48-full25', 86400, 25)
    for i in range(25):
        assert svc.redeem_activation_code(f'lot48-person-{i:04}', code)[0]
    before = ops.list_redeem_codes()[0]
    assert (before['usage_count'], before['unfinished_usage_count'], before['waiting_usage_count']) == (25,25,0)
    with db.connect() as cx:
        accounts = [tuple(r) for r in cx.execute('SELECT * FROM devices ORDER BY device_id')]
        usages = [tuple(r) for r in cx.execute('SELECT * FROM redeem_usages ORDER BY id')]
    clock += 2*86400
    after = ops.list_redeem_codes()[0]
    assert after['status'] == 'expired' and after['all_users_finished']
    assert after['unfinished_usage_count'] == 0
    preview = pm.filtered_codes(pm.FilterRequest(kind='redeem',status='expired'))
    assert preview['count'] == 1
    with db.connect() as cx:
        assert accounts == [tuple(r) for r in cx.execute('SELECT * FROM devices ORDER BY device_id')]
        assert usages == [tuple(r) for r in cx.execute('SELECT * FROM redeem_usages ORDER BY id')]


def test_redeem_stacked_credit_is_explained_and_not_removed(env, monkeypatch):
    db, svc, ops, rs, pm, a, b = env
    clock = svc.now_ts()
    for module in (svc,ops): monkeypatch.setattr(module,'now_ts',lambda:clock)
    paid = svc.issue_activation_code('lot48-paid','1m')
    assert svc.redeem_activation_code('lot48-stacked-device',paid)[0]
    shared = svc.issue_redeem_code('lot48-shared',86400,1)
    assert svc.redeem_activation_code('lot48-stacked-device',shared)[0]
    clock += 2*86400
    result = ops.list_redeem_codes()[0]
    assert result['status']=='active' and result['waiting_usage_count']==1
    assert result['unfinished_usage_count']==1
    assert svc.access_state('lot48-stacked-device')['allowed']
    clock = int(datetime.fromisoformat(result['last_expires_at'].replace('Z','+00:00')).timestamp())
    assert ops.list_redeem_codes()[0]['status']=='expired'


def test_filtered_resellers_expired_only_and_auth(env):
    db,svc,ops,rs,pm,a,b=env
    ca=rs.generate_reseller_test(a['id']);cb=rs.generate_reseller_test(b['id'])
    with db.transaction() as cx: cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(svc.now_ts()-1,a['id']))
    from app import main
    importlib.reload(main)
    client=TestClient(main.app);headers={'X-Admin-Token':'test-admin-token'}
    body={'status':'expired','query':'vendeur'}
    for endpoint in ('filter-preview','delete-filtered'):
        assert client.post('/v1/admin/resellers/'+endpoint,json=body).status_code in (401,403)
    preview=client.post('/v1/admin/resellers/filter-preview',headers=headers,json=body)
    assert preview.status_code==200 and preview.json()['count']==1
    assert client.post('/v1/admin/resellers/delete-filtered',headers=headers,json=body).status_code==409
    result=client.post('/v1/admin/resellers/delete-filtered',headers=headers,json={**body,'fingerprint':preview.json()['fingerprint']})
    assert result.status_code==200 and result.json()['deleted']==1
    assert [r['id'] for r in rs.list_resellers()]==[b['id']]
    assert rs.list_reseller_codes(b['id'])[0]['code']==cb
    assert rs.list_reseller_codes(a['id'])==[]


@pytest.mark.parametrize('change',['renew','new_code','freeze'])
def test_changed_batch_cancels_all_deletions(env,change):
    db,svc,ops,rs,pm,a,b=env
    body=pm.ResellerFilterRequest(status='active')
    preview=pm.filtered_resellers(body)
    if change=='renew':rs.renew_reseller_month(a['id'])
    elif change=='new_code':rs.generate_reseller_test(a['id'])
    else:rs.freeze_reseller(a['id'])
    with pytest.raises(HTTPException) as err:
        pm.filtered_resellers(body.model_copy(update={'fingerprint':preview['fingerprint']}),delete=True)
    assert err.value.status_code==409
    assert len(rs.list_resellers())==2


def test_batch_rollback_and_no_unfiltered_delete(env,monkeypatch):
    db,svc,ops,rs,pm,a,b=env
    with pytest.raises(HTTPException):pm.filtered_resellers(pm.ResellerFilterRequest(status='all'))
    body=pm.ResellerFilterRequest(status='all',query='vendeur')
    preview=pm.filtered_resellers(body);assert preview['count']==2
    original=pm.delete_reseller;calls=[]
    def fail_second(rid,**kwargs):
        calls.append(rid)
        if len(calls)==2:raise RuntimeError('test rollback')
        original(rid,**kwargs)
    monkeypatch.setattr(pm,'delete_reseller',fail_second)
    with pytest.raises(RuntimeError):pm.filtered_resellers(body.model_copy(update={'fingerprint':preview['fingerprint']}),delete=True)
    assert len(rs.list_resellers())==2


@pytest.mark.parametrize('months',[1,3,12])
def test_extend_months_resets_quota_once_preserves_codes_and_others(env,months):
    db,svc,ops,rs,pm,a,b=env
    code=rs.generate_reseller_subscription(a['id'],'1m')
    rs.generate_reseller_subscription(b['id'],'1m')
    rs.adjust_reseller_quota(a['id'],limit=7)
    old=datetime.fromisoformat(a['expires_at'].replace('Z','+00:00'))
    before=rs.list_reseller_codes(a['id'])
    result=rs.renew_reseller_month(a['id'],months,old)
    assert result['quota_reset'] and result['quota']['used']==0 and result['quota']['remaining']==7
    assert result['quota']['limit']==7
    assert rs.list_reseller_codes(a['id'])==before
    assert rs.reseller_quota(b['id'])['used']==1
    rs.generate_reseller_subscription(a['id'],'24h')
    with pytest.raises(ValueError):rs.renew_reseller_month(a['id'],months,old)
    assert rs.reseller_quota(a['id'])['used']==1
    new=datetime.fromisoformat(result['expires_at'].replace('Z','+00:00'))
    assert (new.year-old.year)*12+new.month-old.month==months
    same=rs.update_reseller_expiry(a['id'],new)
    assert same['quota_reset'] is False and same['quota']['used']==1
    assert svc.redeem_activation_code('lot48-existing-code',code)[0]


def test_expiry_extension_reports_real_reset_and_api_validates_months(env):
    db,svc,ops,rs,pm,a,b=env
    from app import main
    importlib.reload(main);client=TestClient(main.app);headers={'X-Admin-Token':'test-admin-token'}
    route=f'/v1/admin/resellers/{a["id"]}'
    for months in (0,121,True,1.5,'2'):
        assert client.post(route+'/renew',headers=headers,json={'confirmation':'oui','months':months}).status_code==422
    assert client.post(route+'/renew',headers=headers,json={'confirmation':'non'}).status_code==422
    rs.generate_reseller_subscription(a['id'],'1w')
    expiry=datetime.fromisoformat(a['expires_at'].replace('Z','+00:00')).timestamp()+31*86400
    reply=client.post(route+'/expiry',headers=headers,json={'expires_at':datetime.fromtimestamp(expiry,timezone.utc).isoformat()})
    assert reply.status_code==200,reply.text
    assert reply.json()['quota_reset'] and reply.json()['quota']['used']==0
    assert reply.json()['quota']['remaining']==30
