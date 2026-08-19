from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator


class DeviceRequest(BaseModel):
    device_id: str = Field(min_length=12, max_length=200)

    @field_validator("device_id")
    @classmethod
    def clean_device_id(cls, value: str) -> str:
        return value.strip()


class AccessResponse(BaseModel):
    allowed: bool
    access_type: Literal["NONE", "TRIAL", "SUBSCRIPTION"]
    server_time: str
    started_at: str | None = None
    expires_at: str | None = None
    remaining_seconds: int = 0


class TrialStartResponse(AccessResponse):
    started_now: bool
    message: str


class PaymentStartRequest(DeviceRequest):
    plan_id: Literal["24h", "1w", "2w", "1m"]
    customer_name: str | None = Field(default=None, max_length=100)
    customer_email: str | None = Field(default=None, max_length=200)


class PaymentStartResponse(BaseModel):
    success: bool
    checkout_url: str
    payment_reference: str
    status: Literal["pending"]
    amount: int
    currency: str = "XOF"


class PaymentStatusRequest(DeviceRequest):
    payment_reference: str = Field(min_length=8, max_length=100)
    sync_provider: bool = True


class PaymentStatusResponse(BaseModel):
    payment_reference: str
    status: Literal["creating", "pending", "paid", "failed", "error"]
    activation_code: str | None = None
    message: str


class ActivationRequest(DeviceRequest):
    code: str = Field(min_length=8, max_length=80)


class ActivationResponse(BaseModel):
    success: bool
    message: str
    access: AccessResponse


class AdminCodeRequest(BaseModel):
    plan_id: Literal["24h", "1w", "2w", "1m"]
    count: int = Field(default=1, ge=1, le=100)


class AdminCodeResponse(BaseModel):
    plan_id: str
    codes: list[str]


class PlanResponse(BaseModel):
    id: str
    label: str
    amount: int
    currency: str
    duration_seconds: int
