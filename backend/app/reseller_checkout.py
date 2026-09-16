"""Cancellation of reseller checkout only; subscription payments are unchanged."""
from urllib.parse import quote
import httpx
from . import saspay


async def cancel_checkout(session_id: str) -> dict:
    """SasPay refuses to cancel a paid session; resolve races by reading it again."""
    if not saspay.settings.payment_ready:
        raise saspay.SasPayError('Paiement SasPay non configuré')
    url = f'{saspay.settings.saspay_api_base}/checkout-sessions/{quote(session_id, safe="")}/cancel/'
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            response = await client.post(url, headers=saspay._headers(), json={})
    except httpx.HTTPError as exc:
        raise saspay.SasPayError('Annulation non confirmée') from exc
    if response.status_code == 409:
        return await saspay.get_payment(session_id)
    if response.status_code >= 400:
        raise saspay.SasPayError('Annulation non confirmée')
    try:
        return saspay._unwrap(response.json())
    except ValueError as exc:
        raise saspay.SasPayError('Réponse d’annulation invalide') from exc
