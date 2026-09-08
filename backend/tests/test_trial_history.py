import pytest

from app.models import AccessResponse, TrialStartResponse
from test_core import load_modules


@pytest.mark.parametrize(
    "scenario,used,allowed,access_type",
    [
        ("fresh", False, False, "NONE"),
        ("active", True, True, "TRIAL"),
        ("expired", True, False, "NONE"),
        ("subscriber", True, True, "SUBSCRIPTION"),
        ("disabled", True, False, "NONE"),
        ("subscriber_without_trial", False, True, "SUBSCRIPTION"),
    ],
)
def test_legacy_trial_history_survives_access_changes(tmp_path, scenario, used, allowed, access_type):
    db, services = load_modules(tmp_path)
    device = "legacy-device-" + scenario
    services.ensure_device(device)
    now = services.now_ts()
    # Simuler les lignes déjà présentes avant la mise à jour, sans migration.
    with db.transaction() as cx:
        if used:
            cx.execute(
                "UPDATE devices SET trial_started_at=?, trial_expires_at=? WHERE device_id=?",
                (now - 10000, now + 3600 if scenario == "active" else now - 2800, device),
            )
        if scenario.startswith("subscriber"):
            cx.execute(
                "UPDATE devices SET subscription_started_at=?, subscription_expires_at=? WHERE device_id=?",
                (now - 100, now + 86400, device),
            )
        if scenario == "disabled":
            cx.execute("UPDATE devices SET access_disabled=1 WHERE device_id=?", (device,))
    for _ in range(2):
        response = AccessResponse(**services.access_state(device)).model_dump()
        assert response["trial_used"] is used
        assert response["allowed"] is allowed
        assert response["access_type"] == access_type


def test_successful_claim_records_history_without_extending_trial(tmp_path):
    _, services = load_modules(tmp_path)
    device = "new-device-for-gift-history"
    first, started, message = services.start_trial(device)
    response = TrialStartResponse(**first, started_now=started, message=message).model_dump()
    assert response["trial_used"] is True
    assert response["started_now"] is True
    second, started_again, _ = services.start_trial(device)
    assert second["trial_used"] is True
    assert started_again is False
    assert second["expires_at"] == first["expires_at"]
