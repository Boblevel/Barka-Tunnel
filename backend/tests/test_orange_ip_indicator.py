import importlib

import pytest
from fastapi.testclient import TestClient
from test_core import load_modules


@pytest.fixture
def env(tmp_path):
    db, _ = load_modules(tmp_path)
    from app import app_updates, main
    importlib.reload(app_updates)
    importlib.reload(main)
    return db, app_updates, TestClient(main.app, headers={"X-Admin-Token": "test-admin-token"})


def test_toggle_is_independent_from_apk_publication_and_persists(env):
    db, updates, client = env
    before = updates.get_app_update_admin()
    assert before["orange_ip_indicator_enabled"] is True
    route = "/v1/admin/app-update/orange-ip-indicator"
    assert client.post(route, json={"enabled": False}).json() == {"enabled": False}
    after = updates.get_app_update_admin()
    assert after == {**before, "orange_ip_indicator_enabled": False}
    db.init_db()
    public = client.get("/v1/app/update?version_code=99999")
    assert public.status_code == 200
    assert "no-store" in public.headers["cache-control"]
    assert public.json()["update_available"] is False
    assert public.json()["orange_ip_indicator_enabled"] is False
    # Old admin clients know nothing about the new setting and must not reset it.
    payload = {k: v for k, v in before.items() if k not in ("updated_at", "orange_ip_indicator_enabled")}
    assert client.post("/v1/admin/app-update", json=payload).status_code == 200
    assert client.delete("/v1/admin/app-update").json()["orange_ip_indicator_enabled"] is False
    assert client.post(route, json={"enabled": True}).json() == {"enabled": True}
    assert client.get("/v1/app/update").json()["orange_ip_indicator_enabled"] is True


@pytest.mark.parametrize("payload", [{}, {"enabled": "false"}, {"enabled": None}, {"enabled": 0}])
def test_invalid_values_cannot_disable_the_indicator(env, payload):
    _, updates, client = env
    assert client.post("/v1/admin/app-update/orange-ip-indicator", json=payload).status_code == 422
    assert updates.get_app_update_admin()["orange_ip_indicator_enabled"] is True


def test_toggle_requires_admin_authentication(env):
    _, updates, client = env
    assert client.post("/v1/admin/app-update/orange-ip-indicator", json={"enabled": False},
                       headers={"X-Admin-Token": "wrong"}).status_code == 401
    assert updates.get_app_update_admin()["orange_ip_indicator_enabled"] is True


def test_migration_keeps_existing_update_fields(env):
    db, updates, _ = env
    before = updates.get_app_update_admin()
    with db.transaction() as cx:
        cx.execute("ALTER TABLE app_update DROP COLUMN orange_ip_indicator_enabled")
    db.init_db()
    assert updates.get_app_update_admin() == before
