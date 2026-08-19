from __future__ import annotations

import importlib
import os
from pathlib import Path


def load_modules(tmp_path: Path):
    db_path = tmp_path / "test.db"
    os.environ["DATABASE_PATH"] = str(db_path)
    os.environ["CODE_SECRET"] = "test-secret-that-is-long-enough"
    os.environ["ADMIN_TOKEN"] = "test-admin-token"
    os.environ["PUBLIC_BASE_URL"] = "https://api.test.local"

    import app.config as config
    import app.db as db
    import app.security as security
    import app.services as services

    importlib.reload(config)
    importlib.reload(db)
    importlib.reload(security)
    importlib.reload(services)
    db.init_db()
    return db, services


def test_trial_is_one_time_and_server_side(tmp_path):
    _, services = load_modules(tmp_path)
    device = "device-abcdefghijklmnop"

    access1, started1, _ = services.start_trial(device)
    access2, started2, _ = services.start_trial(device)

    assert started1 is True
    assert started2 is False
    assert access1["access_type"] == "TRIAL"
    assert access2["expires_at"] == access1["expires_at"]
    assert access1["remaining_seconds"] <= 3600
    assert access1["remaining_seconds"] > 0


def test_activation_code_is_single_use_and_extends(tmp_path):
    _, services = load_modules(tmp_path)
    device = "device-abcdefghijklmnop"
    other = "device-qrstuvwxyz1234"

    code1 = services.issue_activation_code("TEST:ONE", "24h")
    ok1, _, access1 = services.redeem_activation_code(device, code1)
    assert ok1 is True
    assert access1["access_type"] == "SUBSCRIPTION"

    ok_again, _, access_again = services.redeem_activation_code(device, code1)
    assert ok_again is True
    assert access_again["expires_at"] == access1["expires_at"]

    ok_other, _, _ = services.redeem_activation_code(other, code1)
    assert ok_other is False

    code2 = services.issue_activation_code("TEST:TWO", "24h")
    ok2, _, access2 = services.redeem_activation_code(device, code2)
    assert ok2 is True
    assert access2["remaining_seconds"] > access1["remaining_seconds"]


def test_payment_code_is_deterministic(tmp_path):
    _, services = load_modules(tmp_path)
    device = "device-payment-abcdefgh"
    reference = services.create_payment_record(device, "1w")
    source = f"PAYMENT:{reference}"
    code1 = services.issue_activation_code(source, "1w", reference)
    code2 = services.issue_activation_code(source, "1w", reference)
    assert code1 == code2
    assert code1.startswith("BARKA-")


def test_vpn_profiles_are_seeded_and_secrets_require_access(tmp_path):
    db, services = load_modules(tmp_path)
    import app.vpn_profiles as vpn_profiles
    importlib.reload(vpn_profiles)

    catalog = vpn_profiles.list_catalog()
    assert [item["network_id"] for item in catalog] == ["moov_bf", "orange_bf", "telecel_bf"]
    assert [item["protocol"] for item in catalog] == ["SLOWDNS", "VLESS", "UDP"]
    assert all(item["enabled"] is False for item in catalog)

    device = "device-vpnprofile-abcdef"
    try:
        vpn_profiles.get_profile_for_device(device, "orange_bf")
        assert False, "Un appareil sans accès ne doit pas recevoir la configuration"
    except PermissionError:
        pass

    services.issue_activation_code("TEST:VPN", "24h")
    code = services.issue_activation_code("TEST:VPN2", "24h")
    ok, _, _ = services.redeem_activation_code(device, code)
    assert ok is True

    profile = vpn_profiles.upsert_admin_profile({
        "network_id": "orange_bf",
        "display_name": "ORANGE BF",
        "protocol": "VLESS",
        "enabled": True,
        "priority": 10,
        "config": {"uri": "vless://example-only"},
    })
    assert profile["enabled"] is True
    assert profile["version"] >= 2

    live = vpn_profiles.get_profile_for_device(device, "orange_bf")
    assert live["protocol"] == "VLESS"
    assert live["config"]["uri"] == "vless://example-only"


def test_vpn_profile_protocol_is_locked_to_operator(tmp_path):
    load_modules(tmp_path)
    import app.vpn_profiles as vpn_profiles
    importlib.reload(vpn_profiles)

    try:
        vpn_profiles.upsert_admin_profile({
            "network_id": "moov_bf",
            "display_name": "MOOV-AFRICA BF",
            "protocol": "VLESS",
            "enabled": False,
            "priority": 100,
            "config": {},
        })
        assert False, "MOOV ne doit pas accepter VLESS dans cette architecture"
    except ValueError as exc:
        assert "SLOWDNS" in str(exc)
