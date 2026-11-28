#!/usr/bin/env python3
"""Apply only reviewed Redeem/payment sections, preserving all other live code."""
import ast
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile

FILES = {'admin_ops.py', 'admin_panel.py', 'models.py', 'pricing.py', 'reseller_purchases.py', 'resellers.py'}
ROOT = Path('/opt/Barka-Tunnel/backend')


def merge(root, patches):
    result = {}
    for patch in patches:
        name = patch['file']
        if name not in FILES:
            raise ValueError('Fichier non autorisé : '+name)
        path = root / 'app' / name
        if not stat.S_ISREG(path.lstat().st_mode):
            raise ValueError('Fichier régulier requis : '+name)
        source = result.get(name, path.read_text())
        new = patch['new']
        if source.count(new) == 1:
            result[name] = source
            continue
        matches = [old for old in patch['old'] if source.count(old) == 1]
        if len(matches) != 1:
            raise ValueError('Source différente : '+name+' ; aucun fichier modifié. Envoyez ce résultat pour adapter le correctif.')
        result[name] = source.replace(matches[0], new, 1)
    for name, source in result.items():
        ast.parse(source, filename=name)
    return result


def replace(path, content):
    info = path.stat()
    fd, temp = tempfile.mkstemp(prefix='.lot53-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as out:
            out.write(content); out.flush(); os.fsync(out.fileno())
            os.fchown(out.fileno(), info.st_uid, info.st_gid)
            os.fchmod(out.fileno(), stat.S_IMODE(info.st_mode))
        os.replace(temp, path)
    finally:
        Path(temp).unlink(missing_ok=True)


def run(*args, root=ROOT):
    return subprocess.run(args, cwd=root, check=True, text=True, capture_output=True, timeout=600).stdout


def restart(root):
    run('docker','compose','up','-d','--build','--no-deps','barka-backend',root=root)
    raw = run('curl','-fsS','--noproxy','*','--retry','12','--retry-delay','2','--retry-all-errors',
              '--connect-timeout','5','--max-time','10','http://127.0.0.1:8085/health',root=root)
    if not json.loads(raw).get('ok'):
        raise RuntimeError('Contrôle santé du backend échoué')
    catalog = json.loads(run('curl','-fsS','--noproxy','*','--max-time','10',
                            'http://127.0.0.1:8085/v1/pricing',root=root))
    if not all(isinstance(catalog.get(k), bool) for k in ('subscription_payments_enabled','reseller_payments_enabled')):
        raise RuntimeError('Contrôle des achats indisponible')


def install(manifest, root=ROOT):
    patches=json.loads(Path(manifest).read_text())
    for name in ('.env','docker-compose.yml','Dockerfile'):
        if not (root/name).is_file(): raise ValueError('Installation backend non reconnue : '+name)
    fd=os.open('/tmp/barka-backend-deploy.lock',os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        merged=merge(root,patches)
        before={name:(root/'app'/name).read_text() for name in merged}
        changed={name:source for name,source in merged.items() if source!=before[name]}
        # Do not silently deploy stale disk sources over a newer running container.
        probe='import hashlib,json; from pathlib import Path; print(json.dumps({n:hashlib.sha256((Path("/app/app")/n).read_bytes()).hexdigest() for n in '+repr(sorted(merged))+'}))'
        live=json.loads(run('docker','exec','barka-backend','python','-c',probe,root=root))
        for name,source in before.items():
            if live.get(name)!=hashlib.sha256(source.encode()).hexdigest():
                raise ValueError('Sources disque/conteneur différentes : '+name+' ; arrêt sans modification.')
        if not changed:
            print('Correctifs panel déjà installés.');return
        backups=root.parent/'backups';backups.mkdir(mode=0o700,exist_ok=True)
        saved=Path(tempfile.mkdtemp(prefix='lot53-panel-',dir=backups))
        for name in changed: shutil.copy2(root/'app'/name,saved/name)
        print('Sauvegarde panel : '+str(saved),flush=True)
        try:
            for name,source in changed.items(): replace(root/'app'/name,source)
            restart(root)
        except BaseException:
            for name in changed: replace(root/'app'/name,before[name])
            # Rebuild the previous image; no database restore or customer-data writes.
            run('docker','compose','up','-d','--build','--no-deps','barka-backend',root=root)
            raise
        print('LOT53 : Redeem et autorisation des achats installés ; santé vérifiée.',flush=True)


if __name__=='__main__':
    try: install(sys.argv[1])
    except Exception as exc:
        # Never print subprocess stderr that might contain environment credentials.
        sys.exit('Publication panel arrêtée : '+str(exc))
