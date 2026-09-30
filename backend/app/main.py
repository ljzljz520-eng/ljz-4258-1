from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import OAuth2PasswordRequestForm
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from . import tracing
from .config import get_settings
from .db import get_db
from .models import (DeviceReading, EventLog, EventStatus, EventType, FarmBatch,
                     LabResult, MaterialEdge, MaterialNode, ReceivingTank, ReleaseApplication,
                     ReleaseStatus, Sample, Seal, User, UserRole, Vehicle)
from .schemas import (DeviceReadingBatch, DeviceReadingIn, DeviceReadingOut, EventOut, FarmBatchOut, LabResultIn,
                      LabResultOut, ReleaseIn, ReleaseOut, SampleOut, SealOut, SyncBatch,
                      SyncEventResult, SyncResponse, TankOut, Token, UserOut, VehicleOut)
from .security import create_access_token, current_user, require_roles, verify_password
from .sync_service import ingest_device_reading, ingest_event, submit_lab_result

settings = get_settings()
app = FastAPI(title="Raw Milk Receipt Platform", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[x.strip() for x in settings.cors_origins.split(",")],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health():
    return {"status": "ok", "service": "raw-milk-receipt", "time": datetime.now(timezone.utc)}


@app.post("/api/auth/login", response_model=Token)
def login(form: OAuth2PasswordRequestForm = Depends(), db: Session = Depends(get_db)):
    user = db.scalar(select(User).where(User.username == form.username))
    if not user or not user.is_active or not verify_password(form.password, user.password_hash):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid username or password")
    return Token(access_token=create_access_token(user), role=user.role, username=user.username)


@app.get("/api/me", response_model=UserOut)
def me(user: User = Depends(current_user)):
    return user


@app.get("/api/vehicles", response_model=list[VehicleOut])
def list_vehicles(db: Session = Depends(get_db), _: User = Depends(current_user)):
    return db.scalars(select(Vehicle).options(selectinload(Vehicle.compartments)).order_by(Vehicle.plate)).all()


@app.get("/api/farm-batches", response_model=list[FarmBatchOut])
def list_farm_batches(db: Session = Depends(get_db), _: User = Depends(current_user)):
    return db.scalars(select(FarmBatch).order_by(FarmBatch.batch_code)).all()


@app.get("/api/tanks", response_model=list[TankOut])
def list_tanks(db: Session = Depends(get_db), _: User = Depends(current_user)):
    return db.scalars(select(ReceivingTank).order_by(ReceivingTank.tank_code)).all()


@app.get("/api/seals", response_model=list[SealOut])
def list_seals(compartment_id: str | None = None, db: Session = Depends(get_db),
               _: User = Depends(current_user)):
    stmt = select(Seal).order_by(Seal.seal_number)
    if compartment_id:
        stmt = stmt.where(Seal.compartment_id == uuid.UUID(compartment_id))
    return db.scalars(stmt).all()


@app.get("/api/material/nodes/{node_id}")
def get_node(node_id: str, db: Session = Depends(get_db), _: User = Depends(current_user)):
    node = db.get(MaterialNode, uuid.UUID(node_id))
    if not node:
        raise HTTPException(status_code=404, detail="node not found")
    incoming = db.scalars(select(MaterialEdge).where(MaterialEdge.destination_node_id == node.id)).all()
    outgoing = db.scalars(select(MaterialEdge).where(MaterialEdge.source_node_id == node.id)).all()
    return {
        "id": str(node.id), "type": node.type.value, "farm_batch_id": node.farm_batch_id,
        "quantity": float(node.quantity),
        "incoming": [{"source_node_id": str(e.source_node_id), "edge_kind": e.edge_kind, "quantity": float(e.quantity)} for e in incoming],
        "outgoing": [{"destination_node_id": str(e.destination_node_id), "edge_kind": e.edge_kind, "quantity": float(e.quantity)} for e in outgoing],
    }


@app.get("/api/material/conservation")
def conservation(db: Session = Depends(get_db), _: User = Depends(require_roles(UserRole.SUPERVISOR))):
    """Useful invariant endpoint for reconciliation and tests."""
    rows = db.execute(text("""
    SELECT n.id, n.type, n.quantity, COALESCE(SUM(e.quantity),0) AS outgoing
    FROM material_nodes n LEFT JOIN material_edges e ON e.source_node_id=n.id AND e.edge_kind='TRANSFER'
    GROUP BY n.id,n.type,n.quantity
    HAVING COALESCE(SUM(e.quantity),0) > n.quantity
    UNION ALL
    SELECT d.id, d.type, d.quantity, COALESCE(SUM(e.quantity),0) AS outgoing
    FROM material_nodes d
    LEFT JOIN material_edges e ON e.destination_node_id=d.id
    WHERE d.type <> 'FARM_BATCH'
    GROUP BY d.id,d.type,d.quantity
    HAVING COALESCE(SUM(e.quantity),0) <> d.quantity
    """)).mappings().all()
    return {"violations": [dict(r) for r in rows], "global_conservation_ok": len(rows) == 0}


@app.post("/api/device-readings", response_model=DeviceReadingOut)
def post_reading(reading: DeviceReadingIn, db: Session = Depends(get_db),
                 _: User = Depends(require_roles(UserRole.OPERATOR))):
    obj = ingest_device_reading(db, reading)
    db.commit()
    db.refresh(obj)
    return obj


@app.post("/api/device-readings/replay")
def replay_readings(batch: DeviceReadingBatch, db: Session = Depends(get_db),
                    _: User = Depends(require_roles(UserRole.OPERATOR))):
    ingested = []
    for reading in sorted(batch.readings, key=lambda r: (r.recorded_at, r.reading_id)):
        existing = db.scalar(select(DeviceReading).where(DeviceReading.reading_id == reading.reading_id))
        if existing:
            ingested.append({"reading_id": existing.reading_id, "duplicate": True})
            continue
        obj = ingest_device_reading(db, reading)
        db.commit()
        ingested.append({"reading_id": obj.reading_id, "duplicate": False})
    return {"ingested": ingested, "count": len(ingested)}


@app.get("/api/device-readings", response_model=list[DeviceReadingOut])
def get_readings(vehicle_plate: str | None = None, db: Session = Depends(get_db),
                 _: User = Depends(current_user)):
    stmt = select(DeviceReading).order_by(DeviceReading.recorded_at.desc())
    if vehicle_plate:
        stmt = stmt.where(DeviceReading.vehicle_plate == vehicle_plate)
    return db.scalars(stmt.limit(200)).all()


@app.post("/api/sync", response_model=SyncResponse)
def sync_events(batch: SyncBatch, db: Session = Depends(get_db),
                user: User = Depends(require_roles(UserRole.OPERATOR, UserRole.LAB))):
    results = []
    accepted = quarantined = duplicates = 0
    partial_failure = False
    for item in batch.events:
        existing = db.scalar(select(EventLog).where(EventLog.event_id == item.event_id))
        if existing:
            duplicates += 1
            results.append(SyncEventResult(event_id=item.event_id, duplicate=True,
                                           status=existing.status,
                                           conflict_reason=existing.conflict_reason))
            continue
        try:
            event = ingest_event(db, item, user)
            db.commit()
        except Exception:
            db.rollback()
            partial_failure = True
            results.append(SyncEventResult(event_id=item.event_id, duplicate=False,
                                           status=EventStatus.REJECTED,
                                           conflict_reason="server processing failed; retained by terminal for retry"))
            continue
        if event.status == EventStatus.QUARANTINED:
            quarantined += 1
        else:
            accepted += 1
        results.append(SyncEventResult(event_id=item.event_id, duplicate=False,
                                       status=event.status, conflict_reason=event.conflict_reason))
    return SyncResponse(accepted=accepted, quarantined=quarantined, duplicates=duplicates,
                        results=results, partial_failure=partial_failure)


@app.get("/api/events", response_model=list[EventOut])
def events(status: EventStatus | None = None, type: EventType | None = None,
           db: Session = Depends(get_db), _: User = Depends(current_user)):
    stmt = select(EventLog).order_by(EventLog.client_created_at.desc(), EventLog.processed_at.desc())
    if status:
        stmt = stmt.where(EventLog.status == status)
    if type:
        stmt = stmt.where(EventLog.type == type)
    return db.scalars(stmt.limit(300)).all()


@app.get("/api/samples", response_model=list[SampleOut])
def list_samples(db: Session = Depends(get_db), _: User = Depends(current_user)):
    return db.scalars(select(Sample).order_by(Sample.created_at.desc())).all()


@app.post("/api/samples/{sample_id}/results", response_model=LabResultOut)
def add_lab_result(sample_id: str, result_in: LabResultIn, db: Session = Depends(get_db),
                   analyst: User = Depends(require_roles(UserRole.LAB))):
    try:
        sample, result = submit_lab_result(db, sample_id, result_in, analyst)
        db.commit()
    except HTTPException:
        db.rollback()
        raise
    except Exception as exc:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    db.refresh(result)
    return result


@app.get("/api/samples/{sample_id}/results", response_model=list[LabResultOut])
def get_lab_results(sample_id: str, db: Session = Depends(get_db), _: User = Depends(current_user)):
    return db.scalars(select(LabResult).where(LabResult.sample_id == uuid.UUID(sample_id)).order_by(LabResult.version)).all()


@app.get("/api/samples/{sample_id}/impact")
def sample_impact(sample_id: str, db: Session = Depends(get_db),
                  _: User = Depends(require_roles(UserRole.LAB, UserRole.SUPERVISOR))):
    sample = db.get(Sample, uuid.UUID(sample_id))
    if not sample:
        raise HTTPException(status_code=404, detail="sample not found")
    return tracing.abnormal_impact(db, sample.id)


@app.post("/api/releases", response_model=ReleaseOut)
def create_release(release_in: ReleaseIn, db: Session = Depends(get_db),
                   user: User = Depends(require_roles(UserRole.OPERATOR, UserRole.SUPERVISOR))):
    tank = db.scalar(select(ReceivingTank).where(ReceivingTank.tank_code == release_in.tank_code).with_for_update())
    if not tank:
        raise HTTPException(status_code=404, detail="tank not found")
    if tank.current_node_id != release_in.expected_node_id or tank.lineage_version != release_in.expected_lineage_version:
        raise HTTPException(status_code=409, detail=f"application references stale lineage: client=node {release_in.expected_node_id} v{release_in.expected_lineage_version}, current=node {tank.current_node_id} v{tank.lineage_version}")
    app = ReleaseApplication(
        application_no=release_in.application_no, tank_id=tank.id,
        expected_node_id=tank.current_node_id, expected_lineage_version=tank.lineage_version,
        status=ReleaseStatus.PENDING, requested_by=user.id,
    )
    db.add(app)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="a pending application already exists for this tank")
    db.refresh(app)
    return app


@app.post("/api/releases/{application_no}/sign", response_model=ReleaseOut)
def sign_release(application_no: str, db: Session = Depends(get_db),
                 signer: User = Depends(require_roles(UserRole.SUPERVISOR))):
    pending_app = db.scalar(select(ReleaseApplication).where(
        ReleaseApplication.application_no == application_no))
    if not pending_app:
        raise HTTPException(status_code=404, detail="release application not found")
    # Lock the physical tank first in both sign and unload to serialize the
    # competition without deadlock; then lock the application row.
    tank = db.get(ReceivingTank, pending_app.tank_id, with_for_update=True)
    app = db.scalar(select(ReleaseApplication).where(
        ReleaseApplication.id == pending_app.id
    ).with_for_update())
    if app.status == ReleaseStatus.INVALIDATED:
        raise HTTPException(status_code=409, detail=f"application invalidated: {app.invalid_reason}")
    if app.status == ReleaseStatus.SIGNED:
        raise HTTPException(status_code=409, detail="application already signed")
    if app.expected_node_id != tank.current_node_id or app.expected_lineage_version != tank.lineage_version:
        app.status = ReleaseStatus.INVALIDATED
        app.invalid_reason = "lineage changed before human signature"
        db.commit()
        raise HTTPException(status_code=409, detail=app.invalid_reason)
    if tracing.has_rejected_ancestor_sample(db, tank.current_node_id):
        app.status = ReleaseStatus.INVALIDATED
        app.invalid_reason = "current lineage contains a rejected sample"
        db.commit()
        raise HTTPException(status_code=409, detail=app.invalid_reason)
    app.status = ReleaseStatus.SIGNED
    app.signed_by = signer.id
    app.signed_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(app)
    return app


@app.get("/api/releases", response_model=list[ReleaseOut])
def list_releases(db: Session = Depends(get_db), _: User = Depends(current_user)):
    return db.scalars(select(ReleaseApplication).order_by(ReleaseApplication.created_at.desc())).all()
