import json
from pathlib import Path
from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.legal import router

def test_public_policies_available_in_four_languages_and_safe_paths():
    app=FastAPI();app.include_router(router)
    client=TestClient(app)
    for language in ('fr','en','es','ar'):
        document=json.loads((Path(__file__).parents[2]/'app/src/main/assets/legal'/f'{language}.json').read_text())
        for policy in ('privacy','cookies','terms'):
            response=client.get(f'/legal/{policy}?lang={language}')
            assert response.status_code==200
            assert 'barkatunnel@gmail.com' in response.text
            assert "default-src 'none'" in response.headers['content-security-policy']
            assert len(document[policy]) >= 3
            assert '<script' not in response.text
    assert client.get('/legal/unknown').status_code==422
    assert client.get('/legal/privacy?lang=../../config').status_code==422
