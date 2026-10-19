import hashlib,io,zipfile
from datetime import datetime,timezone
import pytest
from test_saspay_resellers_updates import env,client_for

@pytest.mark.parametrize('plan,duration',[('test_2h',7200),('24h',86400),('1w',604800),('2w',1209600),('1m',2592000)])
@pytest.mark.parametrize('poll',[False,True])
def test_all_durations_preserved(env,monkeypatch,plan,duration,poll):
    db,svc,rs,ops,_,_,a=env;clock=svc.now_ts()
    for module in (svc,rs,ops):monkeypatch.setattr(module,'now_ts',lambda:clock)
    code=rs.generate_reseller_test(a['id']) if plan=='test_2h' else rs.generate_reseller_subscription(a['id'],plan)
    device='pause-device-113-'+plan
    assert svc.redeem_activation_code(device,code)[2]['remaining_seconds']==duration
    with db.transaction() as cx:cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(clock+60,a['id']))
    assert svc.access_state(device)['remaining_seconds']==duration
    clock+=60+90*86400
    if poll:
        assert not svc.access_state(device)['allowed']
        for _ in range(3):
            item=next(x for x in rs.list_reseller_codes(a['id']) if x['code']==code)
            assert item['status']=='frozen' and item['remaining_seconds']==duration-60
    rs.renew_reseller_month(a['id'])
    assert svc.access_state(device)['remaining_seconds']==duration-60
    rs.renew_reseller_month(a['id'])
    assert svc.access_state(device)['remaining_seconds']==duration-60
    clock+=30
    assert svc.access_state(device)['remaining_seconds']==duration-90


def test_stacked_codes_survive_restart(env,monkeypatch):
    db,svc,rs,ops,_,_,a=env;clock=svc.now_ts()
    for module in (svc,rs,ops):monkeypatch.setattr(module,'now_ts',lambda:clock)
    device='stacked-paused-device'
    for _ in range(2):assert svc.redeem_activation_code(device,rs.generate_reseller_test(a['id']))[0]
    with db.transaction() as cx:cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(clock+10,a['id']))
    clock+=10000;assert not svc.access_state(device)['allowed'];db.init_db();clock+=10000
    rs.update_reseller_expiry(a['id'],datetime.fromtimestamp(clock+86400,timezone.utc))
    assert svc.access_state(device)['remaining_seconds']==14400-10

@pytest.mark.parametrize('remove_panel',[False,True])
def test_delete_preserves_other_credit(env,monkeypatch,remove_panel):
    db,svc,rs,ops,_,_,a=env;clock=svc.now_ts()
    for module in (svc,rs,ops):monkeypatch.setattr(module,'now_ts',lambda:clock)
    device='delete-paused-device';code=rs.generate_reseller_test(a['id'])
    assert svc.redeem_activation_code(device,code)[0]
    assert svc.redeem_activation_code(device,svc.issue_activation_code('MANUAL:AFTER','24h'))[0]
    with db.transaction() as cx:cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?',(clock+60,a['id']))
    clock+=30*86400
    if remove_panel:rs.delete_reseller(a['id'])
    else:assert ops.delete_activation_code(code)[0]
    assert svc.access_state(device)['remaining_seconds']==86400


def apk_bytes(size):
    buf=io.BytesIO()
    with zipfile.ZipFile(buf,'w') as z:z.writestr('AndroidManifest.xml',b'manifest');z.writestr('classes.dex',bytes(size))
    return buf.getvalue()


def test_chunk_upload_resume_immutable_limit(env):
    *_,main,_=env;payload=apk_bytes(1100000)
    with client_for(main) as c:
        start=c.post('/v1/admin/app-update/uploads',json={'size':len(payload)}).json()
        root='/v1/admin/app-update/uploads/'+start['upload_id'];chunk=start['chunk_bytes']
        assert c.put(root+'?offset=1',content=b'x').status_code==409
        assert c.post(root+'/finish').status_code==409
        assert c.put(root+'?offset=0',content=bytes(chunk+1)).status_code==413
        for offset in range(0,len(payload),chunk):
            result=c.put(root+'?offset='+str(offset),content=payload[offset:offset+chunk]);assert result.status_code==200,result.text
            assert c.get(root).json()['offset']==min(len(payload),offset+chunk)
        result=c.post(root+'/finish').json();assert c.post(root+'/finish').json()==result
        assert result['sha256']==hashlib.sha256(payload).hexdigest()
        url=result['apk_url'];response=c.get(url);assert response.content==payload
        resumed=c.get(url,headers={'Range':'bytes=765432-','If-Range':response.headers['etag']})
        assert resumed.status_code==206 and resumed.content==payload[765432:]
        assert c.get(url,headers={'Range':'bytes=20-','If-Range':'"changed"'}).status_code==200
        second=c.put('/v1/admin/app-update/apk',content=apk_bytes(64)).json()
        assert second['apk_url']!=url and c.get(url).content==payload
        assert c.post('/v1/admin/app-update/uploads',json={'size':300*1024*1024}).status_code==200
        assert c.post('/v1/admin/app-update/uploads',json={'size':300*1024*1024+1}).status_code==422
        assert c.get(root,headers={'X-Admin-Token':'wrong'}).status_code==401


def test_tls_reassignment_and_internal_update(env):
    _,svc,_,_,_,main,_=env;device='tls-profile-device';svc.start_trial(device)
    config={'host':'ssh.example.org','port':443,'sni':'ssh.example.org','username':'test','password':'test','udpgw_port':7300}
    with client_for(main) as c:
        state=c.get('/v1/admin/vpn/drafts').json();profile=next(p for p in state['profiles'] if p['network_id']=='orange_bf')
        profile.update(protocol='SSH_TLS',enabled=True,config=config)
        draft=c.put('/v1/admin/vpn/drafts',json={'revision':state['revision'],'profile':profile});assert draft.status_code==200,draft.text
        assert c.post('/v1/admin/vpn/apply',json={'revision':draft.json()['revision']}).status_code==200
        received=c.post('/v1/vpn/profile',json={'device_id':device,'network_id':'orange_bf'}).json()
        assert received['protocol']=='SSH_TLS' and received['config']==config
        profile['config']={**config,'allow_insecure':True};assert c.post('/v1/admin/vpn/profiles',json=profile).status_code==400
        update=dict(enabled=True,latest_version_code=114,latest_version_name='test',apk_url='https://example.org/app.apk',download_in_app=True)
        assert c.post('/v1/admin/app-update',json=update).status_code==200
        assert c.get('/v1/app/update?version_code=113').json()['download_in_app'] is True
