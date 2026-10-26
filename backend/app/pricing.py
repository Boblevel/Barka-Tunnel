"""Admin-managed XOF prices and immutable payment quotes."""
from __future__ import annotations
import json
import time
import uuid
from typing import Annotated
from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from .db import connect, transaction
from .plans import PLANS
from .security import require_admin

DEFAULT_PRICES = {**{key: plan['amount'] for key, plan in PLANS.items()},
                  'reseller_1m': 5000, 'reseller_2m': 10000}
STATE_KEY = 'application_prices_v1'
router = APIRouter()
Amount = Annotated[int, Field(strict=True, ge=1, le=10_000_000)]

class Promotion(BaseModel):
    model_config = ConfigDict(extra='forbid')
    amount: Amount
    starts_at: int = Field(strict=True, ge=0)
    ends_at: int = Field(strict=True, gt=0)

    @model_validator(mode='after')
    def valid_period(self):
        if self.ends_at <= self.starts_at:
            raise ValueError('La fin doit être postérieure au début.')
        return self


class PricingUpdate(BaseModel):
    model_config = ConfigDict(extra='forbid')
    revision: int = Field(strict=True, ge=0)
    prices: dict[str, Amount]
    promotions: dict[str, Promotion] | None = None

    @model_validator(mode='after')
    def valid_promotions(self):
        for key, promotion in (self.promotions or {}).items():
            if key not in self.prices or promotion.amount >= self.prices[key]:
                raise ValueError('Une promotion doit concerner une offre connue et être inférieure au tarif normal.')
        return self

    @field_validator('prices')
    @classmethod
    def complete_catalog(cls, value):
        if set(value) != set(DEFAULT_PRICES):
            raise ValueError('Tous les tarifs du catalogue sont requis, sans autre offre.')
        return value


def get_catalog(cx=None):
    if cx is None:
        cx = connect()
        try:
            return get_catalog(cx)
        finally:
            cx.close()
    row = cx.execute('SELECT value FROM admin_state WHERE key=?', (STATE_KEY,)).fetchone()
    raw = json.loads(row['value']) if row else {'revision': 0, 'prices': dict(DEFAULT_PRICES)}
    # Upgrade the six-price catalog while preserving custom amounts and revision.
    raw['prices'] = {**DEFAULT_PRICES, **raw['prices']}
    saved = PricingUpdate.model_validate(raw)
    now = int(time.time())
    promotions = {key: value.model_dump() for key, value in (saved.promotions or {}).items()}
    effective = dict(saved.prices)
    for key, promo in promotions.items():
        if promo['starts_at'] <= now < promo['ends_at']:
            effective[key] = promo['amount']
    return {'revision': saved.revision, 'currency': 'XOF', 'prices': effective,
            'base_prices': saved.prices, 'promotions': promotions, 'server_time': now}


def public_plans():
    amounts = get_catalog()['prices']
    return [{**plan, 'amount': amounts[key]} for key, plan in PLANS.items()]


def check_expected_amount(amount, expected):
    if expected is not None and amount != expected:
        raise HTTPException(409, 'PRICING_CHANGED')


def create_subscription_quote(device_id, plan_id, expected_amount=None):
    from .services import ensure_device
    ensure_device(device_id)
    if plan_id not in PLANS:
        raise HTTPException(400, 'Offre inconnue')
    now = int(time.time())
    reference = f'BT-{now}-{uuid.uuid4().hex[:10].upper()}'
    with transaction() as cx:
        amount = get_catalog(cx)['prices'][plan_id]
        check_expected_amount(amount, expected_amount)
        cx.execute("""INSERT INTO payments(reference,device_id,plan_id,amount,currency,status,created_at,updated_at)
                      VALUES(?,?,?,?,'XOF','creating',?,?)""", (reference,device_id,plan_id,amount,now,now))
    return reference, amount


@router.get('/v1/pricing')
def public_pricing(response: Response):
    response.headers['Cache-Control'] = 'no-store, max-age=0'
    return get_catalog()


@router.get('/v1/admin/pricing', dependencies=[Depends(require_admin)])
def admin_pricing(response: Response):
    return public_pricing(response)


@router.put('/v1/admin/pricing', dependencies=[Depends(require_admin)])
def save_pricing(body: PricingUpdate, response: Response):
    with transaction() as cx:
        current = get_catalog(cx)
        if body.revision != current['revision']:
            raise HTTPException(409, 'Les tarifs ont été modifiés ailleurs. Rechargez avant d’enregistrer.')
        promotions = body.promotions if body.promotions is not None else current['promotions']
        try:
            validated = PricingUpdate.model_validate({'revision': current['revision'] + 1,
                                                       'prices': body.prices, 'promotions': promotions})
        except ValueError as exc:
            raise HTTPException(422, 'Adaptez ou retirez la promotion avant de réduire le tarif normal.') from exc
        saved = validated.model_dump()
        cx.execute('''INSERT INTO admin_state(key,value,updated_at) VALUES(?,?,?)
                      ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at''',
                   (STATE_KEY, json.dumps(saved), int(time.time())))
    response.headers['Cache-Control'] = 'no-store'
    return get_catalog()
