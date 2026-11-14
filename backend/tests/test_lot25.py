import copy
from datetime import datetime,timezone
import pytest
from test_saspay_resellers_updates import env,client_for


def test_manual_renewal_resets_and_aligns_without_destroying_codes(env):
 db,svc,rs,_,_,_,a=env;rid=a['id']
 code=rs.generate_reseller_subscription(rid,'2w','stable-request')
 rs.adjust_reseller_quota(rid,limit=1)
 assert rs.reseller_quota(rid)['remaining']==0
 expiry=svc.now_ts()+60*86400
 rs.update_reseller_expiry(rid,datetime.fromtimestamp(expiry,timezone.utc))
 q=rs.reseller_quota(rid)
 assert (q['remaining'],q['used'],q['ends_at'])==(1,0,expiry)
 assert svc.redeem_activation_code('existing-client',code)[0]
 rs.generate_reseller_subscription(rid,'1w')
 # Saving the same expiration must not grant a second quota reset.
 rs.update_reseller_expiry(rid,datetime.fromtimestamp(expiry,timezone.utc))
 assert rs.reseller_quota(rid)['used']==1
 rs.renew_reseller_month(rid)
 assert rs.reseller_quota(rid)['used']==0
 assert rs.generate_reseller_subscription(rid,'2w','stable-request')==code
 assert rs.reseller_quota(rid)['used']==0


def test_deleted_expired_slots_and_test_exemption(env,monkeypatch):
 _,svc,rs,_,_,_,a=env;rid=a['id']
 rs.adjust_reseller_quota(rid,limit=2)
 one=rs.generate_reseller_subscription(rid,'1w');two=rs.generate_reseller_subscription(rid,'2w')
 with pytest.raises(PermissionError):rs.generate_reseller_subscription(rid,'1m')
 assert rs.delete_reseller_code(rid,one)[0]
 assert rs.reseller_quota(rid)['remaining']==1
 assert svc.redeem_activation_code('expires-client',two)[0]
 now=svc.now_ts();monkeypatch.setattr(rs,'now_ts',lambda:now+14*86400+1)
 assert rs.reseller_quota(rid)['remaining']==2
 rs.generate_reseller_test(rid)
 assert rs.reseller_quota(rid)['used']==0


def test_existing_date_mismatch_repairs_once_and_keeps_new_codes(env):
 db,svc,rs,_,_,_,a=env;rid=a['id'];now=svc.now_ts()
 old=rs.generate_reseller_subscription(rid,'1m')
 new=rs.generate_reseller_subscription(rid,'1w')
 with db.transaction() as cx:
  cx.execute('UPDATE reseller_accounts SET created_at=?,updated_at=?,expires_at=? WHERE id=?',(now-86400,now-30,now+60*86400,rid))
  cx.execute('UPDATE activation_codes SET created_at=? WHERE code_hash=?',(now-60,svc.code_hash(old)))
  cx.execute('UPDATE reseller_quota_periods SET ends_at=? WHERE reseller_id=?',(now+10*86400,rid))
 q=rs.reseller_quota(rid)
 assert q['ends_at']==now+60*86400 and q['used']==1
 rs.generate_reseller_subscription(rid,'1w')
 assert rs.reseller_quota(rid)['used']==2
 assert rs.reseller_quota(rid)['used']==2


def test_profile_selection_is_independent_and_published(env):
 db,svc,_,_,_,main,_=env
 from app import vpn_profiles
 with client_for(main) as c:
  state=c.get('/v1/admin/vpn/drafts').json();p=next(p for p in state['profiles'] if p['network_id']=='global_world')
  bank=p['config']['global_profiles'];original=copy.deepcopy(bank['fp_events'])
  p['config']=dict(bank['am2_d3'],global_profiles=bank,active_global_profile='am2_d3');p['enabled']=True
  r=c.put('/v1/admin/vpn/drafts',json={'revision':state['revision'],'profile':p});assert r.status_code==200,r.text
  r=c.post('/v1/admin/vpn/apply',json={'revision':r.json()['revision']});assert r.status_code==200,r.text
  config=next(p for p in vpn_profiles.list_admin_profiles() if p['network_id']=='global_world')['config']
  assert config['global_profiles']['fp_events']==original
  assert config['uuid']=='aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000' and config['mux'] is False
  db.init_db()
  assert next(p for p in vpn_profiles.list_admin_profiles() if p['network_id']=='global_world')['config']==config
  code=svc.issue_activation_code('profile-test','1w');assert svc.redeem_activation_code('profile-device',code)[0]
  assert 'global_profiles' not in vpn_profiles.get_profile_for_device('profile-device','global_world')['config']


@pytest.mark.parametrize('suffix',['GET / HTTP/1.1[crlf][crlf]','x: a[crlf]x: b[crlf][crlf]'])
def test_invalid_payload_rejected(suffix):
 from app.global_profiles import payload_headers
 with pytest.raises(ValueError):payload_headers('CONNECT [host]:[port] HTTP/1.1[crlf][crlf]'+suffix)


def test_latest_global_host_is_preserved_during_migration(env):
 import json
 from app.global_profiles import PRESETS
 db,_,_,_,_,_,_=env
 cfg=copy.deepcopy(PRESETS['am2_d3'])
 cfg['host']='ahmedhkk-708802142101.us-east1.run.app'
 cfg.pop('verify_cert_name')
 with db.transaction() as cx:
  cx.execute("DELETE FROM admin_state WHERE key='lot25_global_presets'")
  cx.execute("UPDATE vpn_profiles SET config_json=? WHERE network_id='global_world'",(json.dumps(cfg),))
 db.init_db()
 with db.connect() as cx:
  updated=json.loads(cx.execute("SELECT config_json FROM vpn_profiles WHERE network_id='global_world'").fetchone()[0])
 assert updated['active_global_profile']=='previous'
 assert updated['host']==cfg['host'] and updated['verify_cert_name']==cfg['host']
 assert updated['uuid']==cfg['uuid'] and updated['proxy_payload']==cfg['proxy_payload']
 assert updated['global_profiles']['fp_events']['host']==PRESETS['fp_events']['host']


def test_pending_legacy_draft_keeps_edits_and_gets_profile_selector(env):
 import json
 db,_,_,_,_,main,_=env
 with client_for(main) as c:
  state=c.get('/v1/admin/vpn/drafts').json()
  p=next(p for p in state['profiles'] if p['network_id']=='global_world')
  cfg=copy.deepcopy(p['config']['global_profiles']['am2_d3'])
  cfg['host']='pending.example';cfg.pop('verify_cert_name')
  p['config']=cfg
  with db.transaction() as cx:
   cx.execute("INSERT OR REPLACE INTO admin_state(key,value,updated_at) VALUES('vpn_profile_drafts',?,0)",(json.dumps({'global_world':{'profile':p,'base_version':0}}),))
  state=c.get('/v1/admin/vpn/drafts').json();p=next(p for p in state['profiles'] if p['network_id']=='global_world')
  assert p['config']['active_global_profile']=='draft' and p['pending']
  assert p['config']['host']=='pending.example' and p['config']['verify_cert_name']=='pending.example'
  assert 'fp_events' in p['config']['global_profiles']
  saved=c.put('/v1/admin/vpn/drafts',json={'revision':state['revision'],'profile':p})
  assert saved.status_code==200,saved.text
  assert c.post('/v1/admin/vpn/apply',json={'revision':saved.json()['revision']}).status_code==200


def test_reseller_api_repairs_stale_counter_and_restricts_new_durations(env):
 db,svc,rs,_,_,main,a=env
 # Existing 24-hour codes remain valid; only new reseller requests lose 24h.
 legacy=rs.generate_reseller_subscription(a['id'],'24h')
 assert rs.delete_reseller_code(a['id'],legacy)[0]
 rs.reseller_quota(a['id'])
 with db.transaction() as cx:
  cx.execute('UPDATE reseller_quota_periods SET used=30 WHERE reseller_id=?',(a['id'],))
 token=rs.login_reseller(a['username'],a['password'])['token']
 with client_for(main) as c:
  headers={'Authorization':'Bearer '+token}
  q=c.get('/v1/reseller/quota',headers=headers)
  assert q.status_code==200 and q.json()['remaining']==30
  rejected=c.post('/v1/reseller/codes/subscription',headers=headers,json={'plan_id':'24h'})
  assert rejected.status_code==422
  for index,plan in enumerate(('1w','2w','1m'),1):
   r=c.post('/v1/reseller/codes/subscription',headers=headers,json={'plan_id':plan})
   assert r.status_code==200,r.text
   assert r.json()['quota']['used']==index and r.json()['quota']['remaining']==30-index
  assert c.post('/v1/reseller/codes/test',headers=headers,json={'duration_hours':2}).status_code==200
  assert c.get('/v1/reseller/quota',headers=headers).json()['used']==3


def test_existing_24h_reseller_code_still_redeems(env):
 _,svc,rs,_,_,_,a=env
 legacy=rs.generate_reseller_subscription(a['id'],'24h')
 assert svc.redeem_activation_code('legacy-day-client',legacy)[0]
