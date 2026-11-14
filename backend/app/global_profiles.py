"""Independent GLOBAL profiles and strict single-request CONNECT payloads."""
import copy
import json
import re
HOST='vless-pass-1h-1794589051-ndsghtfh6q-ew.a.run.app'
COMMON=dict(address='alt13.yt3.ggpht.com',port=443,network='ws',security='tls',host=HOST,
            sni='alt13.yt3.ggpht.com',verify_cert_name=HOST,proxy_host='157.240.9.39',proxy_port=8080)
PRESETS={
 'fp_events':dict(COMMON,uuid='b71def49-88e2-4d13-afb6-c8b413ecb19e',path='/80602af6',mux=True,
  proxy_payload='CONNECT [host]:[port] HTTP/1.1[crlf]proxy-connection: keep-alive[crlf]user-agent: FBAV/0.0[crlf]x-iorg-bsid: @FP_EVENTS[crlf][crlf]'),
 'am2_d3':dict(COMMON,uuid='aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000',path='/Telegram/@AM2_D3/@AHMAD3214',mux=False,
  proxy_payload='CONNECT [host]:[port] HTTP/1.1[crlf]x-connected-to: 34.143.72.2[crlf]proxy-connection: keep-alive[crlf]connection: keep-alive[crlf]user-agent: FBAV/0.0[crlf]x-iorg-bsid: @AM2_D3[crlf][crlf]')}

def payload_headers(payload):
 if not isinstance(payload,str) or len(payload)>4096: raise ValueError('Payload CONNECT invalide.')
 lines=payload.replace('[crlf]','\r\n').split('\r\n')
 if lines[0]!='CONNECT [host]:[port] HTTP/1.1' or lines[-2:]!=['','']: raise ValueError('Utilisez CONNECT [host]:[port] HTTP/1.1 et terminez par [crlf][crlf].')
 headers={}
 for line in lines[1:-2]:
  key,sep,value=line.partition(':');value=value.lstrip(' ')
  if not sep or not re.fullmatch(r'[A-Za-z0-9-]{1,40}',key) or len(value)>200 or any(ord(c)<32 or ord(c)>126 for c in value) or key.lower() in {k.lower() for k in headers}: raise ValueError('En-tête CONNECT invalide ou en double.')
  headers[key]=value
 if len(headers)>12: raise ValueError('Maximum 12 en-têtes CONNECT.')
 return headers

def normalize(config):
 result=copy.deepcopy(config)
 if result.get('proxy_payload'):result['proxy_headers']=payload_headers(result['proxy_payload'])
 bank=result.get('global_profiles')
 if bank is not None:
  active=result.get('active_global_profile')
  if not isinstance(bank,dict) or not 1<=len(bank)<=10 or active not in bank or any(not isinstance(v,dict) or 'global_profiles' in v for v in bank.values()):raise ValueError('Sélection GLOBAL invalide.')
  bank[active]={k:v for k,v in result.items() if k not in ('global_profiles','active_global_profile')}
 return result

def migrate(cx,now):
 key='lot25_global_presets'
 if cx.execute('SELECT 1 FROM admin_state WHERE key=?',(key,)).fetchone():return
 row=cx.execute("SELECT config_json FROM vpn_profiles WHERE network_id='global_world'").fetchone()
 if not row:return
 previous=json.loads(row[0]);bank=copy.deepcopy(PRESETS);bank['previous']=previous
 # Preserve the latest owner configuration as a selectable profile; add an
 # authenticated TLS identity without changing its routing SNI or credentials.
 if previous.get('host') and previous.get('security')=='tls':
  bank['previous']['verify_cert_name']=previous['host']
 active='previous' if previous.get('host') not in (None,'','sangoku-149521136708.us-central1.run.app','ahmed-vidgg-294397537401.us-central1.run.app') else 'fp_events'
 config=normalize(dict(bank[active],global_profiles=bank,active_global_profile=active))
 cx.execute("UPDATE vpn_profiles SET config_json=?,version=version+1,updated_at=? WHERE network_id='global_world'",(json.dumps(config),now))
 cx.execute('INSERT INTO admin_state(key,value,updated_at) VALUES(?,?,?)',(key,'1',now))
