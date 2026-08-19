#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  cp .env.example .env
fi

python3 - <<'PY'
from pathlib import Path
import secrets

p = Path(".env")
text = p.read_text()

def replace_once(name, value):
    global text
    old = f"{name}=CHANGE_ME"
    if old in text:
        text = text.replace(old, f"{name}={value}", 1)

replace_once("CODE_SECRET", secrets.token_urlsafe(48))
replace_once("ADMIN_TOKEN", secrets.token_urlsafe(36))
p.write_text(text)
PY

echo "✅ Secrets locaux générés dans backend/.env"
echo "⚠️ Il reste à renseigner PUBLIC_BASE_URL, LOMOPAY_PUBLIC_KEY et LOMOPAY_SECRET_KEY."
