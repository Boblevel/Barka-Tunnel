from concurrent.futures import ThreadPoolExecutor, ProcessPoolExecutor
from datetime import datetime, timezone
import sqlite3

import pytest
from app.models import TrialStartResponse
from test_core import load_modules

MONDAY = int(datetime(2026, 9, 7, tzinfo=timezone.utc).timestamp())
DEVICE = 'weekly-device-abcdefghijklmnop'


def test_legacy_user_gets_weekly_gift_and_history_is_preserved(tmp_path, monkeypatch):
    db, services = load_modules(tmp_path)
    monkeypatch.setattr(services, 'now_ts', lambda: MONDAY + 3600)
    services.ensure_device(DEVICE)
    with db.transaction() as cx:
        cx.execute('UPDATE devices SET trial_started_at=?, trial_expires_at=? WHERE device_id=?',
                   (MONDAY - 20000, MONDAY - 12800, DEVICE))
    assert services.access_state(DEVICE)['trial_used'] is True
    assert services.access_state(DEVICE)['trial_week_used'] is False
    state, started, message = services.start_trial(DEVICE)
    assert started and state['remaining_seconds'] == 7200
    response = TrialStartResponse(**state, started_now=started, message=message)
    assert response.trial_week_start == MONDAY and response.trial_week_used is True
    assert services.start_trial(DEVICE)[1] is False


def test_sunday_monday_boundary_preserves_remaining_time(tmp_path, monkeypatch):
    _, services = load_modules(tmp_path)
    clock = [MONDAY - 1]
    monkeypatch.setattr(services, 'now_ts', lambda: clock[0])
    assert services.start_trial(DEVICE)[1]
    assert not services.start_trial(DEVICE)[1]
    clock[0] = MONDAY
    assert services.access_state(DEVICE)['trial_week_used'] is False
    state, started, _ = services.start_trial(DEVICE)
    assert started and state['remaining_seconds'] == 14399
    assert not services.start_trial(DEVICE)[1]
    clock[0] = MONDAY + 604800
    assert services.start_trial(DEVICE)[1]


def test_calendar_year_boundary():
    from app.services import trial_week_start
    for date in ['2026-12-31', '2027-01-01', '2027-01-03']:
        epoch = int(datetime.fromisoformat(date).replace(tzinfo=timezone.utc).timestamp())
        assert trial_week_start(epoch) == int(datetime(2026, 12, 28, tzinfo=timezone.utc).timestamp())


def test_subscription_is_extended_and_not_replaced(tmp_path, monkeypatch):
    db, services = load_modules(tmp_path)
    monkeypatch.setattr(services, 'now_ts', lambda: MONDAY + 3600)
    code = services.issue_activation_code('WEEKLY:SUB', '24h')
    assert services.redeem_activation_code(DEVICE, code)[0]
    before = services.access_state(DEVICE)
    after, started, _ = services.start_trial(DEVICE)
    assert started and after['access_type'] == 'SUBSCRIPTION'
    assert after['remaining_seconds'] == before['remaining_seconds'] + 7200
    assert after['started_at'] == before['started_at']
    assert not services.start_trial(DEVICE)[1]


def test_disabled_device_cannot_claim_or_clear_suspension(tmp_path):
    db, services = load_modules(tmp_path)
    services.ensure_device(DEVICE)
    with db.transaction() as cx:
        cx.execute('UPDATE devices SET access_disabled=1 WHERE device_id=?', (DEVICE,))
    state, started, _ = services.start_trial(DEVICE)
    assert not started and not state['allowed'] and not state['trial_week_used']


def test_concurrent_requests_only_credit_once(tmp_path):
    db, services = load_modules(tmp_path)
    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(services.start_trial, [DEVICE] * 24))
    assert sum(started for _, started, _ in results) == 1
    with db.transaction() as cx:
        assert cx.execute('SELECT count(*) FROM weekly_trial_claims').fetchone()[0] == 1
    assert services.access_state(DEVICE)['remaining_seconds'] <= 7200


def _process_claim(device):
    from app.services import start_trial
    return start_trial(device)[1]


def test_separate_server_processes_only_credit_once(tmp_path):
    _, services = load_modules(tmp_path)
    services.ensure_device(DEVICE)
    with ProcessPoolExecutor(max_workers=4) as pool:
        assert sum(pool.map(_process_claim, [DEVICE] * 12)) == 1


def test_reset_access_does_not_reset_weekly_allowance(tmp_path):
    db, services = load_modules(tmp_path)
    assert services.start_trial(DEVICE)[1]
    with db.transaction() as cx:
        cx.execute('UPDATE devices SET trial_started_at=NULL, trial_expires_at=NULL WHERE device_id=?', (DEVICE,))
    assert not services.start_trial(DEVICE)[1]
    db.init_db()
    assert not services.start_trial(DEVICE)[1]


def test_ledger_failure_rolls_back_credit(tmp_path):
    db, services = load_modules(tmp_path)
    services.ensure_device(DEVICE)
    with db.transaction() as cx:
        cx.execute("CREATE TRIGGER reject_claim BEFORE INSERT ON weekly_trial_claims BEGIN SELECT RAISE(ABORT, 'test'); END")
    with pytest.raises(sqlite3.IntegrityError):
        services.start_trial(DEVICE)
    state = services.access_state(DEVICE)
    assert not state['allowed'] and not state['trial_week_used']


def test_clock_rollback_and_other_device(tmp_path, monkeypatch):
    _, services = load_modules(tmp_path)
    clock = [MONDAY]
    monkeypatch.setattr(services, 'now_ts', lambda: clock[0])
    assert services.start_trial(DEVICE)[1]
    clock[0] = MONDAY - 1
    assert not services.start_trial(DEVICE)[1]
    assert services.start_trial('second-independent-device')[1]
