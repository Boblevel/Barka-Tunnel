"""Admin-only persistent VPN drafts; publishing is one SQLite transaction."""
from __future__ import annotations

import hashlib
import json

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, Field

from .db import transaction
from .models import AdminVpnProfileUpsert
from .security import require_admin
from .services import iso, now_ts
from .vpn_profiles import EXPECTED_PROTOCOL, _decode_config, _row_to_public

router = APIRouter(prefix="/v1/admin/vpn", dependencies=[Depends(require_admin)])
KEY = "vpn_profile_drafts"


class DraftSave(BaseModel):
    revision: str = Field(min_length=64, max_length=64)
    profile: AdminVpnProfileUpsert


class DraftApply(BaseModel):
    revision: str = Field(min_length=64, max_length=64)


def _snapshot(cx):
    rows = cx.execute("SELECT * FROM vpn_profiles ORDER BY priority, network_id").fetchall()
    stored = cx.execute("SELECT value FROM admin_state WHERE key=?", (KEY,)).fetchone()
    drafts = json.loads(stored["value"]) if stored else {}
    raw = json.dumps([list(map(dict, rows)), drafts], sort_keys=True, ensure_ascii=False)
    revision = hashlib.sha256(raw.encode()).hexdigest()
    profiles = []
    for row in rows:
        item = _row_to_public(row)
        item["config"] = _decode_config(row["config_json"])
        draft = drafts.get(row["network_id"])
        if draft:
            item.update(draft["profile"])
        elif item["priority"] == 100:
            # Old default becomes the editor's new default, without publishing it.
            item["priority"] = 1
        item["pending"] = draft is not None
        profiles.append(item)
    return rows, drafts, {"revision": revision, "profiles": profiles, "pending_count": len(drafts)}


def _check_revision(actual, expected):
    if actual != expected:
        raise HTTPException(409, "Les profils ont changé dans une autre session. Actualisez avant de continuer.")


def _validate(profile):
    payload = AdminVpnProfileUpsert.model_validate(profile).model_dump()
    if EXPECTED_PROTOCOL.get(payload["network_id"]) != payload["protocol"]:
        raise HTTPException(400, "Le protocole ne correspond pas à cet opérateur.")
    config = payload["config"]
    if payload["enabled"] and not config:
        raise HTTPException(400, "Impossible d'activer un service sans configuration.")
    if payload["enabled"] and payload["protocol"] == "SLOWDNS":
        pub = config.get("dns_public_key") or config.get("dnstt_public_key") or config.get("public_key") or config.get("publicKey")
        ns = config.get("nameserver") or config.get("name_server") or config.get("ns")
        if not pub or not ns:
            raise HTTPException(400, "SlowDNS incomplet : renseignez le Name Server et la DNS Public Key.")
    return payload


@router.get("/drafts")
def get_drafts(response: Response):
    response.headers["Cache-Control"] = "no-store"
    with transaction() as cx:
        return _snapshot(cx)[2]


@router.put("/drafts")
def save_draft(body: DraftSave, response: Response):
    response.headers["Cache-Control"] = "no-store"
    payload = _validate(body.profile.model_dump())
    with transaction() as cx:
        rows, drafts, state = _snapshot(cx)
        _check_revision(state["revision"], body.revision)
        row = next((r for r in rows if r["network_id"] == payload["network_id"]), None)
        if row is None:
            raise HTTPException(404, "Profil introuvable.")
        drafts[payload["network_id"]] = {"base_version": row["version"], "profile": payload}
        cx.execute("""INSERT INTO admin_state(key,value,updated_at) VALUES(?,?,?)
                      ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at""",
                   (KEY, json.dumps(drafts, ensure_ascii=False), now_ts()))
        return _snapshot(cx)[2]


@router.post("/apply")
def apply_drafts(body: DraftApply, response: Response):
    response.headers["Cache-Control"] = "no-store"
    with transaction() as cx:
        rows, drafts, state = _snapshot(cx)
        _check_revision(state["revision"], body.revision)
        versions = {r["network_id"]: r["version"] for r in rows}
        validated = []
        for network_id, draft in drafts.items():
            if versions.get(network_id) != draft["base_version"]:
                raise HTTPException(409, "Un profil publié a changé. Enregistrez à nouveau son brouillon avant de valider.")
            validated.append(_validate(draft["profile"]))
        now = now_ts()
        for p in validated:
            cx.execute("""UPDATE vpn_profiles SET display_name=?, protocol=?, enabled=?,
                       maintenance=?, priority=?, version=version+1, config_json=?, updated_at=?
                       WHERE network_id=?""",
                       (p["display_name"].strip(), p["protocol"], int(p["enabled"]),
                        int(p["maintenance"]), p["priority"],
                        json.dumps(p["config"], ensure_ascii=False, separators=(",", ":")), now, p["network_id"]))
        cx.execute("DELETE FROM admin_state WHERE key=?", (KEY,))
        result = _snapshot(cx)[2]
        result.update(applied_count=len(validated), applied_at=iso(now))
        return result
