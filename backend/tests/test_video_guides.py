from test_saspay_resellers_updates import env, client_for

def test_video_config_admin_only_and_rejects_untrusted(env):
    *_, main, account = env
    with client_for(main) as c:
        a=c.put('/v1/admin/video-guides',json={'guide':'https://youtu.be/AbCdEf01234','ip_finder':''})
        assert a.status_code==200
        assert c.get('/v1/video-guides').json()['guide']=='https://www.youtube.com/watch?v=AbCdEf01234'
        assert c.put('/v1/admin/video-guides',headers={'X-Admin-Token':'wrong'},json={'guide':'','ip_finder':''}).status_code==401
        assert c.put('/v1/admin/video-guides',json={'guide':'https://evil.example/watch?v=AbCdEf01234','ip_finder':''}).status_code==422
        assert c.get('/v1/video-guides').json()['guide']=='https://www.youtube.com/watch?v=AbCdEf01234'


def test_default_guide_and_explicit_clear(env):
    *_, main, account = env
    with client_for(main) as c:
        assert c.get('/v1/video-guides').json() == {'guide':'https://www.youtube.com/watch?v=NUe3oG-R5hI','ip_finder':'','guide_source':'mp4','ip_finder_source':'mp4'}
        assert c.put('/v1/admin/video-guides',json={'guide':'','ip_finder':''}).status_code == 200
        assert c.get('/v1/video-guides').json()['guide'] == ''


def test_independent_sources_and_legacy_save(env):
    *_, main, _ = env
    url = 'https://www.youtube.com/watch?v=AbCdEf01234'
    with client_for(main) as c:
        for section, other in [('guide', 'ip_finder'), ('ip_finder', 'guide')]:
            body = {'guide': url, 'ip_finder': url, section+'_source': 'youtube', other+'_source': 'mp4'}
            assert c.put('/v1/admin/video-guides', json=body).json() == body
            assert c.put('/v1/admin/video-guides', json={'guide':url, 'ip_finder':url}).json() == body
            for invalid in [{section:''}, {section+'_source':'untrusted'}]:
                assert c.put('/v1/admin/video-guides', json={**body, **invalid}).status_code == 422
                assert c.get('/v1/video-guides').json() == body


def test_public_video_supports_playback_and_seeking(env):
    import hashlib
    *_, main, _ = env
    with client_for(main) as client:
        for prefix in ("/media/", "/downloads/"):
            path = prefix + "barka-guide-2e66858b2a9fa9c2.mp4"
            full = client.get(path)
            assert full.status_code == 200 and full.headers["content-type"] == "video/mp4"
            assert hashlib.sha256(full.content).hexdigest() == "2e66858b2a9fa9c239c8f237febcc71d3c58509b2874841a74a7ef40eb40fcc5"
            partial = client.get(path, headers={"Range": "bytes=32-95"})
            assert partial.status_code == 206 and partial.content == full.content[32:96]
            assert partial.headers["content-range"] == f"bytes 32-95/{len(full.content)}"
            assert client.head(path).headers["content-length"] == str(len(full.content))
