import importlib
import sqlite3

import pytest
from test_saspay_resellers_updates import env, client_for

ROOT = '/v1/admin/vpn'


def save(c, state, network='orange_bf', **changes):
    profile = next(p for p in state['profiles'] if p['network_id'] == network)
    profile = {**profile, **changes}
    response = c.put(ROOT+'/drafts', json={'revision': state['revision'], 'profile': profile})
    assert response.status_code == 200, response.text
    return response.json()


def test_save_is_persistent_private_and_does_not_publish(env):
    db, svc, _, _, _, main, _ = env
    svc.start_trial('draft-test-device-123')
    with client_for(main) as c:
        published = c.get(ROOT+'/profiles').json()
        catalog = c.get('/v1/vpn/catalog').json()
        state = c.get(ROOT+'/drafts').json()
        state = save(c, state, config={'uri': 'vless://test-draft'}, enabled=True)
        assert state['pending_count'] == 1
        assert c.get(ROOT+'/profiles').json() == published
        assert c.get('/v1/vpn/catalog').json() == catalog
        assert c.post('/v1/vpn/profile', json={'device_id':'draft-test-device-123','network_id':'orange_bf'}).status_code == 503
        db.init_db()
        from app import vpn_drafts
        importlib.reload(vpn_drafts)
        assert c.get(ROOT+'/drafts').json() == state
        assert 'no-store' in c.get(ROOT+'/drafts').headers['cache-control']
        assert c.get(ROOT+'/drafts', headers={'X-Admin-Token':'wrong'}).status_code == 401
        assert c.put(ROOT+'/drafts', headers={'X-Admin-Token':'wrong'}, json={'revision':state['revision'],'profile':state['profiles'][0]}).status_code == 401
        assert c.post(ROOT+'/apply', headers={'X-Admin-Token':'wrong'}, json={'revision':state['revision']}).status_code == 401


def test_apply_all_once_and_preserve_other_profile(env):
    _, svc, _, _, _, main, _ = env
    svc.start_trial('draft-publish-device-123')
    with client_for(main) as c:
        before={p['network_id']:p for p in c.get(ROOT+'/profiles').json()}
        state=c.get(ROOT+'/drafts').json()
        state=save(c,state,config={'uri':'vless://published'},enabled=True)
        state=save(c,state,'telecel_bf',config={'username':'new-user'},enabled=True,maintenance=True,priority=3)
        result=c.post(ROOT+'/apply',json={'revision':state['revision']})
        assert result.status_code==200,result.text
        result=result.json();assert result['applied_count']==2 and result['pending_count']==0
        after={p['network_id']:p for p in c.get(ROOT+'/profiles').json()}
        assert before['moov_bf']==after['moov_bf']
        for n in ['orange_bf','telecel_bf']:assert after[n]['version']==before[n]['version']+1
        assert after['orange_bf']['updated_at']==after['telecel_bf']['updated_at']
        assert after['orange_bf']['priority']==1 and after['telecel_bf']['priority']==3
        assert after['telecel_bf']['maintenance']
        delivered=c.post('/v1/vpn/profile',json={'device_id':'draft-publish-device-123','network_id':'orange_bf'})
        assert delivered.json()['config']=={'uri':'vless://published'}
        assert c.post(ROOT+'/apply',json={'revision':state['revision']}).status_code==409
        assert c.post(ROOT+'/apply',json={'revision':result['revision']}).json()['applied_count']==0
        assert c.get(ROOT+'/profiles').json()==list(after.values())


def test_stale_browser_cannot_overwrite_or_publish_unseen_draft(env):
    *_, main, _=env
    with client_for(main) as c:
        stale=c.get(ROOT+'/drafts').json()
        fresh=save(c,stale,config={'uri':'vless://one'})
        assert c.put(ROOT+'/drafts',json={'revision':stale['revision'],'profile':stale['profiles'][0]}).status_code==409
        assert c.post(ROOT+'/apply',json={'revision':stale['revision']}).status_code==409
        assert c.get(ROOT+'/drafts').json()==fresh


@pytest.mark.parametrize('changes',[
    {'priority':0}, {'priority':1001}, {'config':[]}, {'protocol':'UDP'},
    {'enabled':True,'config':{}},
])
def test_invalid_save_does_not_mutate(env,changes):
    *_,main,_=env
    with client_for(main) as c:
        state=c.get(ROOT+'/drafts').json()
        p=next(p for p in state['profiles'] if p['network_id']=='orange_bf')
        assert c.put(ROOT+'/drafts',json={'revision':state['revision'],'profile':{**p,**changes}}).status_code in (400,422)
        assert c.get(ROOT+'/drafts').json()==state


def test_changed_live_profile_requires_review_and_resave(env):
    *_,main,_=env
    with client_for(main) as c:
        state=save(c,c.get(ROOT+'/drafts').json(),config={'uri':'vless://draft'})
        live=next(p for p in state['profiles'] if p['network_id']=='orange_bf')
        assert c.post(ROOT+'/profiles',json={**live,'config':{'uri':'vless://other'}}).status_code==200
        state=c.get(ROOT+'/drafts').json()
        assert c.post(ROOT+'/apply',json={'revision':state['revision']}).status_code==409
        state=save(c,state)
        assert c.post(ROOT+'/apply',json={'revision':state['revision']}).status_code==200


def test_sql_failure_rolls_back_all_profiles_and_preserves_drafts(env):
    db,_,_,_,_,main,_=env
    with client_for(main) as c:
        before=c.get(ROOT+'/profiles').json()
        state=save(c,c.get(ROOT+'/drafts').json(),config={'uri':'new'})
        state=save(c,state,'telecel_bf',config={'username':'new'})
        with db.transaction() as cx:
            cx.execute("CREATE TRIGGER fail_second BEFORE UPDATE ON vpn_profiles WHEN NEW.network_id='telecel_bf' BEGIN SELECT RAISE(ABORT,'test failure'); END")
        with pytest.raises(sqlite3.IntegrityError):
            c.post(ROOT+'/apply',json={'revision':state['revision']})
        assert c.get(ROOT+'/profiles').json()==before
        assert c.get(ROOT+'/drafts').json()==state


def test_old_priority_is_editor_default_only_custom_value_preserved(env):
    db,_,_,_,_,main,_=env
    with db.transaction() as cx:
        cx.execute("UPDATE vpn_profiles SET priority=100 WHERE network_id='orange_bf'")
        cx.execute("UPDATE vpn_profiles SET priority=7 WHERE network_id='telecel_bf'")
    with client_for(main) as c:
        state=c.get(ROOT+'/drafts').json()
        priorities={p['network_id']:p['priority'] for p in state['profiles']}
        assert priorities=={'moov_bf':1,'orange_bf':1,'telecel_bf':7}
        assert next(p for p in c.get(ROOT+'/profiles').json() if p['network_id']=='orange_bf')['priority']==100
        state=save(c,state)
        assert c.post(ROOT+'/apply',json={'revision':state['revision']}).status_code==200
        assert next(p for p in c.get(ROOT+'/profiles').json() if p['network_id']=='orange_bf')['priority']==1
