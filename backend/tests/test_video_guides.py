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
        assert c.get('/v1/video-guides').json() == {'guide':'https://www.youtube.com/watch?v=NUe3oG-R5hI','ip_finder':''}
        assert c.put('/v1/admin/video-guides',json={'guide':'','ip_finder':''}).status_code == 200
        assert c.get('/v1/video-guides').json()['guide'] == ''
