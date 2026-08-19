#!/usr/bin/env bash
set -euo pipefail

BASE="${1:-http://127.0.0.1:8085}"
DEVICE="smoke-device-1234567890"

echo "== Santé =="
curl -fsS "$BASE/health"; echo

echo "== Plans =="
curl -fsS "$BASE/v1/plans"; echo

echo "== Démarrer essai =="
curl -fsS -X POST "$BASE/v1/trial/start" \
  -H 'Content-Type: application/json' \
  -d "{\"device_id\":\"$DEVICE\"}"; echo

echo "== Vérifier accès =="
curl -fsS -X POST "$BASE/v1/access/check" \
  -H 'Content-Type: application/json' \
  -d "{\"device_id\":\"$DEVICE\"}"; echo
