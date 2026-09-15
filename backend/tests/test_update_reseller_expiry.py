from datetime import datetime, timedelta, timezone
from pathlib import Path
import importlib.util
import shutil
import pytest
from test_saspay_resellers_updates import env, client_for


def expire(db, a, timestamp):
    with db.transaction() as cx:
        cx.execute('UPDATE reseller_accounts SET expires_at=? WHERE id=?', (timestamp, a['id']))


@pytest.mark.parametrize('renewal', ['month', 'date'])
def test_expiry_renewal_keeps_original_code_dates(env, monkeypatch, renewal):
    db, svc, rs, ops, _, main, a = env
    clock = svc.now_ts()
    for module in (svc, rs, ops): monkeypatch.setattr(module, 'now_ts', lambda: clock)
    used = rs.generate_reseller_subscription(a['id'], '24h')
    unused = rs.generate_reseller_test(a['id'])
    device = 'device-expiry-active-123'
    assert svc.redeem_activation_code(device, used)[0]
    original_end = svc.access_state(device)['expires_at']
    token = rs.login_reseller(a['username'], a['password'])['token']
    expire(db, a, clock+60)
    assert svc.access_state(device)['remaining_seconds'] == 60
    clock += 60
    assert not svc.access_state(device)['allowed']
    assert not svc.redeem_activation_code(device, used)[0]
    assert not svc.redeem_activation_code('device-unused-12345', unused)[0]
    with pytest.raises(ValueError, match='réabonner'): rs.login_reseller(a['username'], a['password'])
    with pytest.raises(ValueError, match='Identifiants'): rs.login_reseller(a['username'], 'wrong-password-12345')
    with client_for(main) as c:
        denied = c.post('/v1/reseller/codes/test', headers={'Authorization':'Bearer '+token})
        assert denied.status_code == 401 and 'réabonner' in denied.json()['detail']
        codes = c.get(f"/v1/admin/resellers/{a['id']}/codes")
        assert codes.status_code == 200 and all(x['status']=='frozen' for x in codes.json())
    clock += 3600
    if renewal == 'month': rs.renew_reseller_month(a['id'])
    else: rs.update_reseller_expiry(a['id'], datetime.fromtimestamp(clock+86400,timezone.utc))
    access = svc.access_state(device)
    assert access['allowed'] and access['expires_at']==original_end and access['remaining_seconds']==86400-3660
    assert svc.redeem_activation_code('device-unused-12345', unused)[0]
    assert rs.login_reseller(a['username'],a['password'])['token'] and rs.generate_reseller_test(a['id'])
    with pytest.raises(Exception): rs.require_reseller('Bearer '+token)


def test_expired_deleted_revoked_codes_are_not_restored(env):
    db, svc, rs, ops, _, _, a = env
    expired, revoked, deleted = [rs.generate_reseller_test(a['id']) for _ in range(3)]
    device = 'device-ended-code-12345'
    assert svc.redeem_activation_code(device,expired)[0]
    ops.revoke_activation_code(revoked); ops.delete_activation_code(deleted)
    with db.transaction() as cx:
        cx.execute('UPDATE devices SET subscription_expires_at=1 WHERE device_id=?',(device,))
        cx.execute('UPDATE activation_codes SET applied_until=1 WHERE redeemed_device_id=?',(device,))
    expire(db,a,svc.now_ts()-1); rs.renew_reseller_month(a['id'])
    assert not svc.access_state(device)['allowed']
    for code in (revoked,deleted): assert not svc.redeem_activation_code('device-other-12345',code)[0]
    assert next(c for c in rs.list_reseller_codes(a['id']) if c['code']==expired)['status']=='expired'


def test_other_sources_and_credit_intervals_are_preserved(env, monkeypatch):
    db, svc, rs, _, _, _, a = env
    b=rs.create_reseller('other-seller', datetime.now(timezone.utc)+timedelta(days=30))
    clock=svc.now_ts(); monkeypatch.setattr(svc,'now_ts',lambda:clock)
    codes=[svc.issue_activation_code('MANUAL:BEFORE','24h'),rs.generate_reseller_test(a['id']),rs.generate_reseller_subscription(b['id'],'24h')]
    device='device-mixed-grants-12345'
    for code in codes: assert svc.redeem_activation_code(device,code)[0]
    expire(db,a,clock)
    assert svc.access_state(device)['remaining_seconds']==86400
    clock+=86400; assert not svc.access_state(device)['allowed']
    clock+=7200; assert svc.access_state(device)['allowed'] and svc.access_state(device)['remaining_seconds']==86400
    for source in ['MANUAL:OTHER','PAYMENT:OTHER']:
        code=svc.issue_activation_code(source,'24h')
        assert svc.redeem_activation_code('device-'+source.replace(':','-'),code)[2]['allowed']
    assert svc.start_trial('device-independent-trial')[0]['allowed']


def publication(url, mandatory=False):
    return dict(enabled=True,mandatory=mandatory,latest_version_code=111,latest_version_name='2.0.4.2',apk_url=url,message='Nouvelle version')


@pytest.mark.parametrize('mandatory',[False,True])
def test_url_change_without_version_change(env,mandatory):
    *_, main, account=env
    with client_for(main) as c:
        first=c.post('/v1/admin/app-update',json=publication('https://example.org/a.apk',mandatory))
        assert first.status_code==200
        url='https://another.example.org/page?build=111&channel=stable#download'
        second=c.post('/v1/admin/app-update',json=publication(url,mandatory))
        assert second.status_code==200 and second.json()['updated_at']!=first.json()['updated_at']
        assert c.get('/v1/admin/app-update').json()['apk_url']==url
        response=c.get('/v1/app/update?version_code=110')
        assert 'no-store' in response.headers['cache-control']
        assert response.json()['apk_url']==url and response.json()['force_update']==mandatory
        assert not c.get('/v1/app/update?version_code=111').json()['update_available']
        c.delete('/v1/admin/app-update')
        assert not c.get('/v1/app/update?version_code=110').json()['update_available']


@pytest.mark.parametrize('url',['','http://example.org/a','javascript:alert(1)','https://','https://user:secret@example.org/a','https://example.org:99999/a','https://example.org/a b','https://example.org/\nx','https://example.org\\@evil.org'])
def test_invalid_url_does_not_change_publication(env,url):
    *_,main,account=env
    with client_for(main) as c:
        before=c.get('/v1/admin/app-update').json()
        assert c.post('/v1/admin/app-update',json=publication(url)).status_code==400
        assert c.get('/v1/admin/app-update').json()==before


def test_upload_returns_actual_download_url(env):
    *_,main,account=env
    with client_for(main) as c:
        payload=b'PK\x03\x04test-upload'
        assert c.put('/v1/admin/app-update/apk',content=payload,headers={'X-Admin-Token':'wrong'}).status_code==401
        result=c.put('/v1/admin/app-update/apk',content=payload)
        assert result.json()['apk_url']==main.settings.public_base_url+'/downloads/BarkaTunnel.apk'
        assert c.get('/downloads/BarkaTunnel.apk').content==payload


def test_deploy_guard_rejects_unknown_live_changes_and_payment_changes(tmp_path):
    backend=Path(__file__).resolve().parents[1]
    spec=importlib.util.spec_from_file_location('verify_deploy_expiry',backend/'verify_deploy.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    live=tmp_path/'live';stage=tmp_path/'stage'
    for target in (live,stage):
        shutil.copytree(backend/'app',target/'app');shutil.copy(backend/'deploy_baseline.json',target/'deploy_baseline.json')
    module.verify(live,stage)
    with (live/'app/resellers.py').open('a') as f:f.write('\n# unknown live change\n')
    with pytest.raises(SystemExit,match='source VPS modifiée'):module.verify(live,stage)
    shutil.copy(stage/'app/resellers.py',live/'app/resellers.py')
    for target in (live,stage):
        with (target/'app/saspay.py').open('a') as f:f.write('\n# altered payment integration\n')
    with pytest.raises(SystemExit,match='intégration SasPay'):module.verify(live,stage)
