"""Persistent, atomic throttling for credential guessing and paid-service creation."""
import hashlib
import hmac
import time
from urllib.parse import urlsplit
from fastapi import HTTPException, Request
from .config import settings
from .db import transaction


def consume_limits(limits, now=None):
    now = int(time.time()) if now is None else now
    retry = 0
    with transaction() as cx:
        cx.execute('CREATE TABLE IF NOT EXISTS security_rate_limits (key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL)')
        cx.execute('DELETE FROM security_rate_limits WHERE expires <= ?', (now,))
        for scope, identity, maximum, seconds in limits:
            key = hashlib.sha256((scope + ':' + identity).encode()).hexdigest()
            row = cx.execute('SELECT count,expires FROM security_rate_limits WHERE key=?', (key,)).fetchone()
            if row and row['count'] >= maximum:
                retry = max(retry, row['expires'] - now)
            else:
                cx.execute('INSERT INTO security_rate_limits(key,count,expires) VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1', (key, now + seconds))
    if retry:
        raise HTTPException(429, 'Trop de tentatives. Réessayez plus tard.', headers={'Retry-After': str(retry)})


def origin_tuple(value):
    try:
        parsed = urlsplit(value)
        if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password:
            return None
        return parsed.scheme, parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80)
    except ValueError:
        return None


async def protect_request(request: Request):
    path = request.url.path
    if not path.startswith('/v1/'):
        return
    if request.method in ('POST', 'PUT', 'PATCH', 'DELETE') and 'origin' in request.headers:
        origin = origin_tuple(request.headers['origin'])
        if origin is None or origin != origin_tuple(settings.public_base_url):
            raise HTTPException(403, 'Origine de la requête refusée')
    ip = request.client.host if request.client else 'unknown'
    if path.startswith('/v1/admin/'):
        supplied = request.headers.get('x-admin-token', '')
        if not settings.admin_token or not hmac.compare_digest(supplied.encode(), settings.admin_token.encode()):
            consume_limits([('admin', ip, 20, 300)])
        return
    rules = {
        '/v1/reseller/login': ('username', 10, 300, 60),
        '/v1/reseller-purchases/restore': ('username', 10, 300, 60),
        '/v1/activation/redeem': ('device_id', 20, 300, 120),
        '/v1/trial/start': ('device_id', 10, 300, 120),
        '/v1/payments/start': ('device_id', 10, 60, 120),
        '/v1/reseller-purchases/start': ('owner_key', 10, 60, 120),
    }
    if request.method != 'POST' or path not in rules:
        return
    field, maximum, seconds, ip_maximum = rules[path]
    consume_limits([(path + ':ip', ip, ip_maximum, seconds)])
    raw = await request.body()
    if len(raw) > 16384:
        raise HTTPException(413, 'Requête trop volumineuse')
    try:
        body = await request.json()
    except ValueError:
        return
    if isinstance(body, dict) and isinstance(body.get(field), str):
        identity = body[field].strip().casefold() if field == 'username' else body[field]
        consume_limits([(path + ':identity', identity, maximum, seconds)])
