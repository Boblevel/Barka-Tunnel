from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import string
import uuid
from datetime import datetime, timezone

from fastapi import Header, HTTPException, status

from .admin_ops import delete_activation_code, list_activation_codes
from .config import settings
from .db import connect, transaction
from .plans import get_plan
from .security import activation_code_for_source, code_hash
from .services import now_ts, iso
from .reseller_audit import record_event


PASSWORD_ITERATIONS = 600_000
SESSION_LIFETIME_SECONDS = 12 * 60 * 60
MAX_ACCOUNT_LIFETIME_SECONDS = 10 * 365 * 24 * 60 * 60


def reseller_panel_url() -> str:
    return f"{settings.public_base_url}/reseller"


def _expiry_timestamp(value: datetime) -> int:
    aware = value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)
    timestamp = int(aware.timestamp())
    now = now_ts()
    if timestamp <= now:
        raise ValueError("La date d'expiration doit être dans le futur.")
    if timestamp - now > MAX_ACCOUNT_LIFETIME_SECONDS:
        raise ValueError("La date d'expiration ne peut pas dépasser 10 ans.")
    return timestamp


def _encode(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def _decode(value: str) -> bytes:
    return base64.b64decode(value.encode("ascii"), validate=True)


def _password_digest(password: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt,
        PASSWORD_ITERATIONS,
    )


def _generate_password() -> str:
    required = [
        secrets.choice(string.ascii_uppercase),
        secrets.choice(string.ascii_lowercase),
        secrets.choice(string.digits),
        secrets.choice("!@#$%+-_"),
    ]
    alphabet = string.ascii_letters + string.digits + "!@#$%+-_"
    required.extend(secrets.choice(alphabet) for _ in range(14))
    secrets.SystemRandom().shuffle(required)
    return "".join(required)


def _public_account(row) -> dict:
    now = now_ts()
    account_status = str(row["status"])
    if int(row["expires_at"]) <= now:
        account_status = "expired"
    return {
        "id": int(row["id"]),
        "username": str(row["username"]),
        "status": account_status,
        "expires_at": datetime.fromtimestamp(
            int(row["expires_at"]), tz=timezone.utc
        ).isoformat().replace("+00:00", "Z"),
        "created_at": datetime.fromtimestamp(
            int(row["created_at"]), tz=timezone.utc
        ).isoformat().replace("+00:00", "Z"),
        "panel_url": reseller_panel_url(),
    }


def public_reseller_account(row) -> dict:
    return _public_account(row)


def create_reseller(username: str, expires_at: datetime) -> dict:
    normalized_username = username.strip().lower()
    expiry = _expiry_timestamp(expires_at)
    password = _generate_password()
    salt = secrets.token_bytes(16)
    digest = _password_digest(password, salt)
    now = now_ts()

    try:
        with transaction() as cx:
            cursor = cx.execute(
                """
                INSERT INTO reseller_accounts(
                    username, password_hash, password_salt, status,
                    expires_at, created_at, updated_at
                ) VALUES(?,?,?,'active',?,?,?)
                """,
                (
                    normalized_username,
                    _encode(digest),
                    _encode(salt),
                    expiry,
                    now,
                    now,
                ),
            )
            row = cx.execute(
                "SELECT * FROM reseller_accounts WHERE id=?",
                (cursor.lastrowid,),
            ).fetchone()
    except Exception as exc:
        if "UNIQUE constraint failed" in str(exc):
            raise ValueError("Ce nom d'utilisateur existe déjà.") from exc
        raise

    result = _public_account(row)
    result["password"] = password
    return result


def list_resellers() -> list[dict]:
    cx = connect()
    try:
        rows = cx.execute(
            """
            SELECT * FROM reseller_accounts
            WHERE deleted_at IS NULL
            ORDER BY id DESC
            """
        ).fetchall()
    finally:
        cx.close()
    return [_public_account(row) for row in rows]


def _revoke_sessions(cx, reseller_id: int, now: int) -> None:
    cx.execute(
        """
        UPDATE reseller_sessions
        SET revoked_at=?
        WHERE reseller_id=? AND revoked_at IS NULL
        """,
        (now, reseller_id),
    )


def freeze_reseller(reseller_id: int) -> dict:
    now = now_ts()
    with transaction() as cx:
        row = cx.execute(
            "SELECT * FROM reseller_accounts WHERE id=? AND deleted_at IS NULL",
            (reseller_id,),
        ).fetchone()
        if not row:
            raise ValueError("Sous-panel introuvable.")
        cx.execute(
            "UPDATE reseller_accounts SET status='frozen', updated_at=? WHERE id=?",
            (now, reseller_id),
        )
        _revoke_sessions(cx, reseller_id, now)
        row = cx.execute(
            "SELECT * FROM reseller_accounts WHERE id=?",
            (reseller_id,),
        ).fetchone()
    return _public_account(row)


def reactivate_reseller(reseller_id: int) -> dict:
    now = now_ts()
    with transaction() as cx:
        row = cx.execute(
            "SELECT * FROM reseller_accounts WHERE id=? AND deleted_at IS NULL",
            (reseller_id,),
        ).fetchone()
        if not row:
            raise ValueError("Sous-panel introuvable.")
        if int(row["expires_at"]) <= now:
            raise ValueError("Prolongez d'abord la date d'expiration de ce sous-panel.")
        cx.execute(
            "UPDATE reseller_accounts SET status='active', updated_at=? WHERE id=?",
            (now, reseller_id),
        )
        row = cx.execute(
            "SELECT * FROM reseller_accounts WHERE id=?",
            (reseller_id,),
        ).fetchone()
    return _public_account(row)


def update_reseller_expiry(reseller_id: int, expires_at: datetime) -> dict:
    expiry = _expiry_timestamp(expires_at)
    now = now_ts()
    with transaction() as cx:
        row = cx.execute(
            "SELECT id FROM reseller_accounts WHERE id=? AND deleted_at IS NULL",
            (reseller_id,),
        ).fetchone()
        if not row:
            raise ValueError("Sous-panel introuvable.")
        cx.execute(
            "UPDATE reseller_accounts SET expires_at=?, updated_at=? WHERE id=?",
            (expiry, now, reseller_id),
        )
        _revoke_sessions(cx, reseller_id, now)
        row = cx.execute(
            "SELECT * FROM reseller_accounts WHERE id=?",
            (reseller_id,),
        ).fetchone()
    return _public_account(row)


def delete_reseller(reseller_id: int) -> None:
    now = now_ts()
    with transaction() as cx:
        row = cx.execute(
            "SELECT id FROM reseller_accounts WHERE id=? AND deleted_at IS NULL",
            (reseller_id,),
        ).fetchone()
        if not row:
            raise ValueError("Sous-panel introuvable.")
        cx.execute(
            """
            UPDATE reseller_accounts
            SET status='frozen', deleted_at=?, updated_at=?
            WHERE id=?
            """,
            (now, now, reseller_id),
        )
        _revoke_sessions(cx, reseller_id, now)


def _verify_password(password: str, row) -> bool:
    try:
        actual = _password_digest(password, _decode(str(row["password_salt"])))
        expected = _decode(str(row["password_hash"]))
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False


def login_reseller(username: str, password: str, client_ip: str | None = None) -> dict:
    normalized_username = username.strip().lower()
    cx = connect()
    try:
        row = cx.execute(
            """
            SELECT * FROM reseller_accounts
            WHERE lower(username)=? AND deleted_at IS NULL
            """,
            (normalized_username,),
        ).fetchone()
    finally:
        cx.close()

    valid_password = False
    if row:
        valid_password = _verify_password(password, row)
    else:
        _password_digest(password, b"\0" * 16)

    now = now_ts()
    if (
        not row
        or not valid_password
        or str(row["status"]) != "active"
        or int(row["expires_at"]) <= now
    ):
        if row:
            with transaction() as tx:
                record_event(tx, int(row["id"]), "login_failed", client_ip)
        raise ValueError("Identifiants invalides ou sous-panel indisponible.")

    token = secrets.token_urlsafe(32)
    token_digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
    session_expiry = min(int(row["expires_at"]), now + SESSION_LIFETIME_SECONDS)
    with transaction() as tx:
        current = tx.execute("SELECT * FROM reseller_accounts WHERE id=?", (row["id"],)).fetchone()
        if current["deleted_at"] is not None or current["status"] != "active" or current["expires_at"] <= now_ts():
            raise ValueError("Identifiants invalides ou sous-panel indisponible.")
        session_expiry = min(session_expiry, int(current["expires_at"]))
        record_event(tx, int(row["id"]), "login_success", client_ip)
        tx.execute(
            "DELETE FROM reseller_sessions WHERE expires_at<=? OR revoked_at IS NOT NULL",
            (now,),
        )
        tx.execute(
            """
            INSERT INTO reseller_sessions(
                reseller_id, token_hash, created_at, expires_at
            ) VALUES(?,?,?,?)
            """,
            (int(row["id"]), token_digest, now, session_expiry),
        )

    return {
        "token": token,
        "session_expires_at": datetime.fromtimestamp(
            session_expiry, tz=timezone.utc
        ).isoformat().replace("+00:00", "Z"),
        "account": _public_account(row),
    }


def _bearer_token(authorization: str | None) -> str:
    value = (authorization or "").strip()
    scheme, separator, token = value.partition(" ")
    if separator != " " or scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Connexion revendeur requise",
        )
    return token.strip()


def require_reseller(authorization: str | None = Header(default=None)) -> dict:
    token = _bearer_token(authorization)
    digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
    now = now_ts()
    cx = connect()
    try:
        row = cx.execute(
            """
            SELECT r.*, s.id AS session_id, s.expires_at AS session_expires_at
            FROM reseller_sessions s
            JOIN reseller_accounts r ON r.id=s.reseller_id
            WHERE s.token_hash=?
              AND s.revoked_at IS NULL
              AND s.expires_at>?
              AND r.deleted_at IS NULL
              AND r.status='active'
              AND r.expires_at>?
            """,
            (digest, now, now),
        ).fetchone()
    finally:
        cx.close()
    if not row:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Session revendeur expirée ou refusée",
        )
    return dict(row)


def logout_reseller(authorization: str | None) -> None:
    token = _bearer_token(authorization)
    digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
    with transaction() as cx:
        cx.execute(
            "UPDATE reseller_sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
            (now_ts(), digest),
        )


def _issue_reseller_code(reseller_id: int, plan_id: str, duration_seconds: int) -> str:
    source_ref = f"RESELLER:{reseller_id}:{plan_id}:{uuid.uuid4().hex}"
    code = activation_code_for_source(source_ref)
    now = now_ts()
    with transaction() as cx:
        account = cx.execute(
            """
            SELECT id FROM reseller_accounts
            WHERE id=? AND deleted_at IS NULL AND status='active' AND expires_at>?
            """,
            (reseller_id, now),
        ).fetchone()
        if not account:
            raise PermissionError("Sous-panel expiré, gelé ou supprimé.")
        cx.execute(
            """
            INSERT INTO activation_codes(
                code_hash, source_ref, plan_id, duration_seconds,
                status, created_at, created_by_reseller_id
            ) VALUES(?,?,?,?, 'issued', ?,?)
            """,
            (
                code_hash(code),
                source_ref,
                plan_id,
                int(duration_seconds),
                now,
                reseller_id,
            ),
        )
        record_event(cx, reseller_id, "code_created", detail=plan_id)
    return code


def generate_reseller_subscription(reseller_id: int, plan_id: str) -> str:
    plan = get_plan(plan_id)
    if not plan:
        raise ValueError("Offre inconnue.")
    return _issue_reseller_code(
        reseller_id,
        plan_id,
        int(plan["duration_seconds"]),
    )


def generate_reseller_test(reseller_id: int) -> str:
    return _issue_reseller_code(reseller_id, "test_2h", 2 * 60 * 60)


def list_reseller_codes(reseller_id: int, limit: int = 200, offset: int = 0) -> list[dict]:
    return list_activation_codes(limit=limit, reseller_id=reseller_id, offset=offset)


def reseller_dashboard_stats(reseller_id: int) -> dict[str, int]:
    now = now_ts()
    cx = connect()
    try:
        row = cx.execute(
            """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN status='issued' THEN 1 ELSE 0 END) AS available,
                SUM(
                    CASE
                        WHEN status='redeemed' AND applied_until>? THEN 1
                        ELSE 0
                    END
                ) AS active,
                SUM(
                    CASE
                        WHEN status='redeemed'
                         AND applied_until IS NOT NULL
                         AND applied_until<=? THEN 1
                        ELSE 0
                    END
                ) AS expired
            FROM activation_codes
            WHERE created_by_reseller_id=? AND deleted_at IS NULL
            """,
            (now, now, int(reseller_id)),
        ).fetchone()
    finally:
        cx.close()
    return {
        "total": int(row["total"] or 0),
        "available": int(row["available"] or 0),
        "active": int(row["active"] or 0),
        "expired": int(row["expired"] or 0),
    }


def delete_reseller_code(reseller_id: int, code: str) -> tuple[bool, str]:
    success, message = delete_activation_code(code, reseller_id=reseller_id)
    if not success:
        return False, "Code introuvable ou non autorisé."
    return True, message
