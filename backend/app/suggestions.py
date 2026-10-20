"""Client suggestions, with an administrator-controlled server-side switch."""
from datetime import datetime, timezone
import time
from uuid import UUID
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, Field, StrictBool, field_validator

from .db import transaction
from .models import DeviceRequest
from .security import require_admin

router = APIRouter()


class SuggestionRequest(DeviceRequest):
    request_id: UUID
    message: str = Field(min_length=3, max_length=2000)

    @field_validator('message', mode='before')
    @classmethod
    def trim_message(cls, value):
        return value.strip() if isinstance(value, str) else value


class SuggestionSwitch(BaseModel):
    enabled: StrictBool


def enabled_in(cx):
    row = cx.execute("SELECT value FROM admin_state WHERE key='suggestions_enabled'").fetchone()
    return row is None or row['value'] == '1'


def stamp(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat()


@router.get('/v1/suggestions/config')
def config(response: Response):
    response.headers['Cache-Control'] = 'no-store'
    with transaction() as cx:
        return {'enabled': enabled_in(cx)}


@router.post('/v1/suggestions')
def submit(body: SuggestionRequest):
    now = int(time.time())
    with transaction() as cx:
        existing = cx.execute('SELECT * FROM suggestions WHERE device_id=? AND request_id=?',
                              (body.device_id, str(body.request_id))).fetchone()
        if existing:
            if existing['message'] != body.message:
                raise HTTPException(409, 'Cet envoi existe déjà avec un autre contenu.')
            return {'success': True, 'id': existing['id']}
        if not enabled_in(cx):
            raise HTTPException(403, 'Les suggestions sont temporairement fermées.')
        if cx.execute('SELECT 1 FROM suggestions WHERE device_id=? AND created_at>? LIMIT 1',
                      (body.device_id, now - 60)).fetchone():
            raise HTTPException(429, 'Veuillez patienter une minute entre deux suggestions.')
        cursor = cx.execute('INSERT INTO suggestions(device_id,request_id,message,created_at) VALUES(?,?,?,?)',
                            (body.device_id, str(body.request_id), body.message, now))
        return {'success': True, 'id': cursor.lastrowid}


@router.get('/v1/admin/suggestions', dependencies=[Depends(require_admin)])
def list_suggestions(response: Response, offset: int = Query(0, ge=0), limit: int = Query(50, ge=1, le=100)):
    response.headers['Cache-Control'] = 'no-store'
    with transaction() as cx:
        rows = cx.execute('SELECT id,device_id,message,created_at,read_at FROM suggestions ORDER BY id DESC LIMIT ? OFFSET ?',
                          (limit, offset)).fetchall()
        return {'enabled': enabled_in(cx), 'total': cx.execute('SELECT COUNT(*) FROM suggestions').fetchone()[0],
                'items': [dict(row, created_at=stamp(row['created_at'])) for row in rows]}


@router.put('/v1/admin/suggestions/config', dependencies=[Depends(require_admin)])
def set_config(body: SuggestionSwitch):
    with transaction() as cx:
        cx.execute("INSERT INTO admin_state(key,value,updated_at) VALUES('suggestions_enabled',?,?) "
                   "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
                   ('1' if body.enabled else '0', int(time.time())))
    return {'enabled': body.enabled}


class SuggestionRead(BaseModel):
    ids: list[int] = Field(min_length=1, max_length=100)


class SuggestionReset(BaseModel):
    confirmation: Literal['SUPPRIMER']


def unread_count(cx):
    return cx.execute('SELECT COUNT(*) FROM suggestions WHERE read_at IS NULL').fetchone()[0]


@router.get('/v1/admin/suggestions/unread', dependencies=[Depends(require_admin)])
def unread(response: Response):
    response.headers['Cache-Control'] = 'no-store'
    with transaction() as cx:
        return {'unread': unread_count(cx)}


@router.post('/v1/admin/suggestions/read', dependencies=[Depends(require_admin)])
def mark_read(body: SuggestionRead):
    with transaction() as cx:
        placeholders = ','.join('?' for _ in body.ids)
        cx.execute(f'UPDATE suggestions SET read_at=? WHERE read_at IS NULL AND id IN ({placeholders})',
                   (int(time.time()), *body.ids))
        return {'unread': unread_count(cx)}


@router.post('/v1/admin/suggestions/reset', dependencies=[Depends(require_admin)])
def reset(body: SuggestionReset):
    with transaction() as cx:
        deleted = cx.execute('DELETE FROM suggestions').rowcount
        # Keep AUTOINCREMENT: stale read requests must never mark future messages read.
        return {'deleted': deleted, 'unread': 0}
