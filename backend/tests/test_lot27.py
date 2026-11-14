import json
from test_saspay_resellers_updates import env


def test_orange_v2_exact_copy_once_keeps_source_global_and_backup(env):
    db, *_ = env
    source_config = {'uri': 'vless://aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000@vpn.example:443?type=ws&security=tls&sni=vpn.example&host=vpn.example&path=%2Fvless',
                     'ip': '192.0.2.10', 'custom_existing_field': 'kept'}
    with db.transaction() as cx:
        cx.execute("UPDATE vpn_profiles SET enabled=1,config_json=? WHERE network_id='orange_bf'", (json.dumps(source_config),))
        cx.execute("UPDATE vpn_profiles SET config_json=? WHERE network_id='orange_bf_v2'", ('{"address":"old.example"}',))
        before = {r['network_id']: dict(r) for r in cx.execute('SELECT * FROM vpn_profiles')}
    db.init_db()
    with db.connect() as cx:
        after = {r['network_id']: dict(r) for r in cx.execute('SELECT * FROM vpn_profiles')}
        assert after['orange_bf_v2']['config_json'] == before['orange_bf']['config_json']
        assert after['orange_bf_v2']['version'] == before['orange_bf_v2']['version'] + 1
        for network in before:
            if network != 'orange_bf_v2': assert before[network] == after[network]
        backup = json.loads(cx.execute("SELECT value FROM admin_state WHERE key='lot27_orange_v2_repair'").fetchone()[0])
        assert backup['previous_v2'] == before['orange_bf_v2']
        cx.execute("UPDATE vpn_profiles SET config_json='{}' WHERE network_id='orange_bf_v2'")
    db.init_db()
    with db.connect() as cx:
        assert cx.execute("SELECT config_json FROM vpn_profiles WHERE network_id='orange_bf_v2'").fetchone()[0] == '{}'


def test_empty_orange_never_replaces_v2(env):
    db, *_ = env
    with db.connect() as cx:
        before = dict(cx.execute("SELECT * FROM vpn_profiles WHERE network_id='orange_bf_v2'").fetchone())
    db.init_db()
    with db.connect() as cx:
        assert dict(cx.execute("SELECT * FROM vpn_profiles WHERE network_id='orange_bf_v2'").fetchone()) == before
        assert cx.execute("SELECT 1 FROM admin_state WHERE key='lot27_orange_v2_repair'").fetchone() is None
