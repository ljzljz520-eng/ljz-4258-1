from __future__ import annotations

import hashlib
import json
from collections import defaultdict, deque
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any, Iterable

from fastapi import HTTPException
from sqlalchemy import and_, update
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session

from app.models import (
    Arrival, ArrivalItem, Compartment, DeviceReading, Farm, MaterialFlow, MaterialNode,
    PastureBatch, ReceivingDecision, ReceivingTank, ReconciliationIssue, ReleaseRequest,
    Sample, SampleResult, Seal, TopUpEvent, UnloadEvent, Vehicle,
)

CARRY_CAUSES = {"compartment_carry", "tank_carry"}


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def payload_hash(payload: dict[str, Any]) -> str:
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, default=str, separators=(",", ":")).encode()
    ).hexdigest()


def money_dict(value: Any) -> Any:
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, datetime):
        return value.isoformat()
    return value


class EventRejected(HTTPException):
    def __init__(self, issue_type: str, event_id: str, details: dict[str, Any]):
        key = f"{issue_type}:{event_id}"
        self.issue_key = key
        self.issue_type = issue_type
        self.event_id = event_id
        self.details = details
        super().__init__(status_code=409, detail={"status": "quarantined", "issue_key": key, "details": details})


def run_retrying(db: Session, fn, *args, retries: int = 5, **kwargs):
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            with db.begin():
                return fn(db, *args, **kwargs)
        except EventRejected as exc:
            db.rollback()
            with db.begin():
                db.add(ReconciliationIssue(
                    issue_key=exc.issue_key,
                    issue_type=exc.issue_type,
                    event_id=exc.event_id,
                    details=json.loads(json.dumps(exc.details, default=money_dict)),
                ))
            raise
        except OperationalError as exc:  # serializable/deadlock/lock timeout
            db.rollback()
            last_error = exc
            if attempt == retries - 1:
                raise
        except IntegrityError:
            db.rollback()
            raise
    raise last_error  # pragma: no cover


def _open_issue(db: Session, issue_type: str, event_id: str, details: dict[str, Any]) -> ReconciliationIssue:
    key = f"{issue_type}:{event_id}"
    issue = db.query(ReconciliationIssue).filter(ReconciliationIssue.issue_key == key).one_or_none()
    if not issue:
        issue = ReconciliationIssue(
            issue_key=key, issue_type=issue_type, event_id=event_id,
            details=json.loads(json.dumps(details, default=money_dict)),
        )
        db.add(issue)
        db.flush()
    return issue


def reject_event(db: Session, issue_type: str, event_id: str, details: dict[str, Any]):
    issue = _open_issue(db, issue_type, event_id, details)
    raise EventRejected(issue_type, event_id, {"issue_key": issue.issue_key, **details})


def get_vehicle(db: Session, code: str) -> Vehicle:
    vehicle = db.query(Vehicle).filter(Vehicle.code == code).one_or_none()
    if not vehicle:
        raise HTTPException(404, f"Vehicle {code} not found")
    return vehicle


def get_compartment(db: Session, vehicle_code: str, code: str) -> Compartment:
    comp = (
        db.query(Compartment)
        .join(Vehicle, Compartment.vehicle_id == Vehicle.id)
        .filter(Vehicle.code == vehicle_code, Compartment.code == code)
        .one_or_none()
    )
    if not comp:
        raise HTTPException(404, f"Compartment {vehicle_code}/{code} not found")
    return comp


def get_tank(db: Session, code: str) -> ReceivingTank:
    tank = db.query(ReceivingTank).filter(ReceivingTank.code == code).one_or_none()
    if not tank:
        raise HTTPException(404, f"Tank {code} not found")
    return tank


def get_batch(db: Session, code: str) -> PastureBatch:
    batch = db.query(PastureBatch).filter(PastureBatch.code == code).one_or_none()
    if not batch:
        raise HTTPException(404, f"Pasture batch {code} not found")
    return batch


def _next_seq(db: Session, *, compartment_id: int | None = None, tank_id: int | None = None) -> int:
    query = db.query(MaterialNode)
    if compartment_id is not None:
        query = query.filter(MaterialNode.compartment_id == compartment_id)
    if tank_id is not None:
        query = query.filter(MaterialNode.tank_id == tank_id)
    return (query.count() or 0) + 1


def _pasture_node(db: Session, batch: PastureBatch, amount: Decimal, *, must_exist: bool) -> MaterialNode:
    node = (
        db.query(MaterialNode)
        .filter(
            MaterialNode.node_type == "pasture_batch",
            MaterialNode.pasture_batch_id == batch.id,
        )
        .order_by(MaterialNode.id.desc())
        .first()
    )
    if not node:
        if must_exist:
            raise HTTPException(409, f"Pasture batch {batch.code} has no registered source material")
        node = MaterialNode(
            node_type="pasture_batch", pasture_batch_id=batch.id,
            remaining_amount=batch.declared_amount, version=1,
        )
        db.add(node)
        db.flush()
    if node.remaining_amount < amount:
        raise HTTPException(409, f"Pasture batch {batch.code} remaining {node.remaining_amount} < {amount}")
    return node


def _add_flow(db: Session, from_node: int, to_node: int, amount: Decimal, cause: str) -> MaterialFlow:
    if from_node == to_node:
        raise HTTPException(422, "Self-loop material flow is forbidden")
    flow = MaterialFlow(from_node_id=from_node, to_node_id=to_node, amount=amount, cause=cause)
    db.add(flow)
    return flow


def _assert_reaches_no_cycle(db: Session, source_id: int, destination_id: int) -> None:
    # New edges always terminate at freshly-created state nodes; this guard additionally prevents
    # wiring a future destination that can already reach the source.
    seen: set[int] = set()
    queue = deque([destination_id])
    edges = defaultdict(list)
    for edge in db.query(MaterialFlow).all():
        edges[edge.from_node_id].append(edge.to_node_id)
    while queue:
        node = queue.popleft()
        if node == source_id:
            raise HTTPException(422, "Refusing cyclic material flow")
        if node in seen:
            continue
        seen.add(node)
        queue.extend(edges[node])


def _create_arrival_txn(db: Session, payload: dict, user_id: int) -> dict:
    event_id = payload["event_id"]
    existing = db.query(Arrival).filter(Arrival.event_id == event_id).one_or_none()
    fingerprint = payload_hash({k: v for k, v in payload.items() if k != "terminal_id"})
    if existing:
        if existing.status == "quarantined" or payload_hash(_arrival_canonical(existing)) != fingerprint:
            reject_event(db, "arrival_conflict", event_id, {"reason": "different payload for same event identity"})
        return {"event_id": event_id, "status": "accepted", "idempotent": True, "arrival_id": existing.id}

    vehicle = get_vehicle(db, payload["vehicle_code"])
    arrival = Arrival(
        event_id=event_id, vehicle_id=vehicle.id,
        arrived_at=payload.get("arrived_at") or utcnow(),
        created_by=user_id,
    )
    db.add(arrival)
    db.flush()
    result_items = []
    for item in payload["items"]:
        comp = get_compartment(db, vehicle.code, item["compartment_code"])
        if comp.current_node_id is not None:
            current = db.get(MaterialNode, comp.current_node_id)
            if current and current.remaining_amount > 0:
                reject_event(db, "compartment_occupied", event_id, {
                    "compartment": comp.code, "remaining_amount": current.remaining_amount,
                })
        batch = get_batch(db, item["pasture_batch_code"])
        amount = Decimal(str(item["declared_amount"]))
        if amount > comp.capacity:
            reject_event(db, "capacity_exceeded", event_id, {"capacity": comp.capacity, "amount": amount})
        source = _pasture_node(db, batch, amount, must_exist=False)

        comp_node = MaterialNode(
            node_type="compartment", compartment_id=comp.id, pasture_batch_id=batch.id,
            state_seq=_next_seq(db, compartment_id=comp.id), remaining_amount=amount, version=1,
        )
        db.add(comp_node)
        db.flush()
        source.remaining_amount -= amount
        source.version += 1
        _add_flow(db, source.id, comp_node.id, amount, "load")
        comp.current_node_id = comp_node.id
        comp.version += 1

        row = ArrivalItem(
            arrival_id=arrival.id, compartment_id=comp.id, pasture_batch_id=batch.id,
            declared_amount=amount, initial_node_id=comp_node.id,
        )
        db.add(row)
        db.flush()
        for code in item["seal_codes"]:
            db.add(Seal(arrival_item_id=row.id, code=code, registered_by=user_id))
        result_items.append({"compartment": comp.code, "node_id": comp_node.id, "amount": format(amount, "f")})
    db.flush()
    return {"event_id": event_id, "status": "accepted", "arrival_id": arrival.id, "items": result_items}


def _arrival_canonical(arrival: Arrival) -> dict:
    return {
        "event_id": arrival.event_id,
        "vehicle_code": arrival.vehicle.code,
        "items": [
            {
                "compartment_code": i.compartment.code,
                "pasture_batch_code": i.pasture_batch.code,
                "declared_amount": format(i.declared_amount, "f"),
                "seal_codes": sorted(s.code for s in i.seals),
            }
            for i in arrival.items
        ],
    }


def create_arrival(db: Session, payload: dict, user_id: int) -> dict:
    return run_retrying(db, _create_arrival_txn, payload, user_id=user_id)


def _expected_seal(db: Session, comp: Compartment) -> Seal | None:
    return (
        db.query(Seal)
        .join(ArrivalItem, Seal.arrival_item_id == ArrivalItem.id)
        .filter(ArrivalItem.compartment_id == comp.id)
        .order_by(Seal.id.desc())
        .first()
    )


def _new_state_node(old: MaterialNode | None, remaining: Decimal, node_type: str,
                    compartment_id: int | None, tank_id: int | None,
                    batch_id: int | None = None) -> MaterialNode:
    return MaterialNode(
        node_type=node_type,
        compartment_id=compartment_id,
        tank_id=tank_id,
        pasture_batch_id=batch_id,
        state_seq=(old.state_seq + 1 if old else 1),
        remaining_amount=remaining,
        version=(old.version + 1 if old else 1),
    )


def _create_unload_txn(db: Session, payload: dict, user_id: int) -> dict:
    event_id = payload["event_id"]
    fingerprint = payload_hash({k: v for k, v in payload.items() if k != "terminal_id"})
    existing = db.query(UnloadEvent).filter(UnloadEvent.event_id == event_id).one_or_none()
    if existing:
        if existing.payload_hash != fingerprint:
            _open_issue(db, "duplicate_event_conflict", event_id, {
                "reason": "same event id submitted with different payload; first event retained",
                "stored_status": existing.status,
            })
            return {"event_id": event_id, "status": "quarantined", "issue_key": f"duplicate_event_conflict:{event_id}", "idempotent": False}
        return {"event_id": event_id, "status": existing.status, "idempotent": True, "unload_id": existing.id}

    comp = get_compartment(db, payload["vehicle_code"], payload["compartment_code"])
    tank = get_tank(db, payload["tank_code"])
    amount = Decimal(str(payload["amount"]))
    source = db.get(MaterialNode, comp.current_node_id) if comp.current_node_id else None
    if not source:
        reject_event(db, "empty_compartment", event_id, {"compartment": comp.code})

    seal = _expected_seal(db, comp)
    expected_code = seal.code if seal else None
    if not seal or payload["observed_seal_code"] != expected_code or not payload.get("seal_intact", False):
        reject_event(db, "seal_conflict", event_id, {
            "compartment": comp.code,
            "expected_seal_code": expected_code,
            "observed_seal_code": payload["observed_seal_code"],
            "seal_intact": payload.get("seal_intact"),
        })

    old_tank = db.get(MaterialNode, tank.current_node_id) if tank.current_node_id else None
    old_tank_remaining = old_tank.remaining_amount if old_tank else Decimal("0")
    if old_tank_remaining + amount > tank.capacity:
        reject_event(db, "tank_capacity_exceeded", event_id, {
            "tank": tank.code, "capacity": format(tank.capacity, "f"),
            "existing": format(old_tank_remaining, "f"), "requested": format(amount, "f"),
        })

    source_result = db.execute(
        update(MaterialNode)
        .where(and_(
            MaterialNode.id == source.id,
            MaterialNode.version == source.version,
            MaterialNode.remaining_amount >= amount,
        ))
        .values(remaining_amount=MaterialNode.remaining_amount - amount, version=MaterialNode.version + 1)
    )
    pointer_result = db.execute(
        update(Compartment)
        .where(and_(
            Compartment.id == comp.id,
            Compartment.version == comp.version,
            Compartment.current_node_id == source.id,
        ))
        .values(version=Compartment.version + 1)
    )
    if source_result.rowcount != 1 or pointer_result.rowcount != 1:
        reject_event(db, "quantity_overrun", event_id, {
            "compartment": comp.code,
            "available": format(source.remaining_amount, "f"),
            "requested": format(amount, "f"),
        })

    db.refresh(source)
    destination = _new_state_node(
        old_tank, old_tank.remaining_amount + amount, "tank",
        compartment_id=None, tank_id=tank.id,
    )
    db.add(destination)
    db.flush()
    _assert_reaches_no_cycle(db, source.id, destination.id)
    if old_tank:
        _add_flow(db, old_tank.id, destination.id, old_tank.remaining_amount, "tank_carry")
    flow = _add_flow(db, source.id, destination.id, amount, "unload")

    tank_result = db.execute(
        update(ReceivingTank)
        .where(and_(
            ReceivingTank.id == tank.id,
            ReceivingTank.version == tank.version,
            (ReceivingTank.current_node_id == old_tank.id) if old_tank else ReceivingTank.current_node_id.is_(None),
        ))
        .values(current_node_id=destination.id, version=ReceivingTank.version + 1)
    )
    if tank_result.rowcount != 1:
        reject_event(db, "tank_state_conflict", event_id, {"tank": tank.code})

    event = UnloadEvent(
        event_id=event_id, compartment_id=comp.id, tank_id=tank.id,
        source_node_id=source.id, destination_node_id=destination.id, amount=amount,
        observed_seal_code=payload["observed_seal_code"], seal_intact=bool(payload["seal_intact"]),
        temperature_c=payload.get("temperature_c"), weight_kg=payload.get("weight_kg"),
        status="accepted", payload_hash=fingerprint, recorded_by=user_id,
        occurred_at=payload.get("occurred_at") or utcnow(),
    )
    db.add(event)
    db.flush()
    db.refresh(tank)
    return {
        "event_id": event_id, "status": "accepted", "unload_id": event.id,
        "flow_id": flow.id, "source_node_id": source.id, "destination_node_id": destination.id,
        "source_remaining": format(source.remaining_amount, "f"),
        "tank_remaining": format(destination.remaining_amount, "f"),
    }


def create_unload(db: Session, payload: dict, user_id: int) -> dict:
    return run_retrying(db, _create_unload_txn, payload, user_id=user_id)


def _create_topup_txn(db: Session, payload: dict, user_id: int) -> dict:
    event_id = payload["event_id"]
    fingerprint = payload_hash({k: v for k, v in payload.items() if k != "terminal_id"})
    existing = db.query(TopUpEvent).filter(TopUpEvent.event_id == event_id).one_or_none()
    if existing:
        if existing.payload_hash != fingerprint:
            _open_issue(db, "duplicate_event_conflict", event_id, {
                "reason": "same top-up id submitted with different payload; first event retained",
            })
            return {"event_id": event_id, "status": "quarantined", "issue_key": f"duplicate_event_conflict:{event_id}"}
        return {"event_id": event_id, "status": existing.status, "idempotent": True, "topup_id": existing.id}

    comp = get_compartment(db, payload["vehicle_code"], payload["compartment_code"])
    batch = get_batch(db, payload["pasture_batch_code"])
    amount = Decimal(str(payload["amount"]))
    old_node = db.get(MaterialNode, comp.current_node_id) if comp.current_node_id else None
    old_remaining = old_node.remaining_amount if old_node else Decimal("0")
    if old_remaining + amount > comp.capacity:
        reject_event(db, "capacity_exceeded", event_id, {
            "compartment": comp.code, "capacity": format(comp.capacity, "f"),
            "existing": format(old_remaining, "f"), "requested": format(amount, "f"),
        })
    source = _pasture_node(db, batch, amount, must_exist=True)
    new_node = _new_state_node(
        old_node, old_remaining + amount, "compartment",
        compartment_id=comp.id, tank_id=None, batch_id=batch.id,
    )
    db.add(new_node)
    db.flush()
    _assert_reaches_no_cycle(db, source.id, new_node.id)
    if old_node:
        _assert_reaches_no_cycle(db, old_node.id, new_node.id)
        _add_flow(db, old_node.id, new_node.id, old_node.remaining_amount, "compartment_carry")
    flow = _add_flow(db, source.id, new_node.id, amount, "topup")

    source_result = db.execute(
        update(MaterialNode)
        .where(and_(MaterialNode.id == source.id, MaterialNode.version == source.version,
                    MaterialNode.remaining_amount >= amount))
        .values(remaining_amount=MaterialNode.remaining_amount - amount, version=MaterialNode.version + 1)
    )
    pointer_result = db.execute(
        update(Compartment)
        .where(and_(
            Compartment.id == comp.id, Compartment.version == comp.version,
            (Compartment.current_node_id == old_node.id) if old_node else Compartment.current_node_id.is_(None),
        ))
        .values(current_node_id=new_node.id, version=Compartment.version + 1)
    )
    if source_result.rowcount != 1 or pointer_result.rowcount != 1:
        reject_event(db, "topup_conflict", event_id, {"compartment": comp.code})

    event = TopUpEvent(
        event_id=event_id, compartment_id=comp.id, pasture_batch_id=batch.id,
        from_node_id=source.id, to_node_id=new_node.id, amount=amount,
        temperature_c=payload.get("temperature_c"), status="accepted",
        payload_hash=fingerprint, recorded_by=user_id,
        occurred_at=payload.get("occurred_at") or utcnow(),
    )
    db.add(event)
    db.flush()
    return {
        "event_id": event_id, "status": "accepted", "topup_id": event.id,
        "flow_id": flow.id, "source_node_id": source.id, "destination_node_id": new_node.id,
        "compartment_remaining": format(new_node.remaining_amount, "f"),
    }


def create_topup(db: Session, payload: dict, user_id: int) -> dict:
    return run_retrying(db, _create_topup_txn, payload, user_id=user_id)


def record_device_reading_txn(db: Session, payload: dict) -> dict:
    reading_id = payload["reading_id"]
    existing = db.query(DeviceReading).filter(DeviceReading.reading_id == reading_id).one_or_none()
    if existing:
        return {"reading_id": reading_id, "idempotent": True}
    reading = DeviceReading(**payload)
    db.add(reading)
    return {"reading_id": reading_id, "idempotent": False}


def record_device_reading(db: Session, payload: dict) -> dict:
    with db.begin():
        return record_device_reading_txn(db, payload)


def create_sample_txn(db: Session, payload: dict, user_id: int) -> dict:
    comp = get_compartment(db, payload["vehicle_code"], payload["compartment_code"])
    if not comp.current_node_id:
        raise HTTPException(409, "Compartment has no material state to sample")
    node = db.get(MaterialNode, comp.current_node_id)
    sample = Sample(
        sample_code=payload["sample_code"], compartment_id=comp.id, node_id=node.id,
        collected_by=user_id, collected_at=payload.get("collected_at") or utcnow(),
    )
    db.add(sample)
    db.flush()
    return {"sample_id": sample.id, "sample_code": sample.sample_code, "node_id": node.id,
            "remaining_amount": format(node.remaining_amount, "f")}


def create_sample(db: Session, payload: dict, user_id: int) -> dict:
    with db.begin():
        return create_sample_txn(db, payload, user_id)


def handover_sample_txn(db: Session, sample_code: str, phase: str, user_id: int) -> dict:
    sample = db.query(Sample).filter(Sample.sample_code == sample_code).one_or_none()
    if not sample:
        raise HTTPException(404, "Sample not found")
    if phase == "handover":
        if sample.status not in {"collected", "handover"}:
            raise HTTPException(409, "Sample already received by lab")
        sample.status = "handover"
        sample.handover_by = user_id
        sample.handover_at = utcnow()
    else:
        if sample.status == "lab_received":
            return {"sample_code": sample_code, "idempotent": True, "phase": phase}
        sample.status = "lab_received"
        sample.lab_received_by = user_id
        sample.lab_received_at = utcnow()
    db.flush()
    return {"sample_code": sample_code, "status": sample.status, "phase": phase}


def handover_sample(db: Session, *args) -> dict:
    with db.begin():
        return handover_sample_txn(db, *args)


def _load_flow_indexes(flows: Iterable[MaterialFlow]):
    downstream = defaultdict(list)
    upstream = defaultdict(list)
    for flow in flows:
        adjacency[flow.from_node_id].append(flow)
        upstream[flow.to_node_id].append(flow)
    downstream_idx, upstream_idx = adjacency, upstream
    return downstream_idx, upstream_idx


def _downstream_graph(db: Session, start_node_id: int):
    flows = db.query(MaterialFlow).all()
    adjacency, _ = _load_flow_indexes(flows)
    reachable_nodes = {start_node_id}
    reachable_flows = []
    queue = deque([start_node_id])
    while queue:
        node_id = queue.popleft()
        for flow in adjacency[node_id]:
            reachable_flows.append(flow)
            if flow.to_node_id not in reachable_nodes:
                reachable_nodes.add(flow.to_node_id)
                queue.append(flow.to_node_id)
    nodes = db.query(MaterialNode).filter(MaterialNode.id.in_(reachable_nodes)).all()
    return nodes, reachable_flows


def _upstream_graph(db: Session, node_id: int):
    flows = db.query(MaterialFlow).all()
    _, reverse = _load_flow_indexes(flows)
    reachable_nodes = {node_id}
    reachable_flows = []
    queue = deque([node_id])
    while queue:
        current = queue.popleft()
        for flow in reverse[current]:
            reachable_flows.append(flow)
            if flow.from_node_id not in reachable_nodes:
                reachable_nodes.add(flow.from_node_id)
                queue.append(flow.from_node_id)
    nodes = db.query(MaterialNode).filter(MaterialNode.id.in_(reachable_nodes)).all()
    return nodes, reachable_flows


def sample_impact(db: Session, sample_code: str) -> dict:
    sample = db.query(Sample).filter(Sample.sample_code == sample_code).one_or_none()
    if not sample:
        raise HTTPException(404, "Sample not found")
    nodes, flows = _downstream_graph(db, sample.node_id)
    tanks = []
    remaining_compartments = []
    for node in nodes:
        if node.node_type == "tank":
            tanks.append({"node_id": node.id, "tank_id": node.tank_id, "remaining_amount": format(node.remaining_amount, "f")})
        if node.node_type == "compartment" and node.remaining_amount > 0:
            remaining_compartments.append({
                "node_id": node.id, "compartment_id": node.compartment_id,
                "remaining_amount": format(node.remaining_amount, "f"),
            })
    return {
        "sample_code": sample_code,
        "source_node_id": sample.node_id,
        "affected_tanks": tanks,
        "remaining_compartment_material": remaining_compartments,
        "flow_ids": [f.id for f in flows],
    }


def lineage_version(db: Session, node_id: int) -> str:
    # A release snapshot must change when any upstream source, edge, state, or lab result changes.
    nodes, flows = _upstream_graph(db, node_id)
    node_ids = {n.id for n in nodes}
    samples = db.query(Sample).filter(Sample.node_id.in_(node_ids)).all()
    sample_records = []
    for sample in samples:
        result = db.get(SampleResult, sample.current_result_id) if sample.current_result_id else None
        sample_records.append({
            "sample": sample.sample_code,
            "node": sample.node_id,
            "status": sample.status,
            "revision": result.revision if result else None,
            "outcome": result.outcome if result else None,
            "corrected": result.corrected if result else None,
        })
    payload = {
        "nodes": sorted([
            {"id": n.id, "type": n.node_type, "remaining": format(n.remaining_amount, "f"), "version": n.version}
            for n in nodes
        ], key=lambda x: x["id"]),
        "flows": sorted([
            {"id": f.id, "from": f.from_node_id, "to": f.to_node_id,
             "amount": format(f.amount, "f"), "cause": f.cause}
            for f in flows
        ], key=lambda x: x["id"]),
        "samples": sorted(sample_records, key=lambda x: x["sample"]),
    }
    return payload_hash(payload)


def record_result_txn(db: Session, payload: dict, user_id: int) -> dict:
    sample = db.query(Sample).filter(Sample.sample_code == payload["sample_code"]).one_or_none()
    if not sample:
        raise HTTPException(404, "Sample not found")
    revision = (
        db.query(SampleResult).filter(SampleResult.sample_id == sample.id).count() + 1
    )
    previous = db.get(SampleResult, sample.current_result_id) if sample.current_result_id else None
    corrected = bool(payload.get("corrected") or previous is not None)
    result = SampleResult(
        sample_id=sample.id, revision=revision, outcome=payload["outcome"],
        analyst_id=user_id, notes=payload.get("notes"), corrected=corrected,
        resulted_at=payload.get("resulted_at") or utcnow(),
    )
    db.add(result)
    db.flush()
    sample.current_result_id = result.id
    sample.status = "resulted"

    invalidated = []
    if corrected and previous and previous.outcome != result.outcome:
        nodes, _ = _downstream_graph(db, sample.node_id)
        current_tank_nodes = {n.id for n in nodes if n.node_type == "tank"}
        pending = db.query(ReleaseRequest).filter(
            ReleaseRequest.status == "pending",
            ReleaseRequest.node_id.in_(current_tank_nodes),
        ).all()
        for request in pending:
            request.status = "invalidated"
            request.decision_notes = (
                f"Invalidated by corrected laboratory result for sample {sample.sample_code}: "
                f"{previous.outcome} -> {result.outcome}"
            )
            invalidated.append(request.request_no)
    db.flush()
    return {"sample_code": sample.sample_code, "revision": revision, "outcome": result.outcome,
            "corrected": corrected, "invalidated_release_requests": invalidated}


def record_result(db: Session, payload: dict, user_id: int) -> dict:
    with db.begin():
        return record_result_txn(db, payload, user_id)


def create_decision_txn(db: Session, payload: dict, user_id: int) -> dict:
    arrival = db.query(Arrival).filter(Arrival.event_id == payload["arrival_event_id"]).one_or_none()
    if not arrival:
        raise HTTPException(404, "Arrival not found")
    existing = db.query(ReceivingDecision).filter(ReceivingDecision.arrival_id == arrival.id).one_or_none()
    if existing:
        raise HTTPException(409, "Human receiving conclusion already recorded; it cannot be overwritten")
    decision = ReceivingDecision(
        arrival_id=arrival.id, conclusion=payload["conclusion"],
        notes=payload.get("notes"), confirmed_by=user_id,
    )
    db.add(decision)
    arrival.status = payload["conclusion"]
    db.flush()
    return {"arrival_event_id": arrival.event_id, "conclusion": decision.conclusion}


def create_decision(db: Session, payload: dict, user_id: int) -> dict:
    with db.begin():
        return create_decision_txn(db, payload, user_id)


def create_release_txn(db: Session, payload: dict, user_id: int) -> dict:
    tank = get_tank(db, payload["tank_code"])
    if not tank.current_node_id:
        raise HTTPException(409, "Tank is empty")
    node = db.get(MaterialNode, tank.current_node_id)
    version = lineage_version(db, node.id)
    request = ReleaseRequest(
        request_no=payload["request_no"], tank_id=tank.id, node_id=node.id,
        lineage_version=version, reason=payload.get("reason"), requested_by=user_id,
    )
    db.add(request)
    db.flush()
    return {"request_no": request.request_no, "status": "pending", "lineage_version": version,
            "node_id": node.id}


def create_release(db: Session, payload: dict, user_id: int) -> dict:
    with db.begin():
        return create_release_txn(db, payload, user_id)


def decide_release_txn(db: Session, payload: dict, user_id: int) -> dict:
    request = db.query(ReleaseRequest).filter(ReleaseRequest.request_no == payload["request_no"]).one_or_none()
    if not request:
        raise HTTPException(404, "Release request not found")
    if request.status != "pending":
        raise HTTPException(409, f"Request is {request.status}, not signable")
    current_version = lineage_version(db, request.node_id)
    if payload["expected_lineage_version"] != request.lineage_version:
        raise HTTPException(409, "Expected lineage version does not match application snapshot")
    if current_version != request.lineage_version:
        request.status = "invalidated"
        request.decision_notes = "Lineage changed before signature; signer must review a fresh application"
        db.flush()
        return {"request_no": request.request_no, "status": "invalidated",
                "snapshot_version": request.lineage_version, "current_version": current_version}
    request.status = "approved" if payload["approve"] else "rejected"
    request.decided_by = user_id
    request.decision_notes = payload.get("notes")
    request.decided_at = utcnow()
    db.flush()
    return {"request_no": request.request_no, "status": request.status,
            "lineage_version": current_version}


def decide_release(db: Session, payload: dict, user_id: int) -> dict:
    with db.begin():
        return decide_release_txn(db, payload, user_id)


def resolve_issue_txn(db: Session, issue_key: str, resolution: str, user_id: int) -> dict:
    issue = db.query(ReconciliationIssue).filter(ReconciliationIssue.issue_key == issue_key).one_or_none()
    if not issue:
        raise HTTPException(404, "Reconciliation issue not found")
    if issue.status != "open":
        return {"issue_key": issue_key, "status": issue.status, "idempotent": True}
    issue.status = "resolved"
    issue.resolution = resolution
    issue.resolved_by = user_id
    return {"issue_key": issue_key, "status": "resolved"}


def resolve_issue(db: Session, *args) -> dict:
    with db.begin():
        return resolve_issue_txn(db, *args)


def conservation_summary(db: Session) -> dict:
    source_nodes = db.query(MaterialNode).filter(MaterialNode.node_type == "pasture_batch").all()
    source_ids = [node.id for node in source_nodes]
    outgoing_source_flows = db.query(MaterialFlow).filter(MaterialFlow.from_node_id.in_(source_ids)).all()
    superseded_state_ids = {
        row[0] for row in db.query(MaterialFlow.from_node_id)
        .filter(MaterialFlow.cause.in_(list(CARRY_CAUSES))).all()
    }
    state_query = db.query(MaterialNode).filter(MaterialNode.node_type.in_(["compartment", "tank"]))
    if superseded_state_ids:
        state_query = state_query.filter(~MaterialNode.id.in_(superseded_state_ids))
    source_remaining = sum((node.remaining_amount for node in source_nodes), Decimal("0"))
    deployed_remaining = sum((node.remaining_amount for node in state_query.all()), Decimal("0"))
    declared_total = source_remaining + sum((flow.amount for flow in outgoing_source_flows), Decimal("0"))
    active_total = source_remaining + deployed_remaining
    return {
        "declared_total": format(declared_total, "f"),
        "active_material_total": format(active_total, "f"),
        "balanced": declared_total == active_total,
    }
