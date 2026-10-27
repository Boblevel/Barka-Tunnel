from __future__ import annotations

import sqlite3
import json
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
                    access_disabled INTEGER NOT NULL DEFAULT 0,
                    last_connect_attempt_at INTEGER
                );

                -- Historique indépendant des droits : une suppression d'accès
                -- ne doit pas rendre le cadeau réutilisable la même semaine.
                CREATE TABLE IF NOT EXISTS weekly_trial_claims(
                    device_id TEXT NOT NULL,
                    week_start INTEGER NOT NULL,
                    claimed_at INTEGER NOT NULL,
                    applied_until INTEGER NOT NULL,
                    PRIMARY KEY(device_id, week_start)
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

                CREATE TABLE IF NOT EXISTS suggestions(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id TEXT NOT NULL,
                    request_id TEXT NOT NULL,
                    message TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    UNIQUE(device_id, request_id)
                );
                CREATE INDEX IF NOT EXISTS idx_suggestions_device_time
                ON suggestions(device_id, created_at DESC);

                CREATE TABLE IF NOT EXISTS admin_state(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS vpn_profiles(
                    network_id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    protocol TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 0,
                    maintenance INTEGER NOT NULL DEFAULT 0,
                    priority INTEGER NOT NULL DEFAULT 1,
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

                CREATE TABLE IF NOT EXISTS reseller_accounts(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL COLLATE NOCASE,
                    password_hash TEXT NOT NULL,
                    password_salt TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active',
                    expires_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER
                );

                CREATE UNIQUE INDEX IF NOT EXISTS idx_reseller_username_active
                ON reseller_accounts(lower(username))
                WHERE deleted_at IS NULL;

                CREATE TABLE IF NOT EXISTS reseller_events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    reseller_id INTEGER NOT NULL,
                    event TEXT NOT NULL,
                    occurred_at INTEGER NOT NULL,
                    client_ip TEXT,
                    detail TEXT NOT NULL DEFAULT '',
                    FOREIGN KEY(reseller_id) REFERENCES reseller_accounts(id)
                );
                CREATE INDEX IF NOT EXISTS idx_reseller_events_recent
                ON reseller_events(reseller_id, occurred_at DESC, id DESC);

                CREATE TABLE IF NOT EXISTS reseller_sessions(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    reseller_id INTEGER NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    revoked_at INTEGER,
                    FOREIGN KEY(reseller_id) REFERENCES reseller_accounts(id)
                );

                CREATE TABLE IF NOT EXISTS reseller_purchase_owners(
                    owner_hash TEXT PRIMARY KEY,
                    reseller_id INTEGER NOT NULL REFERENCES reseller_accounts(id)
                );
                CREATE TABLE IF NOT EXISTS reseller_purchases(
                    reference TEXT PRIMARY KEY REFERENCES payments(reference),
                    owner_hash TEXT NOT NULL,
                    request_id TEXT NOT NULL,
                    months INTEGER NOT NULL CHECK(months IN (1,2)),
                    reseller_id INTEGER REFERENCES reseller_accounts(id),
                    fulfilled_at INTEGER,
                    fulfillment_error TEXT,
                    UNIQUE(owner_hash, request_id)
                );

                CREATE INDEX IF NOT EXISTS idx_reseller_sessions_lookup
                ON reseller_sessions(token_hash, expires_at);
                """
            )

            suggestion_columns = {row["name"] for row in cx.execute("PRAGMA table_info(suggestions)")}
            if "sender_name" not in suggestion_columns:
                cx.execute("ALTER TABLE suggestions ADD COLUMN sender_name TEXT NOT NULL DEFAULT ''")
            if "read_at" not in suggestion_columns:
                cx.execute("ALTER TABLE suggestions ADD COLUMN read_at INTEGER")

            columns = {row["name"] for row in cx.execute("PRAGMA table_info(vpn_profiles)").fetchall()}
            if "maintenance" not in columns:
                cx.execute("ALTER TABLE vpn_profiles ADD COLUMN maintenance INTEGER NOT NULL DEFAULT 0")

            update_columns = {row["name"] for row in cx.execute("PRAGMA table_info(app_update)").fetchall()}
            if "download_in_app" not in update_columns:
                cx.execute("ALTER TABLE app_update ADD COLUMN download_in_app INTEGER NOT NULL DEFAULT 0")

            reseller_columns = {row["name"] for row in cx.execute("PRAGMA table_info(reseller_accounts)").fetchall()}
            if "password_nonce" not in reseller_columns:
                cx.execute("ALTER TABLE reseller_accounts ADD COLUMN password_nonce TEXT")

            device_columns = {row["name"] for row in cx.execute("PRAGMA table_info(devices)").fetchall()}
            if "pause_accounted_until" not in device_columns:
                cx.execute("ALTER TABLE devices ADD COLUMN pause_accounted_until INTEGER NOT NULL DEFAULT 0")
            if "access_revision" not in device_columns:
                cx.execute("ALTER TABLE devices ADD COLUMN access_revision INTEGER NOT NULL DEFAULT 0")
            if "access_disabled" not in device_columns:
                cx.execute("ALTER TABLE devices ADD COLUMN access_disabled INTEGER NOT NULL DEFAULT 0")
            if "last_connect_attempt_at" not in device_columns:
                cx.execute("ALTER TABLE devices ADD COLUMN last_connect_attempt_at INTEGER")

            code_columns = {row["name"] for row in cx.execute("PRAGMA table_info(activation_codes)").fetchall()}
            if "applied_from" not in code_columns:
                cx.execute("ALTER TABLE activation_codes ADD COLUMN applied_from INTEGER")
            if "applied_until" not in code_columns:
                cx.execute("ALTER TABLE activation_codes ADD COLUMN applied_until INTEGER")
            if "deleted_at" not in code_columns:
                cx.execute("ALTER TABLE activation_codes ADD COLUMN deleted_at INTEGER")
            if "created_by_reseller_id" not in code_columns:
                cx.execute(
                    """
                    ALTER TABLE activation_codes
                    ADD COLUMN created_by_reseller_id INTEGER
                    REFERENCES reseller_accounts(id)
                    """
                )

            cx.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_activation_codes_reseller
                ON activation_codes(created_by_reseller_id, id DESC)
                """
            )

            cx.execute(
                """
                UPDATE activation_codes
                SET applied_from=COALESCE(applied_from, redeemed_at),
                    applied_until=COALESCE(applied_until, redeemed_at + duration_seconds)
                WHERE redeemed_at IS NOT NULL
                  AND NOT (plan_id='unlimited' AND created_by_reseller_id IS NULL)
                  AND (applied_from IS NULL OR applied_until IS NULL)
                """
            )

            # Repair unlimited dates filled by the legacy finite-duration backfill.
            # Keep revocation/deletion and all finite subscriptions untouched.
            cx.execute("""UPDATE activation_codes SET applied_until=NULL
                          WHERE plan_id='unlimited' AND created_by_reseller_id IS NULL
                            AND applied_until IS NOT NULL""")

            now = int(__import__("time").time())
            defaults = (
                ("moov_bf", "MOOV-AFRICA BF", "SLOWDNS"),
                ("moov_tiktok", "MOOV FORFAIT TIKTOK", "SSH_TLS"),
                ("orange_bf", "ORANGE BF", "VLESS"),
                ("telecel_bf", "TELECEL BF", "UDP"),
            )
            cx.executemany(
                """
                INSERT OR IGNORE INTO vpn_profiles(
                    network_id, display_name, protocol, enabled,
                    priority, version, config_json, updated_at
                ) VALUES(?,?,?,0,1,1,'{}',?)
                """,
                [(network_id, display_name, protocol, now)
                 for network_id, display_name, protocol in defaults],
            )
            cx.execute("""UPDATE vpn_profiles SET display_name='GLOBAL MONDIAL',
                          version=version+1, updated_at=?
                          WHERE network_id='global_world' AND display_name='GLOBAL MONDIAL 🌍'""", (now,))
            # Empty, disabled TLS profiles get an editable template, never live credentials.
            cx.execute("""UPDATE vpn_profiles SET config_json=?, version=version+1, updated_at=?
                          WHERE network_id='moov_tiktok' AND protocol='SSH_TLS'
                          AND enabled=0 AND config_json='{}'""",
                       (json.dumps({'host':'','port':443,'sni':'m.tiktok.com','username':'','password':'',
                                    'udpgw_host':'127.0.0.1','udpgw_port':7300}), now))
            # Le modèle de la capture est conservé sans activer un serveur
            # dont l'authentification et la connectivité n'ont pas été vérifiées.
            cx.execute("""
                INSERT OR IGNORE INTO vpn_profiles(
                    network_id, display_name, protocol, enabled,
                    priority, version, config_json, updated_at
                ) VALUES('global_world','GLOBAL MONDIAL','VLESS',0,5,1,?,?)
            """, (json.dumps({
                "address": "alt13.yt3.ggpht.com", "port": 443,
                "uuid": "aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000",
                "network": "ws", "path": "/Telegram/@AM2_D3/@AHMAD3214",
                "security": "tls", "host": "sangoku-149521136708.us-central1.run.app",
                "sni": "alt13.yt3.ggpht.com", "proxy_host": "157.240.9.39",
                "proxy_port": 8080,
                "proxy_headers": {"X-Connected-To": "34.143.72.2", "Proxy-Connection": "keep-alive",
                                  "Connection": "keep-alive", "User-Agent": "FBAV/0.0",
                                  "X-Iorg-Bsid": "@AM2_D3"}
            }, ensure_ascii=False, separators=(',', ':')), now))
            from .profile_defaults import migrate_capture_defaults
            migrate_capture_defaults(cx, now)
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
