import importlib
from uuid import uuid4
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from test_core import load_modules

@pytest.fixture
def client(tmp_path):
    db, _ = load_modules(tmp_path)
    from app import suggestions
    importlib.reload(suggestions)
    app = FastAPI()
    app.include_router(suggestions.router)
    with TestClient(app) as c:
        yield c, db

ADMIN = {'X-Admin-Token': 'test-admin-token'}

def body(message='Une idée pour Barka Tunnel'):
    return {'device_id': 'suggestion-device-12345', 'request_id': str(uuid4()), 'message': message}

def test_suggestion_switch_persistence_and_server_enforcement(client):
    c, db = client
    assert c.get('/v1/suggestions/config').json() == {'enabled': True}
    assert c.get('/v1/admin/suggestions').status_code == 401
    assert c.put('/v1/admin/suggestions/config', json={'enabled': False}).status_code == 401
    assert c.post('/v1/suggestions', json=body()).status_code == 200
    assert c.put('/v1/admin/suggestions/config', headers=ADMIN, json={'enabled': False}).status_code == 200
    db.init_db()
    assert c.get('/v1/suggestions/config').json() == {'enabled': False}
    assert c.post('/v1/suggestions', json=body()).status_code == 403
    listing = c.get('/v1/admin/suggestions', headers=ADMIN).json()
    assert listing['total'] == 1 and listing['items'][0]['created_at'].endswith('+00:00')
    assert c.put('/v1/admin/suggestions/config', headers=ADMIN, json={'enabled': True}).status_code == 200
    other = body();other['device_id'] = 'another-device-12345'
    assert c.post('/v1/suggestions', json=other).status_code == 200

def test_retry_is_idempotent_throttle_and_validation(client):
    c, _ = client
    request = body('<script>alert("test")</script>')
    first = c.post('/v1/suggestions', json=request).json()
    assert c.post('/v1/suggestions', json=request).json() == first
    assert c.post('/v1/suggestions', json=dict(request, message='Other text')).status_code == 409
    assert c.post('/v1/suggestions', json=body()).status_code == 429
    for text in ['  ', 'a', 'x' * 2001]:
        assert c.post('/v1/suggestions', json=body(text)).status_code == 422
    assert c.put('/v1/admin/suggestions/config', headers=ADMIN, json={'enabled': 'false'}).status_code == 422
    assert c.get('/v1/admin/suggestions?offset=-1', headers=ADMIN).status_code == 422
    assert c.get('/v1/admin/suggestions?offset=1', headers=ADMIN).json()['items'] == []
    assert c.get('/v1/admin/suggestions', headers=ADMIN).json()['total'] == 1
