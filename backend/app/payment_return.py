"""Return to the owning app; never disclose purchase credentials in a public page."""
import html
import json
import re
from urllib.parse import quote

from fastapi.responses import HTMLResponse
from . import saspay, services


async def payment_return_response(reference: str) -> HTMLResponse:
    reference = reference if re.fullmatch(r"[A-Za-z0-9_-]{8,100}", reference) else ""
    payment = services.get_payment_by_any_reference(local_reference=reference) if reference else None
    if payment:
        if payment["status"] == "pending" and payment["provider_payment_id"]:
            try:
                remote = await saspay.get_payment(str(payment["provider_payment_id"]))
            except saspay.SasPayError:
                remote = {}
            if str(remote.get("status", "")).upper() == "PAID":
                services.mark_payment_paid(reference)
        elif payment["status"] == "paid":
            services.mark_payment_paid(reference)
    destination = ("intent://payment-return?reference=" + quote(reference, safe="")
                   + "#Intent;scheme=barkatunnel;package=com.barkatunnel.app;end")
    link = html.escape(destination, quote=True)
    page = f"""<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Barka Tunnel — Votre achat</title><style>
body{{font-family:system-ui,sans-serif;background:#f7f9fc;color:#101828;margin:0;min-height:100vh;display:grid;place-items:center}}
main{{background:white;border:1px solid #dce2ea;border-radius:22px;padding:28px;max-width:440px;margin:20px;overflow-wrap:anywhere}}
h1{{color:#0961e8}}p{{line-height:1.6}}a{{display:block;background:#0961e8;color:white;text-decoration:none;text-align:center;font-weight:700;padding:18px;border-radius:14px}}small{{color:#667085}}
</style></head><body><main><h1>Retrouvez votre achat</h1>
<p>Ouvrez Barka Tunnel. Après confirmation du paiement, votre code d’abonnement ou vos identifiants revendeur apparaîtront dans l’application.</p>
<a href="{link}">RETOURNER DANS BARKA TUNNEL</a>
<p>Si le bouton ne fonctionne pas, ouvrez l’application et revenez dans Abonnement ou Devenir Revendeur.</p>
<small>Référence : {html.escape(reference)}</small></main>
<script>setTimeout(function(){{window.location.href={json.dumps(destination)};}},300);</script>
</body></html>"""
    return HTMLResponse(page, headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff", "Content-Security-Policy":
        "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'"})
