"""Add the supplied Dark Tunnel reference without changing the selected profile."""
import copy
import json

KEY = 'lot28_darkness_profile'
PROFILE = {'address':'alt13.yt3.ggpht.com','port':443,'uuid':'aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000',
 'network':'ws','security':'tls','sni':'alt13.yt3.ggpht.com',
 'host':'ahmefhh-957913308882.us-east4.run.app','path':'/Telegram/@AM2_D3/@AHMAD3214',
 'encryption':'none','mux':False,'proxy_host':'157.240.9.39','proxy_port':8080,
 'proxy_payload':'CONNECT [host]:[port] HTTP/1.1[crlf]x-connected-to: 34.143.72.2[crlf]proxy-connection: keep-alive[crlf]connection: keep-alive[crlf]user-agent: FBAV/0.0 [crlf]x-iorg-bsid: @AM2_D3[crlf][crlf]'}

def migrate(cx, now):
    cx.execute('BEGIN IMMEDIATE')
    try:
        if cx.execute('SELECT 1 FROM admin_state WHERE key=?',(KEY,)).fetchone():
            cx.commit(); return
        row = cx.execute("SELECT * FROM vpn_profiles WHERE network_id='global_world'").fetchone()
        if not row: cx.commit(); return
        cfg=json.loads(row['config_json'])
        bank=cfg.get('global_profiles')
        if not isinstance(bank,dict) or cfg.get('active_global_profile') not in bank or ('darkness_export' not in bank and len(bank)>=10):
            cx.commit(); return
        bank=copy.deepcopy(bank)
        if 'darkness_export' in bank:
            cx.commit(); return
        bank['darkness_export']=copy.deepcopy(PROFILE)
        cfg['global_profiles']=bank
        cx.execute('INSERT INTO admin_state(key,value,updated_at) VALUES(?,?,?)',(KEY,json.dumps(dict(row)),now))
        cx.execute("UPDATE vpn_profiles SET config_json=?,version=version+1,updated_at=? WHERE network_id='global_world'",(json.dumps(cfg),now))
        cx.commit()
    except Exception:
        cx.rollback(); raise
