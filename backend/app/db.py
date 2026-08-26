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
                    subscription_expires_at INTEGER,
                    access_disabled INTEGER NOT NULL DEFAULT 0
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
                    applied_from INTEGER,
                    applied_until INTEGER,
                    deleted_at INTEGER,
                    FOREIGN KEY(payment_reference) REFERENCES payments(reference),
                    FOREIGN KEY(redeemed_device_id) REFERENCES devices(device_id)
                );

                CREATE INDEX IF NOT EXISTS idx_activation_codes_hash
                ON activation_codes(code_hash);

                CREATE TABLE IF NOT EXISTS redeem_codes(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code_hash TEXT NOT NULL UNIQUE,
                    source_ref TEXT NOT NULL UNIQUE,
                    duration_seconds INTEGER NOT NULL,
                    max_users INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active',
                    created_at INTEGER NOT NULL,
                    deleted_at INTEGER
                );

                CREATE TABLE IF NOT EXISTS redeem_usages(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    redeem_code_id INTEGER NOT NULL,
                    device_id TEXT NOT NULL,
                    redeemed_at INTEGER NOT NULL,
                    applied_from INTEGER NOT NULL,
                    applied_until INTEGER NOT NULL,
                    UNIQUE(redeem_code_id, device_id),
                    FOREIGN KEY(redeem_code_id) REFERENCES redeem_codes(id),
                    FOREIGN KEY(device_id) REFERENCES devices(device_id)
                );

                CREATE INDEX IF NOT EXISTS idx_redeem_codes_hash
                ON redeem_codes(code_hash);

                CREATE INDEX IF NOT EXISTS idx_redeem_usages_code
                ON redeem_usages(redeem_code_id, redeemed_at DESC);

                CREATE TABLE IF NOT EXISTS webhook_events(
                    event_id TEXT PRIMARY KEY,
                    received_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS vpn_profiles(
                    network_id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    protocol TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 0,
                    maintenance INTEGER NOT NULL DEFAULT 0,
                    priority INTEGER NOT NULL DEFAULT 100,
                    version INTEGER NOT NULL DEFAULT 1,
                    config_json TEXT NOT NULL DEFAULT '{}',
                    updated_at INTEGER NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_vpn_profiles_enabled
                ON vpn_profiles(enabled, priority, network_id);

                CREATE TABLE IF NOT EXISTS app_update(
                    id INTEGER PRIMARY KEY CHECK(id=1),
                    enabled INTEGER NOT NULL DEFAULT 0,
                    latest_version_code INTEGER NOT NULL DEFAULT 1,
                    latest_version_name TEXT NOT NULL DEFAULT '1.0.0',
                    apk_url TEXT NOT NULL DEFAULT '',
                    message TEXT NOT NULL DEFAULT 'Une nouvelle version de Barka Tunnel est disponible.',
                    mandatory INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                );
                """
            )

            columns = {row["name"] for row in cx.execute("PRAGMA table_info(vpn_profiles)").fetchall()}
            if "maintenance" not in columns:
                cx.execute("ALTER TABLE vpn_profiles ADD COLUMN maintenance INTEGER NOT NULL DEFAULT 0")

            device_columns = {row["name"] for row in cx.execute("PRAGMA table_info(devices)").fetchall()}
            if "access_disabled" not in device_columns:
                cx.execute("ALTER TABLE devices ADD COLUMN access_disabled INTEGER NOT NULL DEFAULT 0")

            code_columns = {row["name"] for row in cx.execute("PRAGMA table_info(activation_codes)").fetchall()}
            if "applied_from" not in code_columns:
                cx.execute("ALTER TABLE activation_codes ADD COLUMN applied_from INTEGER")
            if "applied_until" not in code_columns:
                cx.execute("ALTER TABLE activation_codes ADD COLUMN applied_until INTEGER")
            if "deleted_at" not in code_columns:
                cx.execute("ALTER TABLE activation_codes ADD COLUMN deleted_at INTEGER")

            cx.execute(
                """
                UPDATE activation_codes
                SET applied_from=COALESCE(applied_from, redeemed_at),
                    applied_until=COALESCE(applied_until, redeemed_at + duration_seconds)
                WHERE redeemed_at IS NOT NULL
                  AND (applied_from IS NULL OR applied_until IS NULL)
                """
            )

            now = int(__import__("time").time())
            defaults = (
                ("moov_bf", "MOOV-AFRICA BF", "SLOWDNS"),
                ("orange_bf", "ORANGE BF", "VLESS"),
                ("telecel_bf", "TELECEL BF", "UDP"),
            )
            cx.executemany(
                """
                INSERT OR IGNORE INTO vpn_profiles(
                    network_id, display_name, protocol, enabled,
                    priority, version, config_json, updated_at
                ) VALUES(?,?,?,0,100,1,'{}',?)
                """,
                [(network_id, display_name, protocol, now)
                 for network_id, display_name, protocol in defaults],
            )
            cx.execute(
                """
                INSERT OR IGNORE INTO app_update(
                    id, enabled, latest_version_code, latest_version_name,
                    apk_url, message, mandatory, updated_at
                ) VALUES(1,0,1,'1.0.0','',
                         'Une nouvelle version de Barka Tunnel est disponible.',0,?)
                """,
                (now,),
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
