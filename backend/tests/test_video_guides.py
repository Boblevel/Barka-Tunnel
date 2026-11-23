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
