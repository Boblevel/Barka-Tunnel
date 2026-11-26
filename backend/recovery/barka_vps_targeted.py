#!/usr/bin/env python3
"""Apply five narrowly scoped RHAFF/Barka changes to EXACTLY audited live VPS files.

This never copies a full GitHub backend over the VPS, does not alter payment
integration, and refuses modified/unrecognized source files.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import sys
import tempfile

FILES = {
    "admin_panel.py": "b6d4b11cdc95f1a317e42f24ca51d9838001b1d815dcd7bcaf510730de8bd12c",
    "admin_ops.py": "6dc1fcc2e8d9de515a5c1a7dd15cffabaaac692045c50e03ce5649c5504c6613",
    "db.py": "fac1ced0bda473ffa71aed232d7ae0e3de179b6652b195cca8cd9f83c6a4c48d",
    "main.py": "f9cbc34c6dcc0226480a81b79aba11d438628f70c5a992004bf6b91b3d561f56",
    "models.py": "92ccf02ffc245af5fb19da890a42e56d0ba60ac69ba75886ea060c4023c9fe3a",
}
PROTECTED = {
    "services.py": "88dd67eef44aac67d7c5d1b1378c1ec13f782c0226393f67832b69a975301ca4",
    "saspay.py": "2ea473e798936bbca4c95f538fa39349c82126df61748933cecfb02a9990867b",
}
# Precomputed from the user-supplied VPS snapshot. Populated after patch tests.
TARGET = {'admin_panel.py': 'd3b612c8deb41d76ee61ce210597fefef693e7b42cee8c946622ca72deab70e1', 'admin_ops.py': '562f481faf5af957a011817b18c82592f40c9113402b641d6107dfbfe5aa580b', 'db.py': '28d441dc0ce9b8e9579fa314371b21c8841fe2e4daf9595506f600f2cf920d81', 'main.py': 'd2ff9d5c9a423c81bd2a22ac5d5838fff33daaa7bf5b5656090c5f0f91a70fb6', 'models.py': '3111fbb99af54fbcc5ad9bd98dd8c21d692d7943d65c02737ef91283afb2e49d'}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def replace_once(src: str, old: str, new: str, name: str) -> str:
    count = src.count(old)
    if count != 1:
        raise ValueError(f"STOP: {name}: expected exactly one matching source anchor, found {count}")
    return src.replace(old, new, 1)


def patch_admin_panel(src: str) -> str:
    old = '''<div id="resellerTotal" class="status" role="status">Total des sous-panels : …</div><div id="resellers"></div>'''
    new = '''<div class="row mobile-stack"><div><label for="resellerSearch">Rechercher un sous-panel</label><input id="resellerSearch" type="search" placeholder="Rechercher un nom ou un statut…" oninput="renderResellers()"></div><div><label for="resellerStatus">Statut</label><select id="resellerStatus" onchange="renderResellers()"><option value="">Tous les statuts</option><option value="active">Actifs</option><option value="frozen">Gelés</option><option value="expired">Expirés</option></select></div></div><div id="resellerTotal" class="status" role="status">Total des sous-panels : …</div><div id="resellers"></div>'''
    src = replace_once(src, old, new, "admin_panel/reseller HTML")
    old = '''function renderRedeemRows(list,emptyText){return list.length?list.map(c=>{const expired=c.status==='expired'||c.all_users_finished;return '<div class="code-row '+(expired?'expired':'')+'''
    new = '''function renderRedeemRows(list,emptyText){return list.length?list.map(c=>{const full=Number(c.max_users)>0&&Number(c.usage_count)>=Number(c.max_users);const expired=c.status==='expired'||c.all_users_finished||full;return '<div class="code-row '+(expired?'expired':'')+'''
    src = replace_once(src, old, new, "admin_panel/redeem red class")
    old = '''<span class="'+(expired?'expired-label':'')+'">'+esc(statusLabel(c.status))+'</span> • Durée : '''
    new = '''<span class="'+(expired?'expired-label':'')+'">'+(full&&c.status==='active'?(c.all_users_finished?'COMPLET / TERMINÉ':'COMPLET'):esc(statusLabel(c.status)))+'</span> • Durée : '''
    src = replace_once(src, old, new, "admin_panel/redeem label")
    old = "const items=[['Utilisateurs uniques',s.devices],['Connexions uniques',s.users],['Abonnements actifs',s.active_subscriptions]"
    new = "const items=[['Utilisateurs uniques',s.devices],['En ligne',s.vpn_online??0],['Abonnements actifs',s.active_subscriptions]"
    src = replace_once(src, old, new, "admin_panel/online card")
    old = '''  $('resellerTotal').textContent='Total des sous-panels : '+latestResellers.length;
  const opened=new Set('''
    new = '''  const query=($('resellerSearch')?.value||'').trim().toLowerCase();
  const status=$('resellerStatus')?.value||'';
  const filtered=latestResellers.filter(r=>(!status||r.status===status)&&(!query||[r.username,r.status,resellerStatus(r.status)].some(v=>String(v||'').toLowerCase().includes(query))));
  $('resellerTotal').textContent='Total des sous-panels : '+latestResellers.length+' • Affichés : '+filtered.length;
  const opened=new Set('''
    src = replace_once(src, old, new, "admin_panel/reseller filtering")
    src = replace_once(src,
        '''  if(!latestResellers.length){el.innerHTML='<div class="status">Aucun sous-panel revendeur.</div>';return}
  el.innerHTML=latestResellers.map(r=>{''',
        '''  if(!filtered.length){el.innerHTML='<div class="status">Aucun sous-panel correspondant.</div>';return}
  el.innerHTML=filtered.map(r=>{''',
        "admin_panel/reseller rows")
    old = "setInterval(()=>{if(TOKEN&&!document.hidden)refreshSuggestionBadge().catch(()=>{})},15000);"
    new = old + "\nsetInterval(()=>{if(TOKEN&&!document.hidden&&document.querySelector('.page.active')?.dataset.page==='dashboard')loadStats().catch(()=>{})},15000);"
    src = replace_once(src, old, new, "admin_panel/live refresh")
    return src


def patch_db(src: str) -> str:
    old = '''            if "last_connect_attempt_at" not in device_columns:
                cx.execute("ALTER TABLE devices ADD COLUMN last_connect_attempt_at INTEGER")'''
    new = old + '''
            if "vpn_session_id" not in device_columns:
                cx.execute("ALTER TABLE devices ADD COLUMN vpn_session_id TEXT")
            if "vpn_online_until" not in device_columns:
                cx.execute("ALTER TABLE devices ADD COLUMN vpn_online_until INTEGER NOT NULL DEFAULT 0")'''
    return replace_once(src, old, new, "db/migration")


def patch_models(src: str) -> str:
    old = '\n\nclass AccessResponse(BaseModel):'
    new = '''

class VpnPresenceRequest(DeviceRequest):
    session_id: str = Field(min_length=36, max_length=36, pattern=r"^[0-9a-fA-F-]{36}$")
    connected: bool


class AccessResponse(BaseModel):'''
    return replace_once(src, old, new, "models/VpnPresenceRequest")


def patch_admin_ops(src: str) -> str:
    old = '        enabled_profiles = cx.execute("SELECT COUNT(*) AS n FROM vpn_profiles WHERE enabled=1").fetchone()["n"]'
    new = old + '''
        vpn_online = cx.execute(
            "SELECT COUNT(*) AS n FROM devices WHERE vpn_online_until>?",
            (now,),
        ).fetchone()["n"]'''
    src = replace_once(src, old, new, "admin_ops/online query")
    src = replace_once(src,
        '        "users": int(users),\n        "payments_paid": int(paid),',
        '        "users": int(users),\n        "vpn_online": int(vpn_online),\n        "payments_paid": int(paid),',
        "admin_ops/online stat")
    return src


def patch_main(src: str) -> str:
    src = replace_once(src,
        'from .db import connect, init_db\n',
        'from .db import connect, init_db, transaction\n',
        'main/database transaction import')
    src = replace_once(src,
        '    DeviceRequest,\n    PaymentStartRequest,',
        '    DeviceRequest,\n    VpnPresenceRequest,\n    PaymentStartRequest,',
        'main/presence request import')
    src = replace_once(src,
        '    mark_connection_attempt,\n    pending_payments_for_reconciliation,',
        '    mark_connection_attempt,\n    now_ts,\n    pending_payments_for_reconciliation,',
        'main/now_ts import')
    old = '''@app.post("/v1/device/connection-attempt")
def device_connection_attempt(body: DeviceRequest):
    mark_connection_attempt(body.device_id)
    return {"ok": True}
'''
    new = old + '''

def mark_vpn_presence(device_id: str, session_id: str, connected: bool) -> None:
    # The latest session wins; older disconnects cannot terminate a newer session.
    now = now_ts()
    with transaction() as cx:
        if connected:
            cx.execute(
                """UPDATE devices SET vpn_session_id=?, vpn_online_until=?
                   WHERE device_id=?""",
                (session_id, now + 90, device_id),
            )
        else:
            cx.execute(
                "UPDATE devices SET vpn_online_until=0 WHERE device_id=? AND vpn_session_id=?",
                (device_id, session_id),
            )


@app.post("/v1/device/vpn-presence")
def device_vpn_presence(body: VpnPresenceRequest):
    mark_vpn_presence(body.device_id, body.session_id, body.connected)
    return {"ok": True}
'''
    return replace_once(src, old, new, "main/endpoint")


PATCHES = {
    "admin_panel.py": patch_admin_panel,
    "admin_ops.py": patch_admin_ops,
    "db.py": patch_db,
    "main.py": patch_main,
    "models.py": patch_models,
}


def build(root: pathlib.Path):
    directory = root / "app"
    for name, sha in PROTECTED.items():
        f = directory / name
        if f.is_symlink() or not f.is_file() or digest(f.read_bytes()) != sha:
            raise RuntimeError(f"STOP: protected payment source is not the audited VPS version: {name}")
    planned = {}
    for name, pre in FILES.items():
        f = directory / name
        if f.is_symlink() or not f.is_file():
            raise RuntimeError(f"STOP: source missing/symlink: {name}")
        original = f.read_bytes()
        original_sha = digest(original)
        if original_sha == TARGET.get(name):
            planned[name] = (f, None, original_sha)
            continue
        if original_sha != pre:
            raise RuntimeError(f"STOP: VPS {name} has changed since audit ({original_sha}), no change allowed")
        patched = PATCHES[name](original.decode("utf-8")).encode("utf-8")
        expected = TARGET.get(name)
        if expected and digest(patched) != expected:
            raise RuntimeError(f"STOP: internal patch checksum mismatch for {name}")
        compile(patched, name, "exec")
        planned[name] = (f, patched, digest(patched))
    return planned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    options = parser.add_mutually_exclusive_group(required=True)
    options.add_argument("--check", action="store_true", help="Read-only preflight")
    options.add_argument("--apply", action="store_true", help="Apply only audited exact changes")
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path('/opt/Barka-Tunnel/backend'))
    args = parser.parse_args()
    planned = build(args.root)
    for name, (_, data, sha) in planned.items():
        print(f"{name}: {'ALREADY APPLIED' if data is None else 'CHECKED'} target_SHA256={sha}")
    if args.check:
        print("READ-ONLY CHECK PASSED; NO FILE CHANGED")
        return
    # Preflight all five before touching anything. Deploy wrapper makes backups.
    for name, (path, data, sha) in planned.items():
        if data is None:
            continue
        fd, tmpname = tempfile.mkstemp(prefix='.barka-targeted-', dir=path.parent)
        try:
            os.fchmod(fd, path.stat().st_mode & 0o777)
            with os.fdopen(fd, 'wb') as out:
                out.write(data)
                out.flush()
                os.fsync(out.fileno())
            os.replace(tmpname, path)
            if digest(path.read_bytes()) != sha:
                raise RuntimeError(f"STOP: written SHA mismatch for {name}")
        finally:
            if os.path.exists(tmpname):
                os.unlink(tmpname)
    for name, (_, _, sha) in planned.items():
        assert digest((args.root / 'app' / name).read_bytes()) == sha, name
    print("SUCCESS: ONLY FIVE APPROVED FILES UPDATED; PAYMENTS UNCHANGED")


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, UnicodeError) as err:
        print(str(err), file=sys.stderr)
        sys.exit(1)
