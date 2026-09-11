from __future__ import annotations

import importlib
import os
import sqlite3
from datetime import datetime, timedelta, timezone
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
    assert current_client["enabled"] is True
    assert current_client["update_available"] is False
    assert current_client["force_update"] is False
    assert current_client["updated_at"] == saved["updated_at"]
    assert current_client["apk_url"] == "https://apkpure.com/p/com.barkatunnel.app"

    removed = app_updates.remove_app_update()
    assert removed["enabled"] is False
    assert removed["mandatory"] is False
    assert removed["updated_at"] > saved["updated_at"]

    after_removal = app_updates.get_app_update_for_client(6)
    assert after_removal["enabled"] is False
    assert after_removal["update_available"] is False
    assert after_removal["force_update"] is False
    assert after_removal["apk_url"] == ""


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


def test_redeem_is_expired_only_when_capacity_and_all_access_have_ended(tmp_path):
    db, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    code = services.issue_redeem_code("REDEEM:EXPIRED-DISPLAY", 3600, 5)
    unused = next(x for x in admin_ops.list_redeem_codes(20) if x["code"] == code)
    assert unused["status"] == "active"

    first = "device-redeem-expired-one"
    second = "device-redeem-expired-two"
    assert services.redeem_activation_code(first, code)[0] is True
    assert services.redeem_activation_code(second, code)[0] is True

    with db.transaction() as cx:
        cx.execute(
            "UPDATE redeem_usages SET applied_until=? WHERE device_id=?",
            (services.now_ts() - 1, first),
        )
    partly_active = next(
        x for x in admin_ops.list_redeem_codes(20) if x["code"] == code
    )
    assert partly_active["status"] == "active"

    with db.transaction() as cx:
        cx.execute(
            "UPDATE redeem_usages SET applied_until=? WHERE device_id=?",
            (services.now_ts() - 1, second),
        )
    # Two finished users do not exhaust the five available places.
    assert next(x for x in admin_ops.list_redeem_codes(20) if x["code"] == code)["status"] == "active"
    for i in range(3):
        assert services.redeem_activation_code(f"device-redeem-later-{i}", code)[0] is True
    assert services.redeem_activation_code("device-redeem-over-capacity", code)[0] is False
    assert next(x for x in admin_ops.list_redeem_codes(20) if x["code"] == code)["status"] == "active"
    with db.transaction() as cx:
        cx.execute("UPDATE redeem_usages SET applied_until=?", (services.now_ts() - 1,))
    fully_expired = next(
        x for x in admin_ops.list_redeem_codes(20) if x["code"] == code
    )
    assert fully_expired["status"] == "expired"

    deleted, _ = admin_ops.delete_redeem_code(code)
    assert deleted is True
    assert all(x["code"] != code for x in admin_ops.list_redeem_codes(20))


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


def test_unique_device_is_counted_only_once_after_repeat_registration(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    device = "device-unique-install-abcdef"
    services.access_state(device)
    services.access_state(device)
    services.mark_connection_attempt(device)
    services.mark_connection_attempt(device)

    stats = admin_ops.admin_stats_extended()
    assert stats["devices"] == 1
    assert stats["users"] == 1


def test_admin_stats_reset_preserves_access_and_known_devices(tmp_path):
    _, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    device = "device-reset-preserved-abcdef"
    code = services.issue_activation_code("MANUAL:RESET-PRESERVE", "24h")
    ok, _, access = services.redeem_activation_code(device, code)
    assert ok is True
    assert access["allowed"] is True
    services.mark_connection_attempt(device)

    before = admin_ops.admin_stats_extended()
    assert before["devices"] == 1
    assert before["active_subscriptions"] == 1

    reset_at = admin_ops.reset_admin_stats()
    after = admin_ops.admin_stats_extended()
    assert reset_at
    assert after["stats_reset_at"] == reset_at
    assert after["devices"] == 0
    assert after["users"] == 0
    assert after["active_subscriptions"] == 0
    assert services.access_state(device)["allowed"] is True
    assert any(item["code"] == code for item in admin_ops.list_activation_codes(20))
    assert admin_ops.admin_stats_extended()["devices"] == 0

    services.access_state("device-new-after-reset-abcdef")
    assert admin_ops.admin_stats_extended()["devices"] == 1


def test_reseller_credentials_sessions_origin_and_freeze_preserve_code(tmp_path):
    db, services = load_modules(tmp_path)
    import app.resellers as resellers
    importlib.reload(resellers)

    account = resellers.create_reseller(
        "Vendeur.01",
        datetime.now(timezone.utc) + timedelta(days=2),
    )
    assert account["username"] == "vendeur.01"
    assert len(account["password"]) == 18
    assert account["panel_url"] == "https://api.test.local/reseller"

    cx = db.connect()
    try:
        stored = cx.execute(
            "SELECT password_hash, password_salt FROM reseller_accounts WHERE id=?",
            (account["id"],),
        ).fetchone()
    finally:
        cx.close()
    assert stored["password_hash"] != account["password"]
    assert stored["password_salt"] != account["password"]

    login = resellers.login_reseller(account["username"], account["password"])
    session_account = resellers.require_reseller(f"Bearer {login['token']}")
    assert int(session_account["id"]) == account["id"]

    code = resellers.generate_reseller_subscription(account["id"], "24h")
    item = resellers.list_reseller_codes(account["id"])[0]
    assert item["code"] == code
    assert item["source_type"] == "REVENDEUR"
    assert item["reseller_username"] == "vendeur.01"
    assert item["code_type"] == "subscription"

    frozen = resellers.freeze_reseller(account["id"])
    assert frozen["status"] == "frozen"
    try:
        resellers.require_reseller(f"Bearer {login['token']}")
        assert False, "Le gel doit révoquer immédiatement les sessions revendeur"
    except Exception as exc:
        assert getattr(exc, "status_code", None) == 401

    redeemed, _, access = services.redeem_activation_code(
        "device-reseller-frozen-abcdef", code
    )
    assert redeemed is True
    assert access["allowed"] is True


def test_deleting_reseller_revokes_previously_generated_test_code(tmp_path):
    _, services = load_modules(tmp_path)
    import app.resellers as resellers
    importlib.reload(resellers)

    account = resellers.create_reseller(
        "vendeur-delete",
        datetime.now(timezone.utc) + timedelta(days=1),
    )
    code = resellers.generate_reseller_test(account["id"])
    listed = resellers.list_reseller_codes(account["id"])[0]
    assert listed["code_type"] == "test"
    assert listed["plan_id"] == "test_2h"

    resellers.delete_reseller(account["id"])
    assert all(item["id"] != account["id"] for item in resellers.list_resellers())
    redeemed, _, access = services.redeem_activation_code(
        "device-reseller-deleted-abcdef", code
    )
    assert redeemed is False
    assert access["remaining_seconds"] == 0


def test_reseller_can_delete_only_own_codes(tmp_path):
    _, services = load_modules(tmp_path)
    import app.resellers as resellers
    importlib.reload(resellers)

    first_account = resellers.create_reseller(
        "vendeur-first",
        datetime.now(timezone.utc) + timedelta(days=1),
    )
    second_account = resellers.create_reseller(
        "vendeur-second",
        datetime.now(timezone.utc) + timedelta(days=1),
    )
    first_code = resellers.generate_reseller_subscription(first_account["id"], "24h")
    second_code = resellers.generate_reseller_test(second_account["id"])

    denied, denied_message = resellers.delete_reseller_code(
        first_account["id"], second_code
    )
    assert denied is False
    assert "non autorisé" in denied_message
    assert any(
        item["code"] == second_code
        for item in resellers.list_reseller_codes(second_account["id"])
    )

    device = "device-reseller-delete-own"
    redeemed, _, access = services.redeem_activation_code(device, first_code)
    assert redeemed is True
    assert access["remaining_seconds"] > 0

    deleted, _ = resellers.delete_reseller_code(first_account["id"], first_code)
    assert deleted is True
    assert services.access_state(device)["remaining_seconds"] == 0
    assert all(
        item["code"] != first_code
        for item in resellers.list_reseller_codes(first_account["id"])
    )

    unused_deleted, _ = resellers.delete_reseller_code(
        second_account["id"], second_code
    )
    assert unused_deleted is True
    assert all(
        item["code"] != second_code
        for item in resellers.list_reseller_codes(second_account["id"])
    )


def test_reseller_dashboard_stats_are_isolated_and_expiration_aware(tmp_path):
    db, services = load_modules(tmp_path)
    import app.resellers as resellers
    importlib.reload(resellers)

    first_account = resellers.create_reseller(
        "vendeur-stats-first",
        datetime.now(timezone.utc) + timedelta(days=1),
    )
    second_account = resellers.create_reseller(
        "vendeur-stats-second",
        datetime.now(timezone.utc) + timedelta(days=1),
    )

    available_code = resellers.generate_reseller_subscription(
        first_account["id"], "24h"
    )
    active_code = resellers.generate_reseller_test(first_account["id"])
    expired_code = resellers.generate_reseller_subscription(
        first_account["id"], "1w"
    )
    resellers.generate_reseller_test(second_account["id"])

    active_ok, _, _ = services.redeem_activation_code(
        "device-reseller-stats-active", active_code
    )
    expired_ok, _, _ = services.redeem_activation_code(
        "device-reseller-stats-expired", expired_code
    )
    assert active_ok is True
    assert expired_ok is True

    with db.transaction() as cx:
        cx.execute(
            "UPDATE activation_codes SET applied_until=? WHERE code_hash=?",
            (services.now_ts() - 1, services.code_hash(expired_code)),
        )

    assert resellers.reseller_dashboard_stats(first_account["id"]) == {
        "stats_reset_at": None,
        "total": 3,
        "available": 1,
        "active": 1,
        "expired": 1,
    }
    assert resellers.reseller_dashboard_stats(second_account["id"]) == {
        "stats_reset_at": None,
        "total": 1,
        "available": 1,
        "active": 0,
        "expired": 0,
    }

    deleted, _ = resellers.delete_reseller_code(
        first_account["id"], available_code
    )
    assert deleted is True
    assert resellers.reseller_dashboard_stats(first_account["id"])["total"] == 2


def test_reseller_panel_has_dashboard_filters_and_admin_expired_style():
    from app.reseller_panel import RESELLER_PANEL_HTML

    for page in ("dashboard", "subscription", "test", "codes"):
        assert f'data-page="{page}"' in RESELLER_PANEL_HTML
    assert "/v1/reseller/stats" in RESELLER_PANEL_HTML
    assert 'id="codeSearch"' in RESELLER_PANEL_HTML
    assert 'id="codeStatus"' in RESELLER_PANEL_HTML
    assert (
        ".code-row.expired{background:#fff1f2;border:1px solid #fecdd3"
        in RESELLER_PANEL_HTML
    )
    assert ".expired-label{color:#b91c1c;font-weight:900}" in RESELLER_PANEL_HTML
    assert "/v1/admin/" not in RESELLER_PANEL_HTML


def test_used_code_is_displayed_expired_at_zero_and_can_be_deleted(tmp_path):
    db, services = load_modules(tmp_path)
    import app.admin_ops as admin_ops
    importlib.reload(admin_ops)

    code = services.issue_activation_code("MANUAL:EXPIRED-DISPLAY", "24h")
    redeemed, _, _ = services.redeem_activation_code(
        "device-expired-display-abcdef", code
    )
    assert redeemed is True

    with db.transaction() as cx:
        cx.execute(
            "UPDATE activation_codes SET applied_until=? WHERE code_hash=?",
            (services.now_ts() - 1, services.code_hash(code)),
        )

    item = next(x for x in admin_ops.list_activation_codes(20) if x["code"] == code)
    assert item["status"] == "expired"
    assert item["remaining_seconds"] == 0
    deleted, _ = admin_ops.delete_activation_code(code)
    assert deleted is True
    assert all(x["code"] != code for x in admin_ops.list_activation_codes(20))


def test_legacy_database_migration_adds_reseller_schema_without_data_loss(tmp_path):
    db_path = tmp_path / "legacy.db"
    cx = sqlite3.connect(db_path)
    try:
        cx.execute(
            """
            CREATE TABLE activation_codes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code_hash TEXT NOT NULL UNIQUE,
                source_ref TEXT NOT NULL UNIQUE,
                plan_id TEXT NOT NULL,
                duration_seconds INTEGER NOT NULL,
                payment_reference TEXT,
                status TEXT NOT NULL DEFAULT 'issued',
                created_at INTEGER NOT NULL,
                redeemed_at INTEGER,
                redeemed_device_id TEXT
            )
            """
        )
        cx.execute(
            """
            INSERT INTO activation_codes(
                code_hash, source_ref, plan_id, duration_seconds, status, created_at
            ) VALUES('legacy-hash','MANUAL:LEGACY','24h',86400,'issued',1)
            """
        )
        cx.commit()
    finally:
        cx.close()

    os.environ["DATABASE_PATH"] = str(db_path)
    os.environ["CODE_SECRET"] = "test-secret-that-is-long-enough"
    os.environ["ADMIN_TOKEN"] = "test-admin-token"
    os.environ["PUBLIC_BASE_URL"] = "https://api.test.local"
    import app.config as config
    import app.db as db
    importlib.reload(config)
    importlib.reload(db)
    db.init_db()

    migrated = db.connect()
    try:
        columns = {
            row["name"]
            for row in migrated.execute("PRAGMA table_info(activation_codes)").fetchall()
        }
        tables = {
            row["name"]
            for row in migrated.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        legacy = migrated.execute(
            "SELECT source_ref FROM activation_codes WHERE code_hash='legacy-hash'"
        ).fetchone()
    finally:
        migrated.close()

    assert {"applied_from", "applied_until", "deleted_at", "created_by_reseller_id"} <= columns
    assert {"reseller_accounts", "reseller_sessions"} <= tables
    assert legacy["source_ref"] == "MANUAL:LEGACY"
