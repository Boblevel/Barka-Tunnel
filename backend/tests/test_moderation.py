import importlib
from uuid import uuid4
from fastapi import FastAPI
from fastapi.testclient import TestClient
from test_core import load_modules

ADMIN = {'X-Admin-Token': 'test-admin-token'}
DEVICE = 'moderation-device-12345'

def setup(tmp_path):
    db, services = load_modules(tmp_path)
    from app import suggestions
    importlib.reload(suggestions)
    app = FastAPI(); app.include_router(suggestions.router)
    return TestClient(app), db, services

def test_independent_blocks_persist_and_unblock_without_restoring_revoked_rights(tmp_path):
    c, db, services = setup(tmp_path)
    services.start_trial(DEVICE)
    request = {'device_id': DEVICE, 'request_id': str(uuid4()), 'message': 'Une suggestion'}
    assert c.post('/v1/suggestions', json=request).status_code == 200
    def change(scope, blocked):
        return c.put('/v1/admin/moderation', headers=ADMIN, json={'device_id': DEVICE, 'scope': scope, 'blocked': blocked})
    assert change('suggestions', True).json() == {'app_blocked': False, 'suggestions_blocked': True}
    assert services.access_state(DEVICE)['allowed']
    assert c.post('/v1/suggestions', json=request).status_code == 403
    assert change('app', True).status_code == 200
    assert not services.access_state(DEVICE)['allowed']
    assert services.access_state(DEVICE)['app_blocked']
    assert not services.start_trial(DEVICE)[1]
    assert c.get('/v1/admin/suggestions', headers=ADMIN).json()['items'][0]['app_blocked']
    c.post('/v1/admin/suggestions/reset', headers=ADMIN, json={'confirmation': 'SUPPRIMER'})
    db.init_db()
    assert c.get('/v1/admin/moderation', headers=ADMIN).json()['items'][0]['app_blocked'] == 1
    assert change('app', False).status_code == 200
    assert services.access_state(DEVICE)['allowed']
    assert c.post('/v1/suggestions', json=request).status_code == 403
    assert change('suggestions', False).status_code == 200
    assert c.post('/v1/suggestions', json=request).status_code == 200
    with db.transaction() as cx:
        cx.execute('UPDATE devices SET access_disabled=1 WHERE device_id=?', (DEVICE,))
    change('app', True); change('app', False)
    assert not services.access_state(DEVICE)['allowed']

def test_admin_only_strict_input_unknown_author_and_device_isolation(tmp_path):
    c, db, services = setup(tmp_path)
    payload = {'device_id': DEVICE, 'scope': 'app', 'blocked': True}
    assert c.put('/v1/admin/moderation', json=payload).status_code == 401
    assert c.get('/v1/admin/moderation').status_code == 401
    assert c.put('/v1/admin/moderation', headers=ADMIN, json=payload).status_code == 404
    assert c.put('/v1/admin/moderation', headers=ADMIN, json=dict(payload, blocked='false')).status_code == 422
    assert c.put('/v1/admin/moderation', headers=ADMIN, json=dict(payload, scope='access_disabled')).status_code == 422
    assert c.post('/v1/moderation/status', json={'device_id': '           x'}).status_code == 422
    assert c.post('/v1/moderation/status', json={'device_id': DEVICE}).json() == {'app_blocked': False, 'suggestions_blocked': False}
    request = {'device_id': DEVICE, 'request_id': str(uuid4()), 'message': 'Suggestion'}
    c.post('/v1/suggestions', json=request)
    c.put('/v1/admin/moderation', headers=ADMIN, json=payload)
    services.start_trial('another-device-12345')
    assert services.access_state('another-device-12345')['allowed']
    response = c.post('/v1/moderation/status', json={'device_id': DEVICE})
    assert response.headers['cache-control'] == 'no-store'
    assert response.json()['app_blocked'] is True

def test_unlimited_access_respects_moderation(tmp_path):
    c, db, services = setup(tmp_path)
    code = services.issue_activation_code('moderation-unlimited', 'unlimited')
    assert services.redeem_activation_code(DEVICE, code)[0]
    c.post('/v1/suggestions', json={'device_id': DEVICE, 'request_id': str(uuid4()), 'message': 'Suggestion'})
    c.put('/v1/admin/moderation', headers=ADMIN, json={'device_id': DEVICE, 'scope': 'app', 'blocked': True})
    assert not services.access_state(DEVICE)['allowed']

def test_blocked_author_cannot_consume_an_activation_code(tmp_path):
    c, db, services = setup(tmp_path)
    c.post('/v1/suggestions', json={'device_id': DEVICE, 'request_id': str(uuid4()), 'message': 'Suggestion'})
    code = services.issue_activation_code('moderation-new-code', '24h')
    payload = {'device_id': DEVICE, 'scope': 'app', 'blocked': True}
    assert c.put('/v1/admin/moderation', headers=ADMIN, json=payload).status_code == 200
    assert not services.redeem_activation_code(DEVICE, code)[0]
    with db.connect() as cx:
        assert cx.execute('SELECT status FROM activation_codes WHERE code_hash=?', (services.code_hash(code),)).fetchone()[0] == 'issued'
    assert c.put('/v1/admin/moderation', headers=ADMIN, json={**payload, 'blocked': False}).status_code == 200
    assert services.redeem_activation_code(DEVICE, code)[0]

def test_suggestion_unblock_cannot_bypass_app_block_or_change_paid_subscription(tmp_path):
    c, db, services = setup(tmp_path)
    code = services.issue_activation_code('moderation-paid', '24h')
    # Use a known paid plan from the service rather than fabricating client rights.
    assert services.redeem_activation_code(DEVICE, code)[0]
    request = {'device_id': DEVICE, 'request_id': str(uuid4()), 'message': 'Suggestion'}
    assert c.post('/v1/suggestions', json=request).status_code == 200
    with db.transaction() as cx:
        before = dict(cx.execute('SELECT * FROM devices WHERE device_id=?', (DEVICE,)).fetchone())
    for scope, blocked in [('app', True), ('suggestions', True), ('suggestions', False)]:
        assert c.put('/v1/admin/moderation', headers=ADMIN, json={
            'device_id': DEVICE, 'scope': scope, 'blocked': blocked}).status_code == 200
    assert c.post('/v1/suggestions', json=request).status_code == 403
    assert not services.access_state(DEVICE)['allowed']
    with db.transaction() as cx:
        after = dict(cx.execute('SELECT * FROM devices WHERE device_id=?', (DEVICE,)).fetchone())
    assert after['subscription_expires_at'] == before['subscription_expires_at']
    assert after['access_revision'] == before['access_revision'] + 1
    assert c.put('/v1/admin/moderation', headers=ADMIN, json={
        'device_id': DEVICE, 'scope': 'app', 'blocked': False}).status_code == 200
    assert services.access_state(DEVICE)['allowed']
    assert c.post('/v1/suggestions', json=request).status_code == 200


def test_blocked_author_display_uses_name_but_keeps_internal_identity(tmp_path):
    c, db, _ = setup(tmp_path)
    request={'device_id':DEVICE,'request_id':str(uuid4()),'sender_name':'Auteur test','message':'Message'}
    assert c.post('/v1/suggestions',json=request).status_code==200
    assert c.put('/v1/admin/moderation',headers=ADMIN,json={'device_id':DEVICE,'scope':'app','blocked':True}).status_code==200
    item=c.get('/v1/admin/moderation',headers=ADMIN).json()['items'][0]
    assert item['sender_name']=='Auteur test'
    assert item['device_id']==DEVICE
    assert c.post('/v1/admin/suggestions/reset',headers=ADMIN,json={'confirmation':'SUPPRIMER'}).status_code==200
    item=c.get('/v1/admin/moderation',headers=ADMIN).json()['items'][0]
    assert item['sender_name'] is None and item['app_blocked']==1
    assert c.put('/v1/admin/moderation',headers=ADMIN,json={'device_id':DEVICE,'scope':'app','blocked':False}).status_code==200
