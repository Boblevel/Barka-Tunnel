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
