from __future__ import annotations

from urllib.parse import quote

import httpx

from .config import settings


class LomoPayError(RuntimeError):
    pass


def _headers() -> dict[str, str]:
    return {
        "X-Public-Key": settings.lomopay_public_key,
        "X-Secret-Key": settings.lomopay_secret_key,
        "Content-Type": "application/json",
    }


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
        raise LomoPayError(
            "Paiement non configuré : PUBLIC_BASE_URL HTTPS et clés LomoPay requises."
        )

    payload = {
        "amount": amount,
        "currency": currency,
        "description": description,
        "external_reference": external_reference,
        "return_url": (
            f"{settings.public_base_url}/payment-return"
            f"?reference={quote(external_reference)}"
        ),
        "webhook_url": f"{settings.public_base_url}/v1/webhooks/lomopay",
    }
    if customer_name:
        payload["customer_name"] = customer_name
    if customer_email:
        payload["customer_email"] = customer_email

    url = f"{settings.lomopay_api_base}/payments.php"
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            response = await client.post(url, headers=_headers(), json=payload)
    except httpx.HTTPError as exc:
        raise LomoPayError(f"Connexion LomoPay impossible: {exc}") from exc

    try:
        data = response.json()
    except ValueError as exc:
        raise LomoPayError(
            f"Réponse LomoPay non JSON (HTTP {response.status_code})"
        ) from exc

    if response.status_code >= 400 or not data.get("success"):
        message = data.get("message") or data.get("error") or str(data)
        raise LomoPayError(f"LomoPay HTTP {response.status_code}: {message}")

    pay = data.get("data") or {}
    checkout_url = pay.get("checkout_url")
    provider_id = pay.get("id")
    if not checkout_url or not provider_id:
        raise LomoPayError("Réponse LomoPay incomplète (id/checkout_url manquant)")

    return pay


async def get_payment(payment_id_or_reference: str) -> dict:
    if not settings.payment_ready:
        raise LomoPayError("Paiement LomoPay non configuré")

    url = f"{settings.lomopay_api_base}/payments.php"
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            response = await client.get(
                url,
                headers=_headers(),
                params={"id": payment_id_or_reference},
            )
    except httpx.HTTPError as exc:
        raise LomoPayError(f"Connexion LomoPay impossible: {exc}") from exc

    try:
        data = response.json()
    except ValueError as exc:
        raise LomoPayError(
            f"Réponse LomoPay non JSON (HTTP {response.status_code})"
        ) from exc

    if response.status_code >= 400 or not data.get("success"):
        message = data.get("message") or data.get("error") or str(data)
        raise LomoPayError(f"LomoPay HTTP {response.status_code}: {message}")

    return data.get("data") or {}
