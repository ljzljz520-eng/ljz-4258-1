from __future__ import annotations

import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .config import get_settings
from .models import (Compartment, DeviceReading, DeviceType, EventLog, EventStatus, EventType,
                     FarmBatch, LabResult, LabResultStatus, MaterialEdge, MaterialNode,
                     MaterialNodeType, ReceivingTank, ReleaseApplication, ReleaseStatus,
                     Sample, SampleStatus, Seal, SealStatus, User)
from .tracing import downstream_tank_ids

settings = get_settings()


class EventConflict(Exception):
    """Business conflict that should be stored as a quarantined event."""


def ingest_event(db: Session, event_data, actor: User) -> EventLog:
    existing = db.scalar(select(EventLog).where(EventLog.event_id == event_data.event_id))
    if existing:
        return existing

    event = EventLog(
        event_id=event_data.event_id,
        type=event_data.type,
        terminal_id=event_data.terminal_id,
        actor_user_id=actor.id,
        payload=event_data.payload,
        client_created_at=event_data.client_created_at,
        status=EventStatus.ACCEPTED,
    )
    db.add(event)
    db.flush()

    # Keep the append-only event even when its material effect is rejected.
    # The savepoint discards partial seal/node/pointer mutations so quarantine
    # can never become an accidental last-write overwrite.
    savepoint = db.begin_nested()
    try:
        if event.type == EventType.REFILL:
            _apply_refill(db, event, event_data.payload)
        elif event.type == EventType.UNLOAD:
            _apply_unload(db, event, event_data.payload)
        elif event.type == EventType.SAMPLE:
            _apply_sample(db, event, event_data.payload)
        elif event.type == EventType.LOAD:
            savepoint.rollback()
            event.status = EventStatus.QUARANTINED
            event.conflict_reason = "LOAD material allocation requires the intake workflow"
            db.flush()
            return event
        savepoint.commit()
        db.flush()
    except EventConflict as exc:
        savepoint.rollback()
        event.status = EventStatus.QUARANTINED
        event.conflict_reason = str(exc)
        db.flush()
    return event


def _lock_compartment(db: Session, compartment_id: str) -> Compartment:
    obj = db.get(Compartment, uuid.UUID(compartment_id), with_for_update=True)
    if not obj:
        raise EventConflict(f"unknown compartment {compartment_id}")
    return obj


def _lock_tank(db: Session, tank_code: str) -> ReceivingTank:
    tank = db.scalar(select(ReceivingTank).where(ReceivingTank.tank_code == tank_code).with_for_update())
    if not tank:
        raise EventConflict(f"unknown receiving tank {tank_code}")
    return tank


def _create_node(db: Session, event: EventLog, node_type: MaterialNodeType, quantity: Decimal,
                 farm_batch_id: uuid.UUID | None = None) -> MaterialNode:
    node = MaterialNode(type=node_type, quantity=quantity, created_event_id=event.id,
                        farm_batch_id=farm_batch_id)
    db.add(node)
    db.flush()
    return node


def _add_edge(db: Session, event: EventLog, source_id: uuid.UUID | None,
              destination_id: uuid.UUID, quantity: Decimal,
              edge_kind: str = "TRANSFER") -> None:
    db.add(MaterialEdge(event_id=event.id, source_node_id=source_id,
                        destination_node_id=destination_id, edge_kind=edge_kind,
                        quantity=quantity))
    db.flush()


def _active_seal(db: Session, compartment_id: uuid.UUID, expected: str | None) -> Seal:
    if not expected:
        raise EventConflict("expected seal number is required")
    seal = db.scalar(select(Seal).where(Seal.seal_number == expected).with_for_update())
    if not seal or seal.compartment_id != compartment_id:
        raise EventConflict(f"seal conflict: {expected} does not belong to compartment")
    if seal.status == SealStatus.CONFLICT:
        raise EventConflict(f"seal {expected} is already marked CONFLICT")
    return seal


def _apply_refill(db: Session, event: EventLog, payload: dict[str, Any]) -> None:
    p = RefillPayload(**payload)
    lines = sorted(p.lines, key=lambda x: x.compartment_id)
    compartments = [_lock_compartment(db, line.compartment_id) for line in lines]
    total = sum((line.quantity for line in lines), Decimal("0"))
    if total <= 0:
        raise EventConflict("refill quantity must be positive")

    if p.expected_seal_number or p.new_seal_number:
        # A mid-trip refill legally breaks the receiving seal and applies a new one.
        if len(compartments) != 1:
            raise EventConflict("sealed mid-trip refill can target one compartment")
        old = _active_seal(db, compartments[0].id, p.expected_seal_number)
        if old.status != SealStatus.APPLIED:
            raise EventConflict("only an APPLIED seal can be broken for refill")
        old.status = SealStatus.BROKEN
        old.broken_event_id = event.id
    else:
        raise EventConflict("mid-trip refill requires expected and new seal numbers")

    for line, compartment in zip(lines, compartments):
        source = compartment.current_node
        if not source:
            raise EventConflict(f"compartment {compartment.label} has no current material node")
        if source.quantity + line.quantity > compartment.capacity:
            raise EventConflict(
                f"refill exceeds compartment capacity: {source.quantity}+{line.quantity}>{compartment.capacity}"
            )

    batches = []
    # Lock batches deterministically to preserve source conservation.
    for batch_id in sorted({b for line in lines for b in line.batch_allocations.keys()}):
        batch = db.get(FarmBatch, uuid.UUID(batch_id), with_for_update=True)
        if not batch:
            raise EventConflict(f"unknown farm batch {batch_id}")
        batches.append(batch)

    for line in lines:
        allocated = sum(line.batch_allocations.values(), Decimal("0"))
        if allocated != line.quantity:
            raise EventConflict("refill line batch allocations must sum to line quantity")

    # Mid-trip refill is modeled per compartment; the API intentionally does
    # not support merging two vehicle compartments without a receiving tank.
    for line, compartment in zip(lines, compartments):
        source = compartment.current_node
        node = _create_node(db, event, MaterialNodeType.COMPARTMENT, source.quantity + line.quantity)
        _add_edge(db, event, source.id, node.id, source.quantity, "CONTINUATION")
        for batch_id, qty in line.batch_allocations.items():
            batch_node = db.scalar(select(MaterialNode).where(
                MaterialNode.farm_batch_id == uuid.UUID(batch_id),
                MaterialNode.type == MaterialNodeType.FARM_BATCH
            ).with_for_update())
            used_qty = sum((e.quantity for e in db.scalars(select(MaterialEdge).where(MaterialEdge.source_node_id == batch_node.id, MaterialEdge.edge_kind == 'TRANSFER')).all()), Decimal("0"))
            if used_qty + qty > batch_node.quantity:
                raise EventConflict("farm batch source overdraw; global material conservation violated")
            _add_edge(db, event, batch_node.id, node.id, qty)
        compartment.current_node_id = node.id

    if p.new_seal_number:
        try:
            db.add(Seal(compartment_id=compartments[0].id, seal_number=p.new_seal_number,
                        status=SealStatus.APPLIED, applied_event_id=event.id))
            db.flush()
        except IntegrityError as exc:
            raise EventConflict("new seal number already exists") from exc


def _apply_unload(db: Session, event: EventLog, payload: dict[str, Any]) -> None:
    p = UnloadPayload(**payload)
    tank = _lock_tank(db, p.tank_code)
    # Deadlock-safe deterministic lock order across concurrent terminals.
    lines = sorted(p.lines, key=lambda x: x.compartment_id)
    if len({x.compartment_id for x in lines}) != len(lines):
        raise EventConflict("one unload event cannot list the same compartment twice; split or correct quantities")
    compartments = [_lock_compartment(db, x.compartment_id) for x in lines]
    total = sum((line.quantity for line in lines), Decimal("0"))
    if total <= 0:
        raise EventConflict("unload quantity must be positive")

    # Validate every source before creating any node. This makes compartment
    # overdraw explicit instead of hiding behind a receiving-tank capacity check.
    residual_nodes: list[tuple[Compartment, MaterialNode, Decimal, str]] = []
    input_qty = Decimal("0")
    for line, compartment in zip(lines, compartments):
        source = db.get(MaterialNode, line.source_node_id, with_for_update=True)
        if not source or source.type != MaterialNodeType.COMPARTMENT:
            raise EventConflict("unload source must be a compartment material node")
        if compartment.current_node_id != source.id:
            # A stale offline terminal is not allowed to overwrite a newer pointer.
            raise EventConflict("stale compartment pointer; refresh handoff state and create a new event")
        if source.quantity < line.quantity:
            raise EventConflict(f"unload quantity overdraw: requested {line.quantity}, remaining {source.quantity}")
        seal = _active_seal(db, compartment.id, line.expected_seal_number)
        if seal.status != SealStatus.APPLIED:
            raise EventConflict("only an APPLIED seal can be opened for unloading")
        if line.weight_reading_id:
            _verify_weight(db, line.weight_reading_id, line.quantity)
        input_qty += line.quantity
        residual_nodes.append((compartment, source, source.quantity - line.quantity, line.expected_seal_number))

    prior = db.get(MaterialNode, tank.current_node_id, with_for_update=True) if tank.current_node_id else None
    prior_qty_for_capacity = prior.quantity if prior else Decimal("0")
    if prior_qty_for_capacity + total > tank.capacity:
        raise EventConflict("unload exceeds receiving tank capacity")
    prior_qty = prior.quantity if prior else Decimal("0")
    tank_node = _create_node(db, event, MaterialNodeType.TANK, prior_qty + input_qty)
    if prior:
        _add_edge(db, event, prior.id, tank_node.id, prior.quantity)
    for line, (_, source, _remaining, _seal) in zip(lines, residual_nodes):
        _add_edge(db, event, source.id, tank_node.id, line.quantity)

    # Every partially unloaded compartment gets an append-only residual node.
    for compartment, source, remaining, seal_number in residual_nodes:
        residual = _create_node(db, event, MaterialNodeType.COMPARTMENT, remaining)
        _add_edge(db, event, source.id, residual.id, remaining, "CONTINUATION")
        compartment.current_node_id = residual.id
        seal = _active_seal(db, compartment.id, seal_number)
        # A partially unloaded compartment remains closed and reusable; a
        # fully emptied compartment consumes/breaks the handoff seal.
        if remaining == 0:
            seal.status = SealStatus.BROKEN
            seal.broken_event_id = event.id

    tank.current_node_id = tank_node.id
    tank.lineage_version += 1
    tank.updated_at = datetime.now(timezone.utc)

    # The unload holds the physical-tank row lock, so this cannot race a
    # signature. Any request for the pre-unload snapshot is superseded; a
    # signature can never silently cover the newly mixed material.
    prior_apps = db.scalars(select(ReleaseApplication).where(
        ReleaseApplication.tank_id == tank.id,
        ReleaseApplication.status.in_([ReleaseStatus.PENDING, ReleaseStatus.SIGNED]),
        ReleaseApplication.expected_node_id == (prior.id if prior else None)
    ).with_for_update()).all()
    for app in prior_apps:
        app.status = ReleaseStatus.INVALIDATED
        app.invalid_reason = "unload changed physical-tank lineage after request"
    db.flush()


def _verify_weight(db: Session, reading_id: str, requested_qty: Decimal) -> None:
    reading = db.scalar(select(DeviceReading).where(DeviceReading.reading_id == reading_id).with_for_update())
    if not reading or reading.device_type != DeviceType.WEIGHT:
        raise EventConflict(f"weight reading {reading_id} is missing")
    if abs(reading.value - requested_qty) > Decimal(str(settings.weight_tolerance_kg)):
        raise EventConflict(
            f"weight mismatch: device {reading.value}{reading.unit}, declared {requested_qty}, tolerance {settings.weight_tolerance_kg}"
        )


def _apply_sample(db: Session, event: EventLog, payload: dict[str, Any]) -> None:
    p = SamplePayload(**payload)
    source = db.get(MaterialNode, uuid.UUID(p.source_node_id), with_for_update=True)
    if not source:
        raise EventConflict("unknown sample source node")
    sample_node = None
    if p.quantity > 0:
        if source.type not in (MaterialNodeType.COMPARTMENT, MaterialNodeType.TANK):
            raise EventConflict("physical sample can be taken only from compartment or tank node")
        if source.quantity < p.quantity:
            raise EventConflict("sample quantity exceeds source quantity")
        sample_node = _create_node(db, event, MaterialNodeType.SAMPLE, p.quantity)
        _add_edge(db, event, source.id, sample_node.id, p.quantity)
    sample = Sample(
        sample_code=p.sample_code,
        source_node_id=source.id,
        sample_node_id=sample_node.id if sample_node else None,
        register_event_id=event.id,
        farm_batch_id=uuid.UUID(p.farm_batch_id) if p.farm_batch_id else None,
        quantity=p.quantity,
        status=SampleStatus.REGISTERED,
        current_version=0,
    )
    db.add(sample)
    try:
        db.flush()
    except IntegrityError as exc:
        raise EventConflict(f"sample code {p.sample_code} already exists") from exc


def submit_lab_result(db: Session, sample_id: str, result_in, analyst: User,
                      terminal_id: str = "lab-terminal") -> tuple[Sample, LabResult]:
    sample = db.get(Sample, uuid.UUID(sample_id), with_for_update=True)
    if not sample:
        raise HTTPException(status_code=404, detail="sample not found")
    next_version = sample.current_version + 1
    prior = db.scalar(select(LabResult).where(LabResult.sample_id == sample.id,
                                              LabResult.version == sample.current_version))
    if prior and prior.result == result_in.result:
        raise HTTPException(status_code=409, detail="new result must actually correct the prior conclusion")

    linked_event = None
    if result_in.event_id:
        linked_event = db.scalar(select(EventLog).where(EventLog.event_id == result_in.event_id).with_for_update())
        if not linked_event:
            raise HTTPException(status_code=404, detail="linked result event not found")
        # The event identity may have been generated on an offline lab terminal.
        if linked_event.type != EventType.SAMPLE:
            linked_event = None

    result = LabResult(sample_id=sample.id, version=next_version, result=result_in.result,
                       analyst_user_id=analyst.id, result_event_id=linked_event.id if linked_event else None,
                       components=result_in.components, note=result_in.note)
    db.add(result)
    sample.current_version = next_version
    sample.status = (
        (SampleStatus.REJECTED if result_in.result == LabResultStatus.REJECT else SampleStatus.ACCEPTED)
        if next_version == 1 else SampleStatus.CORRECTED
    )

    # Any correction (even to an accepting conclusion) changes the signed
    # evidence packet and invalidates open downstream release requests.
    affected = downstream_tank_ids(db, sample.source_node_id, sample.farm_batch_id)
    for tank_id in affected:
        tank = db.get(ReceivingTank, tank_id, with_for_update=True)
        tank.lineage_version += 1
        tank.updated_at = datetime.now(timezone.utc)
        pending = db.scalars(select(ReleaseApplication).where(
            ReleaseApplication.tank_id == tank.id,
            ReleaseApplication.status == ReleaseStatus.PENDING
        ).with_for_update()).all()
        for app in pending:
            app.status = ReleaseStatus.INVALIDATED
            app.invalid_reason = f"lab result version {next_version} for sample {sample.sample_code}"
    db.flush()
    return sample, result


def ingest_device_reading(db: Session, reading) -> DeviceReading:
    existing = db.scalar(select(DeviceReading).where(DeviceReading.reading_id == reading.reading_id))
    if existing:
        return existing
    obj = DeviceReading(
        reading_id=reading.reading_id, terminal_id=reading.terminal_id,
        device_type=DeviceType(reading.device_type), device_id=reading.device_id,
        vehicle_plate=reading.vehicle_plate, value=reading.value, unit=reading.unit,
        recorded_at=reading.recorded_at, raw_payload=reading.raw_payload,
        replay_position=reading.replay_position,
    )
    db.add(obj)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        return db.scalar(select(DeviceReading).where(DeviceReading.reading_id == reading.reading_id))
    return obj


from pydantic import BaseModel, Field


class RefillLine(BaseModel):
    compartment_id: str
    quantity: Decimal = Field(gt=0)
    batch_allocations: dict[str, Decimal]


class RefillPayload(BaseModel):
    lines: list[RefillLine]
    expected_seal_number: str
    new_seal_number: str


class UnloadLinePayload(BaseModel):
    compartment_id: str
    source_node_id: str
    quantity: Decimal = Field(gt=0)
    expected_seal_number: str
    weight_reading_id: str | None = None


class UnloadPayload(BaseModel):
    tank_code: str
    lines: list[UnloadLinePayload]


class SamplePayload(BaseModel):
    sample_code: str
    source_node_id: str
    farm_batch_id: str | None = None
    quantity: Decimal = Field(default=Decimal("0"), ge=0)
