from __future__ import annotations

import json
import uuid
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import HTMLResponse

from .config import settings
from .db import connect, init_db
from .lomopay import LomoPayError, create_payment, get_payment
from .models import (
    AccessResponse,
    ActivationRequest,
    ActivationResponse,
    AdminCodeRequest,
    AdminCodeResponse,
    DeviceRequest,
    PaymentStartRequest,
    PaymentStartResponse,
    PaymentStatusRequest,
    PaymentStatusResponse,
    PlanResponse,
    TrialStartResponse,
)
from .plans import PLANS, get_plan
from .security import require_admin, verify_lomopay_signature
from .services import (
    access_state,
    activation_code_for_payment,
    create_payment_record,
    get_payment_by_any_reference,
    get_payment_for_device,
    issue_activation_code,
    mark_payment_error,
    mark_payment_failed,
    mark_payment_paid,
    recent_pending_payment,
    redeem_activation_code,
    register_webhook_event,
    start_trial,
    update_payment_created,
)


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.validate_runtime()
    init_db()
    yield


app = FastAPI(
    title="Barka Tunnel Backend",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health")
def health():
    return {
        "ok": True,
        "service": "barka-tunnel-backend",
        "version": "1.0.0",
        "payment_configured": settings.payment_ready,
    }


@app.get("/v1/plans", response_model=list[PlanResponse])
def plans():
    return list(PLANS.values())


@app.post("/v1/access/check", response_model=AccessResponse)
def check_access(body: DeviceRequest):
    return access_state(body.device_id)


@app.post("/v1/trial/start", response_model=TrialStartResponse)
def trial_start(body: DeviceRequest):
    access, started_now, message = start_trial(body.device_id)
    return TrialStartResponse(
        **access,
        started_now=started_now,
        message=message,
    )


@app.post("/v1/payments/start", response_model=PaymentStartResponse)
async def payment_start(body: PaymentStartRequest):
    plan = get_plan(body.plan_id)
    if not plan:
        raise HTTPException(status_code=400, detail="Offre inconnue")

    existing = recent_pending_payment(body.device_id, body.plan_id)
    if existing:
        return PaymentStartResponse(
            success=True,
            checkout_url=existing["checkout_url"],
            payment_reference=existing["reference"],
            status="pending",
            amount=existing["amount"],
            currency=existing["currency"],
        )

    reference = create_payment_record(body.device_id, body.plan_id)

    try:
        provider = await create_payment(
            amount=int(plan["amount"]),
            currency=plan["currency"],
            description=f"Barka Tunnel — {plan['label']}",
            external_reference=reference,
            customer_name=body.customer_name,
            customer_email=body.customer_email,
        )
    except LomoPayError as exc:
        mark_payment_error(reference, str(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=str(exc),
        ) from exc

    update_payment_created(
        reference,
        str(provider["id"]),
        str(provider["checkout_url"]),
    )

    return PaymentStartResponse(
        success=True,
        checkout_url=str(provider["checkout_url"]),
        payment_reference=reference,
        status="pending",
        amount=int(plan["amount"]),
        currency=plan["currency"],
    )


@app.post("/v1/payments/status", response_model=PaymentStatusResponse)
async def payment_status(body: PaymentStatusRequest):
    payment = get_payment_for_device(body.payment_reference, body.device_id)
    if not payment:
        raise HTTPException(status_code=404, detail="Paiement introuvable")

    current_status = payment["status"]

    if current_status == "pending" and body.sync_provider and settings.payment_ready:
        provider_lookup = payment["provider_payment_id"] or payment["reference"]
        try:
            remote = await get_payment(str(provider_lookup))
            remote_status = str(remote.get("status", "")).lower()
            if remote_status == "completed":
                mark_payment_paid(payment["reference"])
                current_status = "paid"
            elif remote_status == "failed":
                mark_payment_failed(payment["reference"])
                current_status = "failed"
        except LomoPayError:
            # Le webhook reste la source principale. Une erreur de synchronisation
            # ne doit pas transformer un paiement en échec.
            pass

    code = (
        activation_code_for_payment(body.payment_reference)
        if current_status == "paid"
        else None
    )

    messages = {
        "creating": "Création du paiement en cours.",
        "pending": "Paiement en attente de confirmation.",
        "paid": "Paiement confirmé. Votre code est prêt.",
        "failed": "Paiement refusé, annulé ou expiré.",
        "error": "Le paiement n'a pas pu être créé.",
    }

    return PaymentStatusResponse(
        payment_reference=body.payment_reference,
        status=current_status,
        activation_code=code,
        message=messages.get(current_status, "Statut inconnu."),
    )


@app.post("/v1/activation/redeem", response_model=ActivationResponse)
def activation_redeem(body: ActivationRequest):
    success, message, access = redeem_activation_code(body.device_id, body.code)
    return ActivationResponse(success=success, message=message, access=access)


@app.post("/v1/webhooks/lomopay")
async def lomopay_webhook(
    request: Request,
    x_lomopay_signature: str | None = Header(default=None),
    x_lomopay_event_id: str | None = Header(default=None),
):
    raw = await request.body()

    if not verify_lomopay_signature(raw, x_lomopay_signature):
        raise HTTPException(status_code=401, detail="Signature webhook invalide")

    try:
        payload = json.loads(raw.decode("utf-8"))
    except Exception as exc:
        raise HTTPException(status_code=400, detail="JSON webhook invalide") from exc

    event_id = (
        x_lomopay_event_id
        or str(payload.get("id") or "")
        or f"evt-local-{uuid.uuid4().hex}"
    )
    if not register_webhook_event(event_id):
        return {"ok": True, "duplicate": True}

    event_type = str(payload.get("type", "")).lower()
    pay = payload.get("data") or {}
    remote_status = str(pay.get("status", "")).lower()
    provider_id = str(pay.get("transaction_id") or pay.get("id") or "")
    external_reference = str(pay.get("external_reference") or "")

    payment = get_payment_by_any_reference(
        local_reference=external_reference or None,
        provider_payment_id=provider_id or None,
    )
    if not payment:
        # On accuse réception afin d'éviter des retries infinis du prestataire.
        return {"ok": True, "ignored": "unknown_payment"}

    succeeded = (
        remote_status == "completed"
        or event_type in {
            "payment.succeeded",
            "payment.completed",
            "payment.success",
        }
    )
    failed = remote_status == "failed" or event_type == "payment.failed"

    if succeeded:
        mark_payment_paid(payment["reference"])
    elif failed:
        mark_payment_failed(payment["reference"])

    return {"ok": True}


@app.post(
    "/v1/admin/codes",
    response_model=AdminCodeResponse,
    dependencies=[Depends(require_admin)],
)
def admin_codes(body: AdminCodeRequest):
    codes: list[str] = []
    for _ in range(body.count):
        source_ref = f"MANUAL:{uuid.uuid4().hex}"
        codes.append(issue_activation_code(source_ref, body.plan_id))
    return AdminCodeResponse(plan_id=body.plan_id, codes=codes)


@app.get("/v1/admin/stats", dependencies=[Depends(require_admin)])
def admin_stats():
    cx = connect()
    try:
        devices = cx.execute("SELECT COUNT(*) AS n FROM devices").fetchone()["n"]
        paid = cx.execute(
            "SELECT COUNT(*) AS n FROM payments WHERE status='paid'"
        ).fetchone()["n"]
        pending = cx.execute(
            "SELECT COUNT(*) AS n FROM payments WHERE status='pending'"
        ).fetchone()["n"]
        redeemed = cx.execute(
            "SELECT COUNT(*) AS n FROM activation_codes WHERE status='redeemed'"
        ).fetchone()["n"]
    finally:
        cx.close()

    return {
        "devices": devices,
        "payments_paid": paid,
        "payments_pending": pending,
        "codes_redeemed": redeemed,
    }


@app.get("/payment-return", response_class=HTMLResponse)
def payment_return(reference: str = ""):
    safe_ref = reference.replace("<", "").replace(">", "")
    return f"""
    <!doctype html>
    <html lang="fr">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Barka Tunnel</title>
        <style>
          body {{ font-family: sans-serif; background:#f7f9fc; color:#101828;
                 display:flex; min-height:100vh; align-items:center;
                 justify-content:center; margin:0; }}
          .card {{ background:white; padding:28px; border-radius:18px;
                   max-width:420px; box-shadow:0 8px 30px #00000012; }}
          h1 {{ color:#136fe8; }}
        </style>
      </head>
      <body>
        <div class="card">
          <h1>Barka Tunnel</h1>
          <p>Le paiement a été transmis. Retournez dans l'application
             pour vérifier sa confirmation et récupérer votre code.</p>
          <small>Référence : {safe_ref}</small>
        </div>
      </body>
    </html>
    """
