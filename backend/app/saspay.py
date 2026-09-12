from __future__ import annotations

from urllib.parse import quote

import httpx

from .config import settings


class SasPayError(RuntimeError):
    pass


def _headers() -> dict[str, str]:
    return {
        "Authorization": f"Bearer {settings.saspay_secret_key}",
        "Content-Type": "application/json",
    }


def _message(data: object) -> str:
    if isinstance(data, dict):
        return str(data.get("message") or data.get("error") or data.get("detail") or data)
    return str(data)


def _unwrap(data: object) -> dict:
    if not isinstance(data, dict):
        raise SasPayError("Réponse SasPay invalide")

    inner = data.get("data")
    if inner is not None:
        if not isinstance(inner, dict):
            raise SasPayError("Réponse SasPay invalide (data)")
        return inner

    return data


async def create_payment(
    *,
    amount: int,
    currency: str,
    description: str,
    external_reference: str,
    customer_name: str | None = None,
    customer_email: str | None = None,
) -> dict:
    if not settings.payment_ready:
        raise SasPayError(
            "Paiement non configuré : PUBLIC_BASE_URL HTTPS, clé SasPay et secret webhook requis."
        )

    payload = {
        "amount": f"{amount:.2f}",
        "currency": currency,
        "description": description,
        "country": settings.saspay_country,
        "customer_name": customer_name or "Client Barka Tunnel",
        "customer_email": customer_email or "paiement@rhaffservice.shop",
        "return_url": (
            f"{settings.public_base_url}/payment-return"
            f"?reference={quote(external_reference)}"
        ),
        "metadata": {
            "barka_reference": external_reference,
            "source": "barka_tunnel",
        },
    }

    url = f"{settings.saspay_api_base}/checkout-sessions/"
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            response = await client.post(url, headers=_headers(), json=payload)
    except httpx.HTTPError as exc:
        raise SasPayError(f"Connexion SasPay impossible: {exc}") from exc

    try:
        data = response.json()
    except ValueError as exc:
        raise SasPayError(
            f"Réponse SasPay non JSON (HTTP {response.status_code})"
        ) from exc

    if response.status_code >= 400:
        raise SasPayError(f"SasPay HTTP {response.status_code}: {_message(data)}")

    payload = _unwrap(data)
    checkout_url = payload.get("checkout_url")
    provider_id = payload.get("id")
    if not checkout_url or not provider_id:
        raise SasPayError("Réponse SasPay incomplète (id/checkout_url manquant)")
    return payload


async def get_payment(checkout_session_id: str) -> dict:
    if not settings.payment_ready:
        raise SasPayError("Paiement SasPay non configuré")

    url = f"{settings.saspay_api_base}/checkout-sessions/{quote(checkout_session_id)}/"
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            response = await client.get(url, headers=_headers())
    except httpx.HTTPError as exc:
        raise SasPayError(f"Connexion SasPay impossible: {exc}") from exc

    try:
        data = response.json()
    except ValueError as exc:
        raise SasPayError(
            f"Réponse SasPay non JSON (HTTP {response.status_code})"
        ) from exc

    if response.status_code >= 400:
        raise SasPayError(f"SasPay HTTP {response.status_code}: {_message(data)}")

    return _unwrap(data)
