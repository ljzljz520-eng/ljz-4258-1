from datetime import datetime
from decimal import Decimal
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class ORMModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"


class ArrivalItemIn(BaseModel):
    compartment_code: str
    pasture_batch_code: str
    declared_amount: Decimal = Field(gt=0)
    seal_codes: list[str] = Field(min_length=1)


class ArrivalCreate(BaseModel):
    event_id: str
    vehicle_code: str
    arrived_at: datetime | None = None
    items: list[ArrivalItemIn] = Field(min_length=1)


class UnloadCreate(BaseModel):
    event_id: str
    compartment_code: str
    vehicle_code: str
    tank_code: str
    amount: Decimal = Field(gt=0)
    observed_seal_code: str
    seal_intact: bool
    temperature_c: Decimal | None = None
    weight_kg: Decimal | None = None
    occurred_at: datetime | None = None
    terminal_id: str | None = None


class TopUpCreate(BaseModel):
    event_id: str
    compartment_code: str
    vehicle_code: str
    pasture_batch_code: str
    amount: Decimal = Field(gt=0)
    temperature_c: Decimal | None = None
    occurred_at: datetime | None = None
    terminal_id: str | None = None


class DeviceReadingIn(BaseModel):
    reading_id: str
    device_id: str
    metric: Literal["temperature", "weight"]
    value: Decimal
    unit: str
    observed_at: datetime
    replay_cursor: int | None = None


class SampleCreate(BaseModel):
    sample_code: str
    compartment_code: str
    vehicle_code: str
    collected_at: datetime | None = None


class HandoverUpdate(BaseModel):
    sample_code: str
    phase: Literal["handover", "lab_receive"]


class SampleResultIn(BaseModel):
    sample_code: str
    outcome: Literal["pass", "fail", "conditional"]
    notes: str | None = None
    corrected: bool = False
    resulted_at: datetime | None = None


class DecisionIn(BaseModel):
    arrival_event_id: str
    conclusion: Literal["accepted", "conditional", "rejected"]
    notes: str | None = None


class ReleaseCreate(BaseModel):
    request_no: str
    tank_code: str
    reason: str | None = None


class ReleaseDecisionIn(BaseModel):
    request_no: str
    approve: bool
    expected_lineage_version: str
    notes: str | None = None


class IssueResolve(BaseModel):
    issue_key: str
    resolution: str


class SyncBatch(BaseModel):
    events: list[dict[str, Any]]


class ResponseItem(BaseModel):
    event_id: str
    status: str
    detail: str | None = None
    data: dict[str, Any] | None = None
