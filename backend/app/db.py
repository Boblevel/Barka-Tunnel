from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path

from .config import settings

_lock = threading.RLock()


def connect() -> sqlite3.Connection:
    Path(settings.database_path).parent.mkdir(parents=True, exist_ok=True)
    cx = sqlite3.connect(settings.database_path, timeout=20, isolation_level=None)
    cx.row_factory = sqlite3.Row
    cx.execute("PRAGMA foreign_keys = ON")
    cx.execute("PRAGMA busy_timeout = 5000")
    return cx


def init_db() -> None:
    with _lock:
        cx = connect()
        try:
            cx.execute("PRAGMA journal_mode = WAL")
            cx.executescript(
                """
                CREATE TABLE IF NOT EXISTS devices(
                    device_id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    trial_started_at INTEGER,
                    trial_expires_at INTEGER,
                    subscription_started_at INTEGER,
                    subscription_expires_at INTEGER
                );

                CREATE TABLE IF NOT EXISTS payments(
                    reference TEXT PRIMARY KEY,
                    provider_payment_id TEXT UNIQUE,
                    device_id TEXT NOT NULL,
                    plan_id TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    currency TEXT NOT NULL DEFAULT 'XOF',
                    status TEXT NOT NULL DEFAULT 'creating',
                    checkout_url TEXT,
                    provider_error TEXT,
                    activation_source_ref TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(device_id) REFERENCES devices(device_id)
                );

                CREATE INDEX IF NOT EXISTS idx_payments_device
                ON payments(device_id, created_at DESC);

                CREATE TABLE IF NOT EXISTS activation_codes(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code_hash TEXT NOT NULL UNIQUE,
                    source_ref TEXT NOT NULL UNIQUE,
                    plan_id TEXT NOT NULL,
                    duration_seconds INTEGER NOT NULL,
                    payment_reference TEXT,
                    status TEXT NOT NULL DEFAULT 'issued',
                    created_at INTEGER NOT NULL,
                    redeemed_at INTEGER,
                    redeemed_device_id TEXT,
                    FOREIGN KEY(payment_reference) REFERENCES payments(reference),
                    FOREIGN KEY(redeemed_device_id) REFERENCES devices(device_id)
                );

                CREATE INDEX IF NOT EXISTS idx_activation_codes_hash
                ON activation_codes(code_hash);

                CREATE TABLE IF NOT EXISTS webhook_events(
                    event_id TEXT PRIMARY KEY,
                    received_at INTEGER NOT NULL
                );
                """
            )
        finally:
            cx.close()


@contextmanager
def transaction():
    with _lock:
        cx = connect()
        try:
            cx.execute("BEGIN IMMEDIATE")
            yield cx
            cx.execute("COMMIT")
        except Exception:
            if cx.in_transaction:
                cx.execute("ROLLBACK")
            raise
        finally:
            cx.close()
