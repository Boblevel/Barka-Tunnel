from __future__ import annotations

PLANS = {
    "24h": {
        "id": "24h",
        "label": "24 HEURES",
        "amount": 300,
        "currency": "XOF",
        "duration_seconds": 24 * 60 * 60,
    },
    "1w": {
        "id": "1w",
        "label": "1 SEMAINE",
        "amount": 800,
        "currency": "XOF",
        "duration_seconds": 7 * 24 * 60 * 60,
    },
    "2w": {
        "id": "2w",
        "label": "2 SEMAINES",
        "amount": 1000,
        "currency": "XOF",
        "duration_seconds": 14 * 24 * 60 * 60,
    },
    "1m": {
        "id": "1m",
        "label": "1 MOIS",
        "amount": 2000,
        "currency": "XOF",
        "duration_seconds": 30 * 24 * 60 * 60,
    },
}


def get_plan(plan_id: str) -> dict | None:
    return PLANS.get(plan_id)
