"""Validated administrator-managed WhatsApp destination."""
import time
from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, ConfigDict, Field
from .db import transaction
from .security import require_admin
router = APIRouter()
class SupportConfig(BaseModel):
    model_config = ConfigDict(extra='forbid')
    whatsapp_number: str = Field(pattern=r'^[1-9][0-9]{7,14}$')
@router.get('/v1/support/config')
def get_support(response: Response):
    response.headers['Cache-Control'] = 'no-store'
    with transaction() as cx:
        row = cx.execute("SELECT value FROM admin_state WHERE key='support_whatsapp'").fetchone()
    return SupportConfig(whatsapp_number=row['value'] if row else '19852212404')
@router.put('/v1/admin/support/config', dependencies=[Depends(require_admin)])
def set_support(body: SupportConfig, response: Response):
    with transaction() as cx:
        cx.execute("INSERT INTO admin_state(key,value,updated_at) VALUES('support_whatsapp',?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at", (body.whatsapp_number,int(time.time())))
    response.headers['Cache-Control'] = 'no-store'
    return body

class VideoGuideConfig(BaseModel):
    model_config = ConfigDict(extra='forbid')
    guide: str = Field(default='', max_length=256)
    ip_finder: str = Field(default='', max_length=256)


def video_id(value: str) -> str:
    import re
    from urllib.parse import urlsplit, parse_qs
    if not value: return ''
    parts = urlsplit(value)
    if parts.scheme != 'https' or parts.username or parts.password or parts.port:
        raise ValueError('Lien YouTube invalide.')
    host = (parts.hostname or '').lower()
    if host == 'youtu.be': identifier = parts.path.strip('/')
    elif host in ('youtube.com', 'www.youtube.com', 'm.youtube.com'):
        identifier = parse_qs(parts.query).get('v', [''])[0] if parts.path == '/watch' else (parts.path.split('/')[2] if parts.path.startswith('/embed/') and len(parts.path.split('/')) == 3 else '')
    else: identifier = ''
    if not re.fullmatch(r'[A-Za-z0-9_-]{11}', identifier): raise ValueError('Lien YouTube invalide.')
    return 'https://www.youtube.com/watch?v=' + identifier

@router.get('/v1/video-guides')
def get_video_guides(response: Response):
    response.headers['Cache-Control'] = 'no-store'
    with transaction() as cx:
        rows = dict(cx.execute("SELECT key,value FROM admin_state WHERE key IN ('video_guide','video_ip_finder')").fetchall())
    return VideoGuideConfig(guide=rows.get('video_guide','https://www.youtube.com/watch?v=NUe3oG-R5hI'), ip_finder=rows.get('video_ip_finder',''))

@router.put('/v1/admin/video-guides', dependencies=[Depends(require_admin)])
def set_video_guides(body: VideoGuideConfig, response: Response):
    from fastapi import HTTPException
    try: guide, ip = video_id(body.guide.strip()), video_id(body.ip_finder.strip())
    except ValueError as e: raise HTTPException(422, str(e)) from e
    with transaction() as cx:
        for key, value in [('video_guide',guide),('video_ip_finder',ip)]:
            cx.execute('INSERT INTO admin_state(key,value,updated_at) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at', (key,value,int(time.time())))
    response.headers['Cache-Control']='no-store'
    return VideoGuideConfig(guide=guide,ip_finder=ip)
