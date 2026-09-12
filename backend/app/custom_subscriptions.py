from __future__ import annotations

import uuid
from pydantic import BaseModel, Field

from .db import transaction
from .security import activation_code_for_source, code_hash
from .services import now_ts


class CustomSubscriptionRequest(BaseModel):
    days: int = Field(strict=True, ge=1, le=3650)


def issue_custom_subscription(days: int) -> tuple[str, str]:
    if type(days) is not int or not 1 <= days <= 3650:
        raise ValueError("Choisissez un nombre entier de jours entre 1 et 3650.")
    plan_id = f"custom_{days}d"
    source = f"MANUAL:CUSTOM:{days}:{uuid.uuid4().hex}"
    code = activation_code_for_source(source)
    with transaction() as cx:
        cx.execute(
            """INSERT INTO activation_codes(
                code_hash, source_ref, plan_id, duration_seconds, status, created_at
            ) VALUES(?,?,?,?,'issued',?)""",
            (code_hash(code), source, plan_id, days * 86400, now_ts()),
        )
    # Same storage and redemption path as all individual subscription codes.
    return plan_id, code
