"""Bounded upload, immutable releases and resumable HTTP downloads."""
import errno
import fcntl
import hashlib
import json
import os
import re
import shutil
import tempfile
import time
import uuid
import zipfile
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool
from .security import require_admin
from .app_updates import release_apk_path
from .config import settings

router = APIRouter()
MAX_APK_BYTES = 300 * 1024 * 1024
CHUNK_BYTES = 512 * 1024


def validate_apk(path):
    try:
        with zipfile.ZipFile(path) as z:
            if 'AndroidManifest.xml' not in z.namelist():
                raise ValueError('Manifest absent')
    except (zipfile.BadZipFile, ValueError) as error:
        raise HTTPException(400, 'Fichier APK invalide.') from error


def publish(path, digest, size):
    target = release_apk_path().parent / (digest + '.apk')
    try:
        os.link(path, target)
    except FileExistsError:
        pass
    os.chmod(target, 0o644)
    return {'success': True, 'apk_url': settings.public_base_url + '/downloads/apk/' + digest + '.apk',
            'size_bytes': size, 'sha256': digest}


@router.put('/v1/admin/app-update/apk', dependencies=[Depends(require_admin)])
async def upload_apk(request: Request):
    root = release_apk_path().parent
    root.mkdir(parents=True, exist_ok=True)
    length = request.headers.get('content-length')
    if length and (not length.isdigit() or int(length) > MAX_APK_BYTES):
        raise HTTPException(413, 'APK trop volumineux (300 Mo max).')
    if shutil.disk_usage(root).free < (int(length) if length else MAX_APK_BYTES) + 16*1024*1024:
        raise HTTPException(507, 'Espace disque insuffisant sur le serveur.')
    fd, name = tempfile.mkstemp(prefix='.upload-', suffix='.apk', dir=root)
    size = 0
    digest = hashlib.sha256()
    try:
        with os.fdopen(fd, 'wb') as output:
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_APK_BYTES:
                    raise HTTPException(413, 'APK trop volumineux (300 Mo max).')
                digest.update(chunk)
                await run_in_threadpool(output.write, chunk)
            await run_in_threadpool(output.flush)
            await run_in_threadpool(os.fsync, output.fileno())
        if length and size != int(length):
            raise HTTPException(400, 'Téléversement incomplet.')
        await run_in_threadpool(validate_apk, name)
        return publish(name, digest.hexdigest(), size)
    except OSError as error:
        if error.errno in (errno.ENOSPC, errno.EDQUOT):
            raise HTTPException(507, 'Espace disque insuffisant.') from error
        raise
    finally:
        os.unlink(name)


@router.get('/downloads/apk/{checksum}.apk', include_in_schema=False)
def download_release(checksum: str):
    if not re.fullmatch('[a-f0-9]{64}', checksum):
        raise HTTPException(404, 'APK non publié')
    path = release_apk_path().parent / (checksum+'.apk')
    if not path.is_file():
        raise HTTPException(404, 'APK non publié')
    return FileResponse(path, media_type='application/vnd.android.package-archive', filename='BarkaTunnel.apk',
                        headers={'ETag': '"'+checksum+'"', 'Cache-Control': 'public, max-age=31536000, immutable', 'X-Content-Type-Options': 'nosniff'})


class UploadStart(BaseModel):
    size: int = Field(ge=1, le=MAX_APK_BYTES)


def session_paths(upload_id):
    if not re.fullmatch('[a-f0-9]{32}', upload_id):
        raise HTTPException(404, 'Téléversement introuvable.')
    root = release_apk_path().parent / '.uploads'
    meta = root / (upload_id+'.json')
    if not meta.is_file():
        raise HTTPException(404, 'Téléversement expiré. Réessayez.')
    return root/(upload_id+'.part'), json.loads(meta.read_text()), meta


@router.post('/v1/admin/app-update/uploads', dependencies=[Depends(require_admin)])
def begin_upload(body: UploadStart):
    root = release_apk_path().parent / '.uploads'
    root.mkdir(parents=True, exist_ok=True)
    for meta in root.glob('*.json'):
        if meta.stat().st_mtime < time.time()-86400:
            part = meta.with_suffix('.part')
            if part.is_file():
                with part.open('rb') as handle:
                    try: fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError: continue
                    part.unlink(missing_ok=True)
            meta.unlink(missing_ok=True)
    if shutil.disk_usage(root).free < body.size + 16*1024*1024:
        raise HTTPException(507, 'Espace disque insuffisant sur le serveur.')
    uid = uuid.uuid4().hex
    (root/(uid+'.json')).write_text(json.dumps({'size':body.size}))
    (root/(uid+'.part')).touch(mode=0o600)
    return {'upload_id':uid, 'chunk_bytes':CHUNK_BYTES}


@router.get('/v1/admin/app-update/uploads/{upload_id}', dependencies=[Depends(require_admin)])
def upload_status(upload_id: str):
    part, meta, _ = session_paths(upload_id)
    return {'offset':part.stat().st_size, 'size':meta['size']}


@router.put('/v1/admin/app-update/uploads/{upload_id}', dependencies=[Depends(require_admin)])
async def upload_chunk(upload_id: str, offset: int, request: Request):
    part, meta, metadata = session_paths(upload_id)
    chunk = bytearray()
    async for data in request.stream():
        if len(chunk)+len(data)>CHUNK_BYTES:
            raise HTTPException(413, 'Bloc trop grand.')
        chunk.extend(data)
    if not chunk: raise HTTPException(400, 'Bloc vide.')
    with part.open('r+b') as output:
        try: fcntl.flock(output, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise HTTPException(409, 'Bloc déjà en cours.')
        output.seek(0,2)
        if output.tell()!=offset or offset<0: raise HTTPException(409, 'Position de reprise incorrecte.')
        if offset+len(chunk)>meta['size']: raise HTTPException(413, 'Taille annoncée dépassée.')
        try:
            await run_in_threadpool(output.write, chunk)
            await run_in_threadpool(output.flush)
            await run_in_threadpool(os.fsync, output.fileno())
        except OSError as error:
            output.truncate(offset)
            raise HTTPException(507, 'Écriture impossible : vérifiez l’espace disque.') from error
        os.utime(metadata,None)
        return {'offset':output.tell()}


@router.post('/v1/admin/app-update/uploads/{upload_id}/finish', dependencies=[Depends(require_admin)])
def finish_upload(upload_id: str):
    part, meta, _ = session_paths(upload_id)
    with part.open('rb') as source:
        try: fcntl.flock(source, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise HTTPException(409, 'Téléversement en cours.')
        if part.stat().st_size != meta['size']: raise HTTPException(409, 'Téléversement incomplet.')
        validate_apk(part)
        return publish(part, hashlib.file_digest(source,'sha256').hexdigest(), meta['size'])
