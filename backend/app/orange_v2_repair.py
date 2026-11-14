"""One-time exact copy requested for Orange V2; keep Orange untouched."""
import json

KEY = "lot27_orange_v2_repair"


def repair(cx, now):
    cx.execute("BEGIN IMMEDIATE")
    try:
        if cx.execute("SELECT 1 FROM admin_state WHERE key=?", (KEY,)).fetchone():
            cx.commit()
            return
        source = cx.execute("SELECT * FROM vpn_profiles WHERE network_id='orange_bf'").fetchone()
        target = cx.execute("SELECT * FROM vpn_profiles WHERE network_id='orange_bf_v2'").fetchone()
        if not source or not target or source['protocol'] != 'VLESS' or not source['enabled']:
            cx.commit()
            return
        config = json.loads(source['config_json'])
        complete = isinstance(config, dict) and (
            str(config.get('uri', '')).startswith('vless://') or
            all(config.get(k) for k in ('address', 'port', 'uuid', 'network', 'security', 'host', 'sni', 'path'))
        )
        if not complete:
            cx.commit()
            return
        # Preserve the original V2 configuration for recovery. The marker and
        # update are atomic, so later administrator edits are never overwritten.
        backup = {'previous_v2': dict(target), 'source_version': source['version']}
        cx.execute("INSERT INTO admin_state(key,value,updated_at) VALUES(?,?,?)",
                   (KEY, json.dumps(backup, ensure_ascii=False), now))
        cx.execute("""UPDATE vpn_profiles SET protocol=?, config_json=?,
                      version=version+1, updated_at=? WHERE network_id='orange_bf_v2'""",
                   (source['protocol'], source['config_json'], now))
        cx.commit()
    except Exception:
        cx.rollback()
        raise
