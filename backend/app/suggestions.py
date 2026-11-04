"""Client suggestions, with an administrator-controlled server-side switch."""
from datetime import datetime, timezone
import time
from uuid import UUID
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, Field, StrictBool, field_validator

from .db import transaction
from .models import DeviceRequest
from .moderation import state as moderation_state
from .security import require_admin

router = APIRouter()


class SuggestionRequest(DeviceRequest):
    request_id: UUID
    message: str = Field(min_length=3, max_length=2000)
    sender_name: str = Field(default="", max_length=80)

    @field_validator('message', 'sender_name', mode='before')
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
        flags = moderation_state(cx, body.device_id)
        if flags['app_blocked'] or flags['suggestions_blocked']:
            raise HTTPException(403, 'Votre accès aux suggestions a été suspendu. Contact : barkatunnel@gmail.com')
        existing = cx.execute('SELECT * FROM suggestions WHERE device_id=? AND request_id=?',
                              (body.device_id, str(body.request_id))).fetchone()
        if existing:
            if existing['message'] != body.message or existing['sender_name'] != body.sender_name:
                raise HTTPException(409, 'Cet envoi existe déjà avec un autre contenu.')
            return {'success': True, 'id': existing['id']}
        if not enabled_in(cx):
            raise HTTPException(403, 'Les suggestions sont temporairement fermées.')
        if cx.execute('SELECT 1 FROM suggestions WHERE device_id=? AND created_at>? LIMIT 1',
                      (body.device_id, now - 60)).fetchone():
            raise HTTPException(429, 'Veuillez patienter une minute entre deux suggestions.')
        cursor = cx.execute('INSERT INTO suggestions(device_id,request_id,message,created_at,sender_name) VALUES(?,?,?,?,?)',
                            (body.device_id, str(body.request_id), body.message, now, body.sender_name))
        return {'success': True, 'id': cursor.lastrowid}


@router.get('/v1/admin/suggestions', dependencies=[Depends(require_admin)])
def list_suggestions(response: Response, offset: int = Query(0, ge=0), limit: int = Query(50, ge=1, le=100)):
    response.headers['Cache-Control'] = 'no-store'
    with transaction() as cx:
        rows = cx.execute('SELECT id,device_id,message,created_at,read_at,sender_name FROM suggestions ORDER BY id DESC LIMIT ? OFFSET ?',
                          (limit, offset)).fetchall()
        return {'enabled': enabled_in(cx), 'total': cx.execute('SELECT COUNT(*) FROM suggestions').fetchone()[0],
                'items': [dict(row, created_at=stamp(row['created_at']), **moderation_state(cx, row['device_id'])) for row in rows]}


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


class ModerationChange(DeviceRequest):
    scope: Literal['app', 'suggestions']
    blocked: StrictBool


@router.post('/v1/moderation/status')
def moderation_status(body: DeviceRequest, response: Response):
    response.headers['Cache-Control'] = 'no-store'
    with transaction() as cx:
        return moderation_state(cx, body.device_id)


@router.get('/v1/admin/moderation', dependencies=[Depends(require_admin)])
def moderation_list(response: Response, offset: int = Query(0, ge=0), limit: int = Query(50, ge=1, le=100)):
    response.headers['Cache-Control'] = 'no-store'
    with transaction() as cx:
        rows = cx.execute('SELECT * FROM device_moderation WHERE app_blocked=1 OR suggestions_blocked=1 ORDER BY updated_at DESC,device_id LIMIT ? OFFSET ?', (limit, offset)).fetchall()
        return {'items': [dict(row) for row in rows]}


@router.put('/v1/admin/moderation', dependencies=[Depends(require_admin)])
def moderation_change(body: ModerationChange):
    with transaction() as cx:
        if not cx.execute('SELECT 1 FROM suggestions WHERE device_id=? UNION SELECT 1 FROM device_moderation WHERE device_id=?', (body.device_id, body.device_id)).fetchone():
            raise HTTPException(404, 'Auteur de suggestion introuvable.')
        before = moderation_state(cx, body.device_id)
        after = dict(before)
        after[body.scope + '_blocked'] = body.blocked
        cx.execute('INSERT INTO device_moderation(device_id,app_blocked,suggestions_blocked,updated_at) VALUES(?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET app_blocked=excluded.app_blocked,suggestions_blocked=excluded.suggestions_blocked,updated_at=excluded.updated_at',
                   (body.device_id, int(after['app_blocked']), int(after['suggestions_blocked']), int(time.time())))
        if before['app_blocked'] != after['app_blocked']:
            cx.execute('UPDATE devices SET access_revision=access_revision+1 WHERE device_id=?', (body.device_id,))
        return after
