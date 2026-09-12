from datetime import datetime, timedelta, timezone
from fastapi.testclient import TestClient
from test_custom_subscriptions import env


def test_free_names_create_login_and_copy(tmp_path):
    *_, main = env(tmp_path)
    with TestClient(main.app, headers={'X-Admin-Token':'test-admin-token'}) as client:
        for name in ['mike larson','mike cooper larson',"Élodie O'Connor",'李 明','A','Nom '+ 'x'*80,'<b>client</b>']:
            response=client.post('/v1/admin/resellers',json={'username':name,'expires_at':(datetime.now(timezone.utc)+timedelta(days=30)).isoformat()})
            assert response.status_code==200, response.text
            data=response.json()
            login=client.post('/v1/reseller/login',json={'username':name,'password':data['password']})
            assert login.status_code==200,login.text
            assert login.json()['account']['username']==name.lower()
        for name in ['', '   ', 'bad\nname', 'x'*201, 123]:
            response=client.post('/v1/admin/resellers',json={'username':name,'expires_at':(datetime.now(timezone.utc)+timedelta(days=30)).isoformat()})
            assert response.status_code==422
