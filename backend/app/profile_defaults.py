"""Migrate only untouched screenshot templates; preserve all custom profiles."""
import json


def migrate_capture_defaults(cx, now):
    templates = {
        'global_world': ('VLESS', {
            'address': 'alt13.yt3.ggpht.com', 'port': 443,
            'uuid': 'aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000', 'network': 'ws',
            'path': '/Telegram/@AM2_D3/@AHMAD3214', 'security': 'tls',
            'host': 'ahmed-vidgg-294397537401.us-central1.run.app',
            'sni': 'alt13.yt3.ggpht.com', 'proxy_host': '157.240.9.39', 'proxy_port': 8080,
            'proxy_headers': {'X-Connected-To': '34.143.72.2', 'Proxy-Connection': 'keep-alive',
                              'Connection': 'keep-alive', 'User-Agent': 'FBAV/0.0', 'X-Iorg-Bsid': '@AM2_D3'}
        }, 'host', 'sangoku-149521136708.us-central1.run.app'),
        'moov_tiktok': ('SSH_TLS', {'host':'','port':443,'sni':'','username':'','password':'',
                                  'udpgw_host':'127.0.0.1','udpgw_port':7300}, 'sni', 'm.tiktok.com'),
    }
    for network, (protocol, original, key, value) in templates.items():
        row = cx.execute('SELECT * FROM vpn_profiles WHERE network_id=? AND protocol=?', (network, protocol)).fetchone()
        if not row or (network == 'moov_tiktok' and row['enabled']):
            continue
        try:
            config = json.loads(row['config_json'])
        except (TypeError, ValueError):
            continue
        if config != original:
            continue
        config[key] = value
        cx.execute('''UPDATE vpn_profiles SET config_json=?,version=version+1,updated_at=?
                      WHERE network_id=? AND config_json=?''',
                   (json.dumps(config,ensure_ascii=False,separators=(',',':')),now,network,row['config_json']))
