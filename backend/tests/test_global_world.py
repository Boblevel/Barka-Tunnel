import importlib

import pytest

from test_core import load_modules


def test_global_profile_is_seeded_private_and_never_overwritten(tmp_path):
    db, services = load_modules(tmp_path)
    from app import vpn_profiles
    importlib.reload(vpn_profiles)
    profiles = vpn_profiles.list_admin_profiles()
    global_profile = next(p for p in profiles if p['network_id'] == 'global_world')
    assert global_profile['enabled'] is False
    assert global_profile['protocol'] == 'VLESS'
    cfg = global_profile['config']
    assert cfg['host'] == 'sangoku-149521136708.us-central1.run.app'
    assert cfg['proxy_host'] == '157.240.9.39'
    assert cfg['proxy_port'] == 8080
    assert cfg['proxy_headers']['X-Connected-To'] == '34.143.72.2'
    assert cfg['proxy_headers']['X-Iorg-Bsid'] == '@AM2_D3'
    services.start_trial('global-device-12345678')
    with pytest.raises(LookupError):
        vpn_profiles.get_profile_for_device('global-device-12345678', 'global_world')
    changed = dict(cfg, host='new-owner-host.example')
    result = vpn_profiles.upsert_admin_profile(dict(global_profile, config=changed))
    assert result['version'] == global_profile['version'] + 1
    db.init_db()
    assert next(p for p in vpn_profiles.list_admin_profiles() if p['network_id'] == 'global_world')['config']['host'] == 'new-owner-host.example'


def test_proxy_rejects_crlf_and_invalid_port_before_publication(tmp_path):
    load_modules(tmp_path)
    from app.vpn_profiles import validate_vless_proxy
    safe = {'security': 'tls', 'network': 'ws', 'proxy_host': '157.240.9.39', 'proxy_port': 8080,
            'proxy_headers': {'X-Iorg-Bsid': '@AM2_D3'}}
    validate_vless_proxy(safe)
    for bad in [dict(safe, proxy_port=70000), dict(safe, security='none'),
                dict(safe, proxy_headers={'X-Test': 'safe\r\nInjected: yes'})]:
        with pytest.raises(ValueError):
            validate_vless_proxy(bad)
