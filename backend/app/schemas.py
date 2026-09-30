from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from uuid import UUID
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from .models import EventStatus, EventType, LabResultStatus, ReleaseStatus, SampleStatus, UserRole


class ORMModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    role: UserRole
    username: str


class UserOut(ORMModel):
    id: UUID
    username: str
    full_name: str
    role: UserRole


class FarmBatchOut(ORMModel):
    id: UUID
    batch_code: str
    farm_name: str
    declared_qty: Decimal


class CompartmentOut(ORMModel):
    id: UUID
    label: str
    capacity: Decimal
    current_node_id: UUID | None = None


class VehicleOut(ORMModel):
    id: UUID
    plate: str
    carrier: str
    compartments: list[CompartmentOut] = []


class TankOut(ORMModel):
    id: UUID
    tank_code: str
    name: str
    capacity: Decimal
    current_node_id: UUID | None = None
    lineage_version: int


class MaterialNodeOut(ORMModel):
    id: UUID
    type: Any
    farm_batch_id: UUID | None = None
    quantity: Decimal


class MaterialEdgeOut(ORMModel):
    id: UUID
    event_id: UUID | None = None
    source_node_id: UUID | None = None
    destination_node_id: UUID
    quantity: Decimal


class SealOut(ORMModel):
    id: UUID
    seal_number: str
    status: Any
    compartment_id: UUID


class DeviceReadingIn(BaseModel):
    reading_id: str
    terminal_id: str
    device_type: Literal["TEMPERATURE", "WEIGHT"]
    device_id: str
    vehicle_plate: str | None = None
    value: Decimal
    unit: str
    recorded_at: datetime
    raw_payload: dict[str, Any] = Field(default_factory=dict)
    replay_position: int | None = None


class DeviceReadingOut(ORMModel):
    id: UUID
    reading_id: str
    terminal_id: str
    device_type: Any
    device_id: str
    vehicle_plate: str | None
    value: Decimal
    unit: str
    recorded_at: datetime
    replay_position: int | None


class SyncEvent(BaseModel):
    event_id: str
    terminal_id: str
    type: EventType
    client_created_at: datetime
    payload: dict[str, Any]


class SyncBatch(BaseModel):
    events: list[SyncEvent]


class SyncEventResult(BaseModel):
    event_id: str
    duplicate: bool
    status: EventStatus
    conflict_reason: str | None = None


class SyncResponse(BaseModel):
    accepted: int
    quarantined: int
    duplicates: int
    results: list[SyncEventResult]
    partial_failure: bool = False


class EventOut(ORMModel):
    id: UUID
    event_id: str
    type: EventType
    terminal_id: str
    status: EventStatus
    conflict_reason: str | None = None
    payload: dict[str, Any]
    client_created_at: datetime
    processed_at: datetime


class RefillLine(BaseModel):
    farm_batch_id: UUID
    quantity: Decimal = Field(gt=0)


class UnloadLine(BaseModel):
    compartment_id: UUID
    source_node_id: UUID
    quantity: Decimal = Field(gt=0)
    expected_seal_number: str | None = None
    weight_reading_id: UUID | None = None


class LabResultIn(BaseModel):
    event_id: str | None = None
    result: LabResultStatus
    components: dict[str, Any] = Field(default_factory=dict)
    note: str | None = None


class LabResultOut(ORMModel):
    id: UUID
    sample_id: UUID
    version: int
    result: LabResultStatus
    note: str | None = None
    components: dict[str, Any]


class SampleOut(ORMModel):
    id: UUID
    sample_code: str
    source_node_id: UUID
    sample_node_id: UUID | None = None
    farm_batch_id: UUID | None = None
    quantity: Decimal
    status: SampleStatus
    current_version: int


class ReleaseIn(BaseModel):
    application_no: str
    tank_code: str
    expected_node_id: UUID | None = None
    expected_lineage_version: int


class ReleaseOut(ORMModel):
    id: UUID
    application_no: str
    tank_id: UUID
    expected_node_id: UUID | None = None
    expected_lineage_version: int
    status: ReleaseStatus
    invalid_reason: str | None = None
    signed_at: datetime | None = None

class DeviceReadingBatch(BaseModel):
    readings: list[DeviceReadingIn]
