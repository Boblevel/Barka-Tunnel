"""Public, static legal notices matching the offline Android pages."""
from pathlib import Path
from typing import Literal
from fastapi import APIRouter
from fastapi.responses import FileResponse

router = APIRouter()

@router.get('/legal/{policy}', response_class=FileResponse)
def legal_page(policy: Literal['privacy', 'cookies', 'terms'], lang: Literal['fr', 'en', 'es', 'ar'] = 'fr'):
    return FileResponse(Path(__file__).parent / 'legal_pages' / f'{policy}-{lang}.html',
                        media_type='text/html; charset=utf-8',
                        headers={'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
                                 'X-Content-Type-Options': 'nosniff'})
