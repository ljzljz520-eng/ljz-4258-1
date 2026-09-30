from __future__ import annotations

import json
from typing import Any

from fastapi import Depends, FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from sqlalchemy.orm import Session

from app import schemas, services
from app.db import Base, engine, get_db
from app.models import MaterialNode, ReconciliationIssue, User
from app.security import (
    create_access_token, current_user, hash_password, require_roles, verify_password,
)

Base.metadata.create_all(bind=engine)
app = FastAPI(title="Raw Milk Receiving Platform", version="1.0.0")


@app.exception_handler(services.EventRejected)
async def rejected_handler(_, exc: services.EventRejected):
    return JSONResponse(status_code=exc.status_code, content=exc.detail)


@app.post("/auth/token", response_model=schemas.Token)
def token(form: dict[str, str], db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == form.get("username")).one_or_none()
    if not user or not verify_password(form.get("password", ""), user.hashed_password):
        raise HTTPException(401, "Incorrect username or password")
    return {"access_token": create_access_token(user.username, user.role)}


@app.get("/me")
def me(user: User = Depends(current_user)):
    return {"username": user.username, "role": user.role, "full_name": user.full_name}


@app.post("/arrivals")
def arrivals(payload: schemas.ArrivalCreate, db: Session = Depends(get_db),
             user: User = Depends(require_roles("receiver", "supervisor"))):
    return services.create_arrival(db, payload.model_dump(mode="json"), user.id)


@app.post("/unloads")
def unloads(payload: schemas.UnloadCreate, db: Session = Depends(get_db),
            user: User = Depends(require_roles("receiver", "supervisor"))):
    return services.create_unload(db, payload.model_dump(mode="json"), user.id)


@app.post("/topups")
def topups(payload: schemas.TopUpCreate, db: Session = Depends(get_db),
           user: User = Depends(require_roles("receiver", "supervisor"))):
    return services.create_topup(db, payload.model_dump(mode="json"), user.id)


@app.post("/sync")
def sync_batch(payload: dict[str, Any], db: Session = Depends(get_db),
               user: User = Depends(require_roles("receiver", "supervisor"))):
    raw_events = payload.get("events", [])
    if not isinstance(raw_events, list):
        raise HTTPException(422, "events must be a list")
    results = []
    accepted = failed = quarantined = 0
    for raw in raw_events:
        kind = raw.get("type") if isinstance(raw, dict) else None
        event_id = raw.get("event_id") if isinstance(raw, dict) else None
        try:
            if kind == "arrival":
                parsed = schemas.ArrivalCreate.model_validate(raw)
                response = services.create_arrival(db, parsed.model_dump(mode="json"), user.id)
            elif kind == "unload":
                parsed = schemas.UnloadCreate.model_validate(raw)
                response = services.create_unload(db, parsed.model_dump(mode="json"), user.id)
            elif kind == "topup":
                parsed = schemas.TopUpCreate.model_validate(raw)
                response = services.create_topup(db, parsed.model_dump(mode="json"), user.id)
            else:
                raise HTTPException(422, "event type must be arrival, unload, or topup")
            results.append({"event_id": event_id, "status": "accepted", "data": response})
            accepted += 1
        except HTTPException as exc:
            status = "quarantined" if exc.status_code == 409 else "failed"
            if status == "quarantined":
                quarantined += 1
            else:
                failed += 1
            detail = exc.detail
            results.append({"event_id": event_id, "status": status, "detail": detail})
    return JSONResponse(
        status_code=207 if failed or quarantined else 200,
        content={"accepted": accepted, "failed": failed, "quarantined": quarantined, "results": results},
    )


@app.post("/devices/readings")
def device_reading(payload: schemas.DeviceReadingIn, db: Session = Depends(get_db),
                   _: User = Depends(require_roles("device", "receiver", "supervisor"))):
    return services.record_device_reading(db, payload.model_dump(mode="json"))


@app.post("/samples")
def create_sample(payload: schemas.SampleCreate, db: Session = Depends(get_db),
                  user: User = Depends(require_roles("sampler", "supervisor"))):
    return services.create_sample(db, payload.model_dump(mode="json"), user.id)


@app.post("/samples/handover")
def sample_handover(payload: schemas.HandoverUpdate, db: Session = Depends(get_db),
                    user: User = Depends(require_roles("sampler", "lab", "supervisor"))):
    return services.handover_sample(db, payload.sample_code, payload.phase, user.id)


@app.get("/samples/{sample_code}/impact")
def sample_impact(sample_code: str, db: Session = Depends(get_db),
                  user: User = Depends(require_roles("lab", "quality", "supervisor"))):
    return services.sample_impact(db, sample_code)


@app.post("/lab/results")
def lab_result(payload: schemas.SampleResultIn, db: Session = Depends(get_db),
               user: User = Depends(require_roles("lab", "supervisor"))):
    return services.record_result(db, payload.model_dump(mode="json"), user.id)


@app.post("/receiving-decisions")
def receiving_decision(payload: schemas.DecisionIn, db: Session = Depends(get_db),
                       user: User = Depends(require_roles("supervisor"))):
    return services.create_decision(db, payload.model_dump(mode="json"), user.id)


@app.post("/releases")
def create_release(payload: schemas.ReleaseCreate, db: Session = Depends(get_db),
                   user: User = Depends(require_roles("quality", "supervisor"))):
    return services.create_release(db, payload.model_dump(mode="json"), user.id)


@app.post("/releases/decide")
def decide_release(payload: schemas.ReleaseDecisionIn, db: Session = Depends(get_db),
                   user: User = Depends(require_roles("supervisor"))):
    return services.decide_release(db, payload.model_dump(mode="json"), user.id)


@app.get("/lineage/nodes/{node_id}/version")
def node_lineage_version(node_id: int, db: Session = Depends(get_db),
                         user: User = Depends(require_roles("quality", "supervisor"))):
    node = db.get(MaterialNode, node_id)
    if not node:
        raise HTTPException(404, "Node not found")
    return {"node_id": node_id, "lineage_version": services.lineage_version(db, node_id)}


@app.get("/reconciliation/issues")
def list_issues(db: Session = Depends(get_db),
                user: User = Depends(require_roles("supervisor"))):
    issues = db.query(ReconciliationIssue).order_by(ReconciliationIssue.created_at.desc()).all()
    return [
        {"issue_key": i.issue_key, "type": i.issue_type, "event_id": i.event_id,
         "status": i.status, "details": i.details, "resolution": i.resolution}
        for i in issues
    ]


@app.post("/reconciliation/issues/resolve")
def resolve_issue(payload: schemas.IssueResolve, db: Session = Depends(get_db),
                  user: User = Depends(require_roles("supervisor"))):
    return services.resolve_issue(db, payload.issue_key, payload.resolution, user.id)


@app.get("/conservation")
def conservation(db: Session = Depends(get_db),
                 user: User = Depends(require_roles("quality", "supervisor"))):
    return services.conservation_summary(db)


@app.get("/healthz")
def healthz():
    return {"ok": True, "valve_control": "not_implemented_by_design"}
