#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
: "${BARKA_VPS_HOST:?Secret BARKA_VPS_HOST manquant}" "${BARKA_VPS_USER:?Secret BARKA_VPS_USER manquant}" "${BARKA_VPS_SSH_KEY:?Secret BARKA_VPS_SSH_KEY manquant}"
port="${BARKA_VPS_PORT:-22}"
[[ "$port" =~ ^[0-9]+$ && "$port" -ge 1 && "$port" -le 65535 ]]
[[ "$BARKA_VPS_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]]
[[ "$BARKA_VPS_USER" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]]
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]]
sha256sum -c BarkaTunnel-release.apk.sha256
expected="$(sha256sum BarkaTunnel-release.apk | cut -d ' ' -f 1)"
private="$(mktemp -d "$RUNNER_TEMP/barka-apk-ssh.XXXXXXXX")"
trap 'rm -rf -- "$private"' EXIT
printf '%s\n' "$BARKA_VPS_SSH_KEY" > "$private/key"
if [[ -n "${BARKA_VPS_KNOWN_HOSTS:-}" ]]; then
  printf '%s\n' "$BARKA_VPS_KNOWN_HOSTS" > "$private/known_hosts"
else
  ssh-keyscan -T 20 -H -p "$port" "$BARKA_VPS_HOST" > "$private/known_hosts"
fi
test -s "$private/known_hosts"
opts=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$private/known_hosts" -o ConnectTimeout=30 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 -i "$private/key")
host="$BARKA_VPS_USER@$BARKA_VPS_HOST"
remote="$(ssh "${opts[@]}" -p "$port" "$host" 'umask 077; mktemp -d /tmp/barka-apk-lot53.XXXXXXXX')"
[[ "$remote" =~ ^/tmp/barka-apk-lot53\.[A-Za-z0-9]{8}$ ]]
scp "${opts[@]}" -P "$port" BarkaTunnel-release.apk scripts/lot49_install_apk.py scripts/lot53_install_backend.py scripts/lot53_backend_patch.json "$host:$remote/"
ssh "${opts[@]}" -p "$port" "$host" "python3 '$remote/lot53_install_backend.py' '$remote/lot53_backend_patch.json'"
ssh "${opts[@]}" -p "$port" "$host" "python3 '$remote/lot49_install_apk.py' '$remote/BarkaTunnel-release.apk' '$expected'"
url="https://api.rhaffservice.com/downloads/BarkaTunnel.apk?build=$GITHUB_SHA"
curl --fail --silent --show-error --location --retry 2 --retry-delay 3 \
  --connect-timeout 30 --max-time 600 --max-filesize 134217728 \
  "$url" --output "$private/public.apk"
test "$(sha256sum "$private/public.apk" | cut -d ' ' -f 1)" = "$expected"
ssh "${opts[@]}" -p "$port" "$host" "rm -f '$remote/lot53_install_backend.py' '$remote/lot53_backend_patch.json' '$remote/lot49_install_apk.py' '$remote/BarkaTunnel-release.apk'; rmdir '$remote'" || true
printf 'APK publié et téléchargement public vérifié : %s\nSHA256=%s\n' "$url" "$expected"
printf '%s\n' "$url" > BarkaTunnel-VPS.txt
printf '%s\n' "$GITHUB_SHA" >> BarkaTunnel-VPS.txt
printf '%s\n' "$expected" >> BarkaTunnel-VPS.txt
