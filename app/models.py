from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    Boolean, CheckConstraint, DateTime, ForeignKey, Index, Integer, JSON, Numeric,
    String, Text, UniqueConstraint, func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base

NUMERIC = Numeric(14, 3)


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255))
    role: Mapped[str] = mapped_column(String(32))
    full_name: Mapped[str] = mapped_column(String(128))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)


class Farm(Base):
    __tablename__ = "farms"
    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(64), unique=True)
    name: Mapped[str] = mapped_column(String(255))


class PastureBatch(Base):
    __tablename__ = "pasture_batches"
    id: Mapped[int] = mapped_column(primary_key=True)
    farm_id: Mapped[int] = mapped_column(ForeignKey("farms.id"))
    code: Mapped[str] = mapped_column(String(64), unique=True)
    collected_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    declared_amount: Mapped[Decimal] = mapped_column(NUMERIC)
    farm: Mapped[Farm] = relationship()


class Vehicle(Base):
    __tablename__ = "vehicles"
    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(64), unique=True)
    plate: Mapped[str] = mapped_column(String(32), unique=True)


class Compartment(Base):
    __tablename__ = "compartments"
    id: Mapped[int] = mapped_column(primary_key=True)
    vehicle_id: Mapped[int] = mapped_column(ForeignKey("vehicles.id"))
    code: Mapped[str] = mapped_column(String(32))
    capacity: Mapped[Decimal] = mapped_column(NUMERIC)
    current_node_id: Mapped[int | None] = mapped_column(ForeignKey("material_nodes.id"), nullable=True)
    version: Mapped[int] = mapped_column(Integer, default=1)
    vehicle: Mapped[Vehicle] = relationship()
    __table_args__ = (UniqueConstraint("vehicle_id", "code"),)


class ReceivingTank(Base):
    __tablename__ = "receiving_tanks"
    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(64), unique=True)
    name: Mapped[str] = mapped_column(String(255))
    capacity: Mapped[Decimal] = mapped_column(NUMERIC)
    current_node_id: Mapped[int | None] = mapped_column(ForeignKey("material_nodes.id"), nullable=True)
    version: Mapped[int] = mapped_column(Integer, default=1)


class Arrival(Base):
    __tablename__ = "arrivals"
    id: Mapped[int] = mapped_column(primary_key=True)
    event_id: Mapped[str] = mapped_column(String(80), unique=True)
    vehicle_id: Mapped[int] = mapped_column(ForeignKey("vehicles.id"))
    arrived_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    status: Mapped[str] = mapped_column(String(32), default="registered")
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    vehicle: Mapped[Vehicle] = relationship()
    items: Mapped[list["ArrivalItem"]] = relationship(back_populates="arrival")


class ArrivalItem(Base):
    __tablename__ = "arrival_items"
    id: Mapped[int] = mapped_column(primary_key=True)
    arrival_id: Mapped[int] = mapped_column(ForeignKey("arrivals.id"))
    compartment_id: Mapped[int] = mapped_column(ForeignKey("compartments.id"))
    pasture_batch_id: Mapped[int] = mapped_column(ForeignKey("pasture_batches.id"))
    declared_amount: Mapped[Decimal] = mapped_column(NUMERIC)
    initial_node_id: Mapped[int | None] = mapped_column(ForeignKey("material_nodes.id"), nullable=True)
    arrival: Mapped[Arrival] = relationship(back_populates="items")
    compartment: Mapped[Compartment] = relationship()
    seals: Mapped[list["Seal"]] = relationship(back_populates="arrival_item")
    pasture_batch: Mapped[PastureBatch] = relationship()


class Seal(Base):
    __tablename__ = "seals"
    id: Mapped[int] = mapped_column(primary_key=True)
    arrival_item_id: Mapped[int] = mapped_column(ForeignKey("arrival_items.id"))
    code: Mapped[str] = mapped_column(String(80), unique=True)
    state: Mapped[str] = mapped_column(String(32), default="sealed")
    registered_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    arrival_item: Mapped[ArrivalItem] = relationship(back_populates="seals")


class MaterialNode(Base):
    __tablename__ = "material_nodes"
    id: Mapped[int] = mapped_column(primary_key=True)
    node_type: Mapped[str] = mapped_column(String(32))
    pasture_batch_id: Mapped[int | None] = mapped_column(ForeignKey("pasture_batches.id"), nullable=True)
    compartment_id: Mapped[int | None] = mapped_column(ForeignKey("compartments.id"), nullable=True)
    tank_id: Mapped[int | None] = mapped_column(ForeignKey("receiving_tanks.id"), nullable=True)
    state_seq: Mapped[int] = mapped_column(Integer, default=1)
    remaining_amount: Mapped[Decimal] = mapped_column(NUMERIC)
    version: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    __table_args__ = (
        CheckConstraint("remaining_amount >= 0", name="ck_material_node_remaining_non_negative"),
        CheckConstraint("node_type in ('pasture_batch','compartment','tank')", name="ck_material_node_type"),
    )


class MaterialFlow(Base):
    __tablename__ = "material_flows"
    id: Mapped[int] = mapped_column(primary_key=True)
    from_node_id: Mapped[int] = mapped_column(ForeignKey("material_nodes.id"))
    to_node_id: Mapped[int] = mapped_column(ForeignKey("material_nodes.id"))
    amount: Mapped[Decimal] = mapped_column(NUMERIC)
    cause: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    __table_args__ = (
        CheckConstraint("amount > 0", name="ck_material_flow_amount_positive"),
        CheckConstraint("from_node_id <> to_node_id", name="ck_material_flow_no_self_loop"),
        Index("ix_material_flows_from", "from_node_id"),
        Index("ix_material_flows_to", "to_node_id"),
    )


class UnloadEvent(Base):
    __tablename__ = "unload_events"
    id: Mapped[int] = mapped_column(primary_key=True)
    event_id: Mapped[str] = mapped_column(String(80), unique=True)
    compartment_id: Mapped[int] = mapped_column(ForeignKey("compartments.id"))
    tank_id: Mapped[int] = mapped_column(ForeignKey("receiving_tanks.id"))
    source_node_id: Mapped[int | None] = mapped_column(ForeignKey("material_nodes.id"), nullable=True)
    destination_node_id: Mapped[int | None] = mapped_column(ForeignKey("material_nodes.id"), nullable=True)
    amount: Mapped[Decimal] = mapped_column(NUMERIC)
    observed_seal_code: Mapped[str] = mapped_column(String(80))
    seal_intact: Mapped[bool] = mapped_column(Boolean)
    temperature_c: Mapped[Decimal | None] = mapped_column(NUMERIC, nullable=True)
    weight_kg: Mapped[Decimal | None] = mapped_column(NUMERIC, nullable=True)
    status: Mapped[str] = mapped_column(String(32), default="accepted")
    payload_hash: Mapped[str] = mapped_column(String(64))
    recorded_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class TopUpEvent(Base):
    __tablename__ = "topup_events"
    id: Mapped[int] = mapped_column(primary_key=True)
    event_id: Mapped[str] = mapped_column(String(80), unique=True)
    compartment_id: Mapped[int] = mapped_column(ForeignKey("compartments.id"))
    pasture_batch_id: Mapped[int] = mapped_column(ForeignKey("pasture_batches.id"))
    from_node_id: Mapped[int | None] = mapped_column(ForeignKey("material_nodes.id"), nullable=True)
    to_node_id: Mapped[int | None] = mapped_column(ForeignKey("material_nodes.id"), nullable=True)
    amount: Mapped[Decimal] = mapped_column(NUMERIC)
    temperature_c: Mapped[Decimal | None] = mapped_column(NUMERIC, nullable=True)
    status: Mapped[str] = mapped_column(String(32), default="accepted")
    payload_hash: Mapped[str] = mapped_column(String(64))
    recorded_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class ReconciliationIssue(Base):
    __tablename__ = "reconciliation_issues"
    id: Mapped[int] = mapped_column(primary_key=True)
    issue_key: Mapped[str] = mapped_column(String(120), unique=True)
    issue_type: Mapped[str] = mapped_column(String(64))
    event_id: Mapped[str | None] = mapped_column(String(80), nullable=True)
    status: Mapped[str] = mapped_column(String(32), default="open")
    details: Mapped[dict] = mapped_column(JSON, default=dict)
    resolution: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    resolved_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)


class DeviceReading(Base):
    __tablename__ = "device_readings"
    id: Mapped[int] = mapped_column(primary_key=True)
    reading_id: Mapped[str] = mapped_column(String(80), unique=True)
    device_id: Mapped[str] = mapped_column(String(80), index=True)
    metric: Mapped[str] = mapped_column(String(32))
    value: Mapped[Decimal] = mapped_column(NUMERIC)
    unit: Mapped[str] = mapped_column(String(16))
    observed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    replay_cursor: Mapped[int | None] = mapped_column(Integer, nullable=True)
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    __table_args__ = (CheckConstraint("metric in ('temperature','weight')", name="ck_device_metric"),)


class Sample(Base):
    __tablename__ = "samples"
    id: Mapped[int] = mapped_column(primary_key=True)
    sample_code: Mapped[str] = mapped_column(String(80), unique=True)
    compartment_id: Mapped[int] = mapped_column(ForeignKey("compartments.id"))
    node_id: Mapped[int] = mapped_column(ForeignKey("material_nodes.id"))
    status: Mapped[str] = mapped_column(String(32), default="collected")
    collected_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    collected_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    handover_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    handover_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    lab_received_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    lab_received_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    current_result_id: Mapped[int | None] = mapped_column(ForeignKey("sample_results.id"), nullable=True)


class SampleResult(Base):
    __tablename__ = "sample_results"
    id: Mapped[int] = mapped_column(primary_key=True)
    sample_id: Mapped[int] = mapped_column(ForeignKey("samples.id"))
    revision: Mapped[int] = mapped_column(Integer, default=1)
    outcome: Mapped[str] = mapped_column(String(32))
    analyst_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    corrected: Mapped[bool] = mapped_column(Boolean, default=False)
    resulted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    sample: Mapped[Sample] = relationship(foreign_keys=[sample_id])
    __table_args__ = (
        UniqueConstraint("sample_id", "revision"),
        CheckConstraint("outcome in ('pass','fail','conditional')", name="ck_sample_outcome"),
    )


class ReceivingDecision(Base):
    __tablename__ = "receiving_decisions"
    id: Mapped[int] = mapped_column(primary_key=True)
    arrival_id: Mapped[int] = mapped_column(ForeignKey("arrivals.id"), unique=True)
    conclusion: Mapped[str] = mapped_column(String(32))
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    confirmed_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    confirmed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    __table_args__ = (CheckConstraint("conclusion in ('accepted','conditional','rejected')", name="ck_decision_conclusion"),)


class ReleaseRequest(Base):
    __tablename__ = "release_requests"
    id: Mapped[int] = mapped_column(primary_key=True)
    request_no: Mapped[str] = mapped_column(String(80), unique=True)
    tank_id: Mapped[int] = mapped_column(ForeignKey("receiving_tanks.id"))
    node_id: Mapped[int] = mapped_column(ForeignKey("material_nodes.id"))
    status: Mapped[str] = mapped_column(String(32), default="pending")
    lineage_version: Mapped[str] = mapped_column(String(64))
    reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    requested_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    decided_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    decision_notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    decided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    __table_args__ = (
        CheckConstraint("status in ('pending','approved','rejected','invalidated')", name="ck_release_status"),
    )
