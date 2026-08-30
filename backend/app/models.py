from __future__ import annotations

from typing import Any, Literal

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


class AdminRedeemCodeRequest(BaseModel):
    duration_hours: float = Field(gt=0, le=8760)
    max_users: int = Field(ge=1, le=100000)
    count: int = Field(default=1, ge=1, le=100)


class AdminRedeemCodeResponse(BaseModel):
    duration_seconds: int
    max_users: int
    codes: list[str]


class AdminRedeemCodeListItem(BaseModel):
    code: str
    status: Literal["active", "revoked"]
    duration_seconds: int
    max_users: int
    usage_count: int
    created_at: str
    last_redeemed_at: str | None = None
    last_expires_at: str | None = None


class PlanResponse(BaseModel):
    id: str
    label: str
    amount: int
    currency: str
    duration_seconds: int


VpnNetworkId = Literal["moov_bf", "orange_bf", "telecel_bf"]
VpnProtocol = Literal["SLOWDNS", "VLESS", "UDP"]


class VpnProfileRequest(DeviceRequest):
    network_id: VpnNetworkId


class VpnProfileCatalogItem(BaseModel):
    network_id: VpnNetworkId
    display_name: str
    protocol: VpnProtocol
    enabled: bool
    maintenance: bool = False
    priority: int
    version: int
    updated_at: str


class VpnProfileResponse(VpnProfileCatalogItem):
    config: dict[str, Any]


class AdminVpnProfileUpsert(BaseModel):
    network_id: VpnNetworkId
    display_name: str = Field(min_length=2, max_length=80)
    protocol: VpnProtocol
    enabled: bool = False
    maintenance: bool = False
    priority: int = Field(default=100, ge=1, le=1000)
    config: dict[str, Any] = Field(default_factory=dict)


class AdminVpnProfileResponse(VpnProfileResponse):
    pass


class AdminCodeListItem(BaseModel):
    code: str
    plan_id: str
    status: Literal["issued", "redeemed", "revoked"]
    created_at: str
    redeemed_at: str | None = None
    expires_at: str | None = None
    redeemed_device_id: str | None = None
    source_type: str


class AdminCodeRevokeRequest(BaseModel):
    code: str = Field(min_length=8, max_length=80)


class AdminCodeRevokeResponse(BaseModel):
    success: bool
    message: str


class AdminStatsResetRequest(BaseModel):
    confirmation: Literal["REINITIALISER"]


class AppUpdateAdminUpsert(BaseModel):
    enabled: bool = False
    latest_version_code: int = Field(default=1, ge=1, le=2_000_000_000)
    latest_version_name: str = Field(default="1.0.0", min_length=1, max_length=50)
    apk_url: str = Field(default="", max_length=1000)
    message: str = Field(default="Une nouvelle version de Barka Tunnel est disponible.", max_length=500)
    mandatory: bool = False


class AppUpdateAdminResponse(AppUpdateAdminUpsert):
    updated_at: str


class AppUpdateResponse(BaseModel):
    enabled: bool
    update_available: bool
    force_update: bool
    latest_version_code: int
    latest_version_name: str
    apk_url: str
    message: str
    updated_at: str
