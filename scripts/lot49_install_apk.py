#!/usr/bin/env python3
"""Publish only the validated APK; never deploy code, restart services or write the database."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import zipfile

TARGET = Path('/opt/Barka-Tunnel/backend/data/releases/BarkaTunnel.apk')
BACKUPS = Path('/opt/Barka-Tunnel/backups')
LOCK = Path('/tmp/barka-backend-deploy.lock')
URL = 'https://api.rhaffservice.com/downloads/BarkaTunnel.apk'
MAX_SIZE = 128 * 1024 * 1024


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def regular(path):
    info = Path(path).lstat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(f'Fichier régulier requis : {path}')
    return info


def verify_served(expected):
    with tempfile.TemporaryDirectory(prefix='barka-apk-check-') as folder:
        downloaded = Path(folder) / 'served.apk'
        subprocess.run([
            'curl', '--fail', '--silent', '--show-error', '--noproxy', '*',
            '--resolve', 'api.rhaffservice.com:443:127.0.0.1',
            '--connect-timeout', '15', '--max-time', '180',
            '--max-filesize', str(MAX_SIZE), '--output', str(downloaded),
            URL + '?verification=' + str(time.time_ns()),
        ], check=True, timeout=190)
        if digest(downloaded) != expected:
            raise ValueError('Le lien HTTPS ne sert pas le fichier APK attendu.')


def replace_from(source, target, info):
    fd, name = tempfile.mkstemp(prefix='.BarkaTunnel-lot49-', suffix='.apk', dir=target.parent)
    staged = Path(name)
    try:
        with os.fdopen(fd, 'wb') as out, Path(source).open('rb') as src:
            shutil.copyfileobj(src, out)
            os.fchown(out.fileno(), info.st_uid, info.st_gid)
            os.fchmod(out.fileno(), stat.S_IMODE(info.st_mode))
            out.flush()
            os.fsync(out.fileno())
        os.replace(staged, target)
        directory = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        staged.unlink(missing_ok=True)


def publish(source, expected, *, target=TARGET, backups=BACKUPS, lock=LOCK, verify=verify_served):
    source, target, backups, lock = map(Path, (source, target, backups, lock))
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Empreinte SHA256 invalide.')
    incoming = regular(source)
    if not 0 < incoming.st_size <= MAX_SIZE or digest(source) != expected:
        raise ValueError('APK transféré incomplet ou empreinte incorrecte.')
    with zipfile.ZipFile(source) as apk:
        if 'AndroidManifest.xml' not in apk.namelist() or apk.testzip() is not None:
            raise ValueError('Archive APK invalide.')
    descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as guard:
        fcntl.flock(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        info = regular(target)
        previous = digest(target)
        # Prove this exact existing file backs the live URL before replacing anything.
        verify(previous)
        if previous == expected:
            return {'sha256': expected, 'url': URL, 'already_published': True}
        backups.mkdir(mode=0o700, parents=True, exist_ok=True)
        backup = Path(tempfile.mkdtemp(prefix='lot49-apk-', dir=backups))
        saved = backup / 'BarkaTunnel.apk'
        shutil.copy2(target, saved)
        if digest(saved) != previous:
            raise ValueError('Échec de vérification de la sauvegarde ; APK actuel conservé.')
        (backup / 'receipt.json').write_text(json.dumps({
            'old_sha256': previous, 'new_sha256': expected, 'target': str(target),
        }, indent=2))
        try:
            replace_from(source, target, info)
            if digest(target) != expected:
                raise ValueError('Empreinte incorrecte après remplacement.')
            verify(expected)
        except Exception:
            # All deployers using the existing backend deployment lock are excluded here.
            if digest(target) != previous:
                replace_from(saved, target, info)
            if digest(target) != previous:
                raise RuntimeError('Échec de restauration ; sauvegarde : ' + str(saved))
            raise
        return {'sha256': expected, 'url': URL, 'backup': str(backup)}


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit('Usage : lot49_install_apk.py APK SHA256')
    print(json.dumps(publish(sys.argv[1], sys.argv[2])), flush=True)
