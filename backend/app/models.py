from __future__ import annotations

import enum
import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import (BigInteger, Boolean, CheckConstraint, DateTime, Enum, ForeignKey,
                        Index, Integer, Numeric, String, Text, UniqueConstraint, text)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


class UserRole(str, enum.Enum):
    OPERATOR = "OPERATOR"
    LAB = "LAB"
    SUPERVISOR = "SUPERVISOR"
    ADMIN = "ADMIN"


class EventType(str, enum.Enum):
    LOAD = "LOAD"
    REFILL = "REFILL"
    UNLOAD = "UNLOAD"
    SAMPLE = "SAMPLE"


class EventStatus(str, enum.Enum):
    ACCEPTED = "ACCEPTED"
    QUARANTINED = "QUARANTINED"
    REJECTED = "REJECTED"


class MaterialNodeType(str, enum.Enum):
    FARM_BATCH = "FARM_BATCH"
    COMPARTMENT = "COMPARTMENT"
    TANK = "TANK"
    SAMPLE = "SAMPLE"


class SealStatus(str, enum.Enum):
    EXPECTED = "EXPECTED"
    APPLIED = "APPLIED"
    BROKEN = "BROKEN"
    CONFLICT = "CONFLICT"


class DeviceType(str, enum.Enum):
    TEMPERATURE = "TEMPERATURE"
    WEIGHT = "WEIGHT"


class SampleStatus(str, enum.Enum):
    REGISTERED = "REGISTERED"
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    CORRECTED = "CORRECTED"


class LabResultStatus(str, enum.Enum):
    PENDING = "PENDING"
    ACCEPT = "ACCEPT"
    REJECT = "REJECT"


class ReleaseStatus(str, enum.Enum):
    PENDING = "PENDING"
    SIGNED = "SIGNED"
    INVALIDATED = "INVALIDATED"


class User(Base):
    __tablename__ = "users"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    username: Mapped[str] = mapped_column(String, unique=True)
    full_name: Mapped[str] = mapped_column(String)
    password_hash: Mapped[str] = mapped_column(Text)
    role: Mapped[UserRole] = mapped_column(Enum(UserRole, name="user_role"))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"))


class Vehicle(Base):
    __tablename__ = "vehicles"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    plate: Mapped[str] = mapped_column(String, unique=True)
    carrier: Mapped[str] = mapped_column(String)
    compartments: Mapped[list["Compartment"]] = relationship(back_populates="vehicle")


class FarmBatch(Base):
    __tablename__ = "farm_batches"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    batch_code: Mapped[str] = mapped_column(String, unique=True)
    farm_name: Mapped[str] = mapped_column(String)
    declared_qty: Mapped[Decimal] = mapped_column(Numeric(12, 3))


class EventLog(Base):
    __tablename__ = "event_log"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    event_id: Mapped[str] = mapped_column(Text, unique=True)
    type: Mapped[EventType] = mapped_column(Enum(EventType, name="event_type"))
    terminal_id: Mapped[str] = mapped_column(Text)
    actor_user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id"))
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, default=dict)
    status: Mapped[EventStatus] = mapped_column(Enum(EventStatus, name="event_status"))
    conflict_reason: Mapped[str | None] = mapped_column(Text)
    client_created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"))


class MaterialNode(Base):
    __tablename__ = "material_nodes"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    type: Mapped[MaterialNodeType] = mapped_column(Enum(MaterialNodeType, name="material_node_type"))
    farm_batch_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("farm_batches.id"))
    quantity: Mapped[Decimal] = mapped_column(Numeric(12, 3))
    created_event_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("event_log.id"))


class Compartment(Base):
    __tablename__ = "compartments"
    __table_args__ = (UniqueConstraint("vehicle_id", "label"),)
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    vehicle_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("vehicles.id", ondelete="CASCADE"))
    label: Mapped[str] = mapped_column(String)
    capacity: Mapped[Decimal] = mapped_column(Numeric(12, 3))
    current_node_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("material_nodes.id"))
    vehicle: Mapped[Vehicle] = relationship(back_populates="compartments")
    current_node: Mapped[MaterialNode | None] = relationship()


class ReceivingTank(Base):
    __tablename__ = "receiving_tanks"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    tank_code: Mapped[str] = mapped_column(String, unique=True)
    name: Mapped[str] = mapped_column(String)
    capacity: Mapped[Decimal] = mapped_column(Numeric(12, 3))
    current_node_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("material_nodes.id"))
    lineage_version: Mapped[int] = mapped_column(Integer, default=1)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"))
    current_node: Mapped[MaterialNode | None] = relationship()


class MaterialEdge(Base):
    __tablename__ = "material_edges"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    event_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("event_log.id"))
    source_node_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("material_nodes.id"))
    destination_node_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("material_nodes.id"))
    edge_kind: Mapped[str] = mapped_column(String, default="TRANSFER")
    quantity: Mapped[Decimal] = mapped_column(Numeric(12, 3))


class Seal(Base):
    __tablename__ = "seals"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    compartment_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("compartments.id", ondelete="CASCADE"))
    seal_number: Mapped[str] = mapped_column(String, unique=True)
    status: Mapped[SealStatus] = mapped_column(Enum(SealStatus, name="seal_status"))
    applied_event_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("event_log.id"))
    broken_event_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("event_log.id"))


class DeviceReading(Base):
    __tablename__ = "device_readings"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    reading_id: Mapped[str] = mapped_column(Text, unique=True)
    terminal_id: Mapped[str] = mapped_column(Text)
    device_type: Mapped[DeviceType] = mapped_column(Enum(DeviceType, name="device_type"))
    device_id: Mapped[str] = mapped_column(Text)
    vehicle_plate: Mapped[str | None] = mapped_column(Text)
    value: Mapped[Decimal] = mapped_column(Numeric(12, 3))
    unit: Mapped[str] = mapped_column(Text)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    raw_payload: Mapped[dict[str, Any]] = mapped_column(JSONB, default=dict)
    replay_position: Mapped[int | None] = mapped_column(BigInteger)
    ingested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"))


class Sample(Base):
    __tablename__ = "samples"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    sample_code: Mapped[str] = mapped_column(Text, unique=True)
    source_node_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("material_nodes.id"))
    sample_node_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("material_nodes.id"))
    register_event_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("event_log.id"))
    farm_batch_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("farm_batches.id"))
    quantity: Mapped[Decimal] = mapped_column(Numeric(12, 3), default=Decimal("0"))
    status: Mapped[SampleStatus] = mapped_column(Enum(SampleStatus, name="sample_status"))
    current_version: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"))
    source_node: Mapped[MaterialNode] = relationship(foreign_keys=[source_node_id])


class LabResult(Base):
    __tablename__ = "lab_results"
    __table_args__ = (UniqueConstraint("sample_id", "version"),)
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    sample_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("samples.id", ondelete="CASCADE"))
    version: Mapped[int] = mapped_column(Integer)
    result: Mapped[LabResultStatus] = mapped_column(Enum(LabResultStatus, name="lab_result_status"))
    analyst_user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id"))
    result_event_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("event_log.id"))
    components: Mapped[dict[str, Any]] = mapped_column(JSONB, default=dict)
    note: Mapped[str | None] = mapped_column(Text)


class ReleaseApplication(Base):
    __tablename__ = "release_applications"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    application_no: Mapped[str] = mapped_column(Text, unique=True)
    tank_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("receiving_tanks.id"))
    expected_node_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("material_nodes.id"))
    expected_lineage_version: Mapped[int] = mapped_column(Integer)
    status: Mapped[ReleaseStatus] = mapped_column(Enum(ReleaseStatus, name="release_status"))
    requested_by: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id"))
    signed_by: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("users.id"))
    invalid_reason: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"))
    signed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    tank: Mapped[ReceivingTank] = relationship()
