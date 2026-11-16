import hashlib
import pytest
from apk_fixture import apk_bytes, manifest_bytes
from test_saspay_resellers_updates import env, client_for


@pytest.mark.parametrize('utf8', [True, False])
def test_binary_manifest_matches_announced_version(utf8):
    from app.apk_metadata import manifest_info
    assert manifest_info(manifest_bytes(utf8=utf8)) == {
        'package_name': 'com.barkatunnel.app', 'version_code': 144,
        'version_name': '2.3.5.4', 'min_sdk': 23}


@pytest.mark.parametrize('payload', [b'', b'<html>Unavailable</html>', b'PK\x03\x04'])
def test_upload_rejects_non_apk(env, payload):
    *_, main, _ = env
    with client_for(main) as c:
        assert c.put('/v1/admin/app-update/apk', content=payload).status_code == 400


@pytest.mark.parametrize('extra', [{'split': 'config.arm64_v8a'}, {'package': 'unrelated.app'},
                                 {'required_split': 'application'}, {'required_split': 'types'},
                                 {'required_split': 'metadata'}])
def test_upload_rejects_split_or_other_app(env, extra):
    *_, main, _ = env
    with client_for(main) as c:
        assert c.put('/v1/admin/app-update/apk', content=apk_bytes(**extra)).status_code == 400


def test_upload_detects_version_and_publication_rejects_wrong_version(env):
    *_, main, _ = env
    with client_for(main) as c:
        upload = c.put('/v1/admin/app-update/apk', content=apk_bytes()).json()
        assert upload['version_code'] == 144 and upload['version_name'] == '2.3.5.4'
        body = dict(enabled=True, download_in_app=True, latest_version_code=145,
                    latest_version_name='2.3.5.4', apk_url=upload['apk_url'])
        before = c.get('/v1/admin/app-update').json()
        assert c.post('/v1/admin/app-update', json=body).status_code == 400
        assert c.get('/v1/admin/app-update').json() == before
        body['latest_version_code'] = 144
        assert c.post('/v1/admin/app-update', json=body).status_code == 200
        assert c.get('/v1/app/update?version_code=142').json()['apk_url'] == upload['apk_url']


def test_vps_url_is_frozen_and_redeployment_cannot_change_published_download(env):
    *_, main, _ = env
    from app.app_updates import release_apk_path
    path = release_apk_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = apk_bytes()
    path.write_bytes(payload)
    body = dict(enabled=True, download_in_app=True, latest_version_code=144,
                latest_version_name='2.3.5.4', apk_url=main.settings.public_base_url+'/downloads/BarkaTunnel.apk')
    with client_for(main) as c:
        response = c.post('/v1/admin/app-update', json=body)
        assert response.status_code == 200, response.text
        url = response.json()['apk_url']
        assert hashlib.sha256(payload).hexdigest() in url
        path.write_bytes(apk_bytes(version=145))
        assert c.get(url).content == payload
        # The same announced version may not refer to the replaced APK.
        assert c.post('/v1/admin/app-update', json=body).status_code == 400
