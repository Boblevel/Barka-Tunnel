#!/usr/bin/env python3
"""Publish only the approved immutable guide and its exact Nginx route."""
import fcntl
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

SHA = "2e66858b2a9fa9c239c8f237febcc71d3c58509b2874841a74a7ef40eb40fcc5"
NAME = "barka-guide-2e66858b2a9fa9c2.mp4"
URL = "https://api.rhaffservice.com/media/" + NAME
CONF = Path("/etc/nginx/conf.d/barka-api-com.conf")
TARGET = Path("/var/www/barka-media") / NAME
BASELINE = """server {
    listen 443 ssl;
    server_name api.rhaffservice.com;
    ssl_certificate /etc/letsencrypt/live/api.rhaffservice.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.rhaffservice.com/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;
    location / {
        proxy_pass http://127.0.0.1:8085;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
server {
    listen 80;
    server_name api.rhaffservice.com;
    return 301 https://$host$request_uri;
}
"""
LOCATION = """    location = /media/barka-guide-2e66858b2a9fa9c2.mp4 {
        alias /var/www/barka-media/barka-guide-2e66858b2a9fa9c2.mp4;
        default_type video/mp4;
        add_header Cache-Control "public, max-age=31536000, immutable";
        add_header X-Content-Type-Options nosniff;
    }

"""


def normalized(text):
    return [line.strip() for line in text.splitlines() if line.strip()]


def updated_config(text):
    expected = BASELINE.replace("    location / {", LOCATION + "    location / {", 1)
    if normalized(text) == normalized(expected):
        return text
    if normalized(text) != normalized(BASELINE):
        raise ValueError("Configuration Nginx différente du fichier validé : arrêt sans modification.")
    return re.sub(r"(?m)^[ \t]*location\s+/\s*\{",
                  lambda m: LOCATION + m.group(0), text, count=1)


def digest(path):
    result = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def run(args):
    return subprocess.run(args, check=True, timeout=60)


def atomic_config(data, metadata):
    fd, name = tempfile.mkstemp(prefix=".barka-video-", dir=CONF.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, metadata.st_mode & 0o7777)
        os.chown(name, metadata.st_uid, metadata.st_gid)
        os.replace(name, CONF)
    finally:
        Path(name).unlink(missing_ok=True)


def verify_local(media, directory):
    headers, body = directory / "headers.txt", directory / "range.bin"
    run(["curl", "--fail", "--silent", "--show-error", "--noproxy", "*",
         "--resolve", "api.rhaffservice.com:443:127.0.0.1",
         "--connect-timeout", "10", "--max-time", "30", "--range", "0-31",
         "--dump-header", str(headers), "--output", str(body), URL])
    text = headers.read_text().lower()
    if not re.search(r"^http/\S+ 206\b", text, re.M) or "content-type: video/mp4" not in text:
        raise RuntimeError("Réponse vidéo locale incorrecte (HTTP 206 / video/mp4 requis).")
    with media.open("rb") as stream:
        if body.read_bytes() != stream.read(32):
            raise RuntimeError("Contenu vidéo local incorrect.")


def publish(media):
    if os.geteuid() != 0:
        raise PermissionError("Exécution root requise pour cette publication ciblée.")
    if digest(media) != SHA:
        raise ValueError("Empreinte de la vidéo incorrecte.")
    if CONF.is_symlink() or not CONF.is_file():
        raise ValueError("Configuration Nginx absente ou lien symbolique.")
    with open("/run/lock/barka-video-publication.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        original = CONF.read_bytes()
        updated = updated_config(original.decode()).encode()
        metadata = CONF.stat()
        run(["nginx", "-t"])
        backup = Path(tempfile.mkdtemp(prefix="barka-video-backup-", dir="/root"))
        shutil.copy2(CONF, backup / CONF.name)
        print("Sauvegarde conservée :", backup, flush=True)
        if TARGET.parent.is_symlink() or TARGET.is_symlink():
            raise ValueError("Destination vidéo ambiguë.")
        TARGET.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        if TARGET.exists():
            if digest(TARGET) != SHA:
                raise ValueError("Vidéo existante différente : aucun écrasement.")
        else:
            fd, temporary = tempfile.mkstemp(prefix=".barka-video-", dir=TARGET.parent)
            os.close(fd)
            try:
                shutil.copyfile(media, temporary)
                os.chmod(temporary, 0o644)
                if digest(temporary) != SHA:
                    raise ValueError("Copie vidéo incorrecte.")
                os.link(temporary, TARGET)
            finally:
                Path(temporary).unlink(missing_ok=True)
        changed = original != updated
        reload_attempted = False
        try:
            if changed:
                if CONF.read_bytes() != original:
                    raise RuntimeError("Configuration modifiée pendant la préparation.")
                atomic_config(updated, metadata)
                run(["nginx", "-t"])
                reload_attempted = True
                run(["systemctl", "reload", "nginx"])
            verify_local(media, backup)
        except BaseException:
            if changed and CONF.read_bytes() == updated:
                atomic_config(original, metadata)
                run(["nginx", "-t"])
                if reload_attempted:
                    run(["systemctl", "reload", "nginx"])
            raise
        print("Vidéo vérifiée localement :", URL)
        print("SHA256=" + SHA)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage : lot43_publish_video.py VIDEO.mp4")
    publish(Path(sys.argv[1]))
