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
    assert 7190 <= access1["remaining_seconds"] <= 7200


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


def test_manual_code_is_universal_for_first_user(tmp_path):
    _, services = load_modules(tmp_path)
    code = services.issue_activation_code("MANUAL:UNIVERSAL", "24h")

    first_device = "device-any-user-abcdefgh"
    second_device = "device-other-user-ijklmnop"

    ok_first, _, access_first = services.redeem_activation_code(first_device, code)
    assert ok_first is True
    assert access_first["access_type"] == "SUBSCRIPTION"

    ok_second, message_second, access_second = services.redeem_activation_code(second_device, code)
    assert ok_second is False
    assert "déjà été utilisé" in message_second
    assert access_second["allowed"] is False


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

    maintained = vpn_profiles.upsert_admin_profile({
        "network_id": "orange_bf",
        "display_name": "ORANGE BF",
        "protocol": "VLESS",
        "enabled": True,
        "maintenance": True,
        "priority": 10,
        "config": {"uri": "vless://example-only"},
    })
    assert maintained["maintenance"] is True
    try:
        vpn_profiles.get_profile_for_device(device, "orange_bf")
        assert False, "Un réseau en maintenance ne doit pas fournir sa configuration"
    except LookupError as exc:
        assert "maintenance" in str(exc).lower()


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


def test_admin_can_revoke_unused_code(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    code = services.issue_activation_code("TEST:REVOKE", "24h")
    ok, message = admin_ops.revoke_activation_code(code)
    assert ok is True
    assert "désactiv" in message.lower()

    success, message, access = services.redeem_activation_code(
        "device-revoked-abcdef", code
    )
    assert success is False
    assert "désactiv" in message.lower()
    assert access["allowed"] is False


def test_app_update_is_server_controlled(tmp_path):
    load_modules(tmp_path)
    import app.app_updates as app_updates
    importlib.reload(app_updates)

    initial = app_updates.get_app_update_for_client(1)
    assert initial["update_available"] is False
    assert initial["force_update"] is False

    saved = app_updates.upsert_app_update({
        "enabled": True,
        "latest_version_code": 7,
        "latest_version_name": "1.7.0",
        "apk_url": "https://downloads.example.test/BarkaTunnel.apk",
        "message": "Mise à jour de sécurité.",
        "mandatory": True,
    })
    assert saved["mandatory"] is True

    old_client = app_updates.get_app_update_for_client(6)
    assert old_client["update_available"] is True
    assert old_client["force_update"] is True
    assert old_client["apk_url"].startswith("https://")

    current_client = app_updates.get_app_update_for_client(7)
    assert current_client["update_available"] is False
    assert current_client["force_update"] is False
    assert current_client["apk_url"] == ""


def test_admin_code_listing_reconstructs_manual_code(tmp_path):
    load_modules(tmp_path)
    import app.services as services
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    code = services.issue_activation_code("MANUAL:LIST-TEST", "1w")
    items = admin_ops.list_activation_codes(10)
    assert items
    assert items[0]["code"] == code
    assert items[0]["source_type"] == "ABONNEMENT"
    assert items[0]["status"] == "issued"

def test_admin_can_delete_unused_manual_code(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    code = services.issue_activation_code("MANUAL:DELETE-ME", "24h")
    ok, message = admin_ops.delete_activation_code(code)
    assert ok is True
    assert "supprim" in message.lower()
    assert all(item["code"] != code for item in admin_ops.list_activation_codes(20))


def test_admin_can_freeze_and_reactivate_redeemed_access(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    import app.vpn_profiles as vpn_profiles
    importlib.reload(admin_ops)
    importlib.reload(vpn_profiles)

    device = "device-freeze-access-abcdef"
    code = services.issue_activation_code("MANUAL:FREEZE", "24h")
    ok, _, access = services.redeem_activation_code(device, code)
    assert ok is True
    assert access["allowed"] is True

    revoked, _ = admin_ops.revoke_activation_code(code)
    assert revoked is True
    assert services.access_state(device)["allowed"] is False

    vpn_profiles.upsert_admin_profile({
        "network_id": "orange_bf",
        "display_name": "ORANGE BF",
        "protocol": "VLESS",
        "enabled": True,
        "priority": 10,
        "config": {"uri": "vless://example-only"},
    })
    try:
        vpn_profiles.get_profile_for_device(device, "orange_bf")
        assert False, "Un accès désactivé ne doit recevoir aucun profil opérateur"
    except PermissionError:
        pass

    restored, _ = admin_ops.reactivate_activation_code(code)
    assert restored is True
    assert services.access_state(device)["allowed"] is True


def test_admin_delete_redeemed_code_removes_its_remaining_time(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    device = "device-delete-used-abcdef"
    code1 = services.issue_activation_code("MANUAL:USED-ONE", "24h")
    code2 = services.issue_activation_code("MANUAL:USED-TWO", "24h")
    ok1, _, access1 = services.redeem_activation_code(device, code1)
    ok2, _, access2 = services.redeem_activation_code(device, code2)
    assert ok1 is True and ok2 is True
    assert access2["remaining_seconds"] > access1["remaining_seconds"]

    deleted, message = admin_ops.delete_activation_code(code2)
    assert deleted is True
    assert "temps restant" in message.lower()
    after = services.access_state(device)
    assert after["allowed"] is True
    assert after["remaining_seconds"] < access2["remaining_seconds"]
    assert abs(after["remaining_seconds"] - access1["remaining_seconds"]) <= 3


def test_admin_can_delete_payment_code_without_reissue(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    device = "device-payment-audit-abcdef"
    payment_reference = services.create_payment_record(device, "24h")
    payment = services.issue_activation_code(
        f"PAYMENT:{payment_reference}", "24h", payment_reference
    )
    ok, _, _ = services.redeem_activation_code(device, payment)
    assert ok is True

    deleted, message = admin_ops.delete_activation_code(payment)
    assert deleted is True
    assert "supprim" in message.lower()
    assert all(item["code"] != payment for item in admin_ops.list_activation_codes(20))

    same_code = services.issue_activation_code(
        f"PAYMENT:{payment_reference}", "24h", payment_reference
    )
    assert same_code == payment
    success, _, access = services.redeem_activation_code(device, same_code)
    assert success is False
    assert access["remaining_seconds"] == 0


def test_admin_listing_has_expiration_date_after_redemption(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    code = services.issue_activation_code("MANUAL:DATES", "1w")
    ok, _, _ = services.redeem_activation_code("device-dates-abcdefgh", code)
    assert ok is True
    item = next(x for x in admin_ops.list_activation_codes(20) if x["code"] == code)
    assert item["created_at"]
    assert item["expires_at"]


def test_one_week_plan_is_seven_days(tmp_path):
    load_modules(tmp_path)
    import app.plans as plans
    importlib.reload(plans)
    assert plans.PLANS["1w"]["duration_seconds"] == 7 * 24 * 60 * 60


def test_redeem_code_supports_multiple_users_with_limit(tmp_path):
    _, services = load_modules(tmp_path)
    code = services.issue_redeem_code("REDEEM:MULTI", 6 * 60 * 60, 2)

    first = "device-redeem-first-abcdef"
    second = "device-redeem-second-abcdef"
    third = "device-redeem-third-abcdef"

    ok1, _, access1 = services.redeem_activation_code(first, code)
    ok2, _, access2 = services.redeem_activation_code(second, code)
    assert ok1 is True and ok2 is True
    assert 21590 <= access1["remaining_seconds"] <= 21600
    assert 21590 <= access2["remaining_seconds"] <= 21600

    same_ok, same_message, same_access = services.redeem_activation_code(first, code)
    assert same_ok is True
    assert "déjà été utilisé" in same_message
    assert same_access["expires_at"] == access1["expires_at"]

    ok3, message3, access3 = services.redeem_activation_code(third, code)
    assert ok3 is False
    assert "limite" in message3.lower()
    assert access3["allowed"] is False


def test_admin_redeem_disable_reactivate_and_usage_count(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    code = services.issue_redeem_code("REDEEM:CONTROL", 3600, 5)
    ok, _, _ = services.redeem_activation_code("device-redeem-control-one", code)
    assert ok is True

    revoked, _ = admin_ops.revoke_redeem_code(code)
    assert revoked is True
    blocked, message, _ = services.redeem_activation_code("device-redeem-control-two", code)
    assert blocked is False
    assert "désactiv" in message.lower()

    restored, _ = admin_ops.reactivate_redeem_code(code)
    assert restored is True
    allowed, _, _ = services.redeem_activation_code("device-redeem-control-two", code)
    assert allowed is True

    item = next(x for x in admin_ops.list_redeem_codes(20) if x["code"] == code)
    assert item["usage_count"] == 2
    assert item["max_users"] == 5
    assert item["duration_seconds"] == 3600


def test_delete_redeem_removes_remaining_time_from_all_users(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    code = services.issue_redeem_code("REDEEM:DELETE", 2 * 60 * 60, 10)
    devices = ["device-redeem-delete-one", "device-redeem-delete-two"]
    for device in devices:
        ok, _, access = services.redeem_activation_code(device, code)
        assert ok is True
        assert access["remaining_seconds"] > 7100

    deleted, message = admin_ops.delete_redeem_code(code)
    assert deleted is True
    assert "temps restant" in message.lower()
    for device in devices:
        assert services.access_state(device)["remaining_seconds"] == 0

    assert all(item["code"] != code for item in admin_ops.list_redeem_codes(20))
