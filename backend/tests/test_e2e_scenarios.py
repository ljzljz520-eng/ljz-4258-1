from __future__ import annotations

import threading
import uuid
from decimal import Decimal

from sqlalchemy import text

from .conftest import make_event, weight_reading

C1 = "66666666-6666-6666-6666-666666666661"
C2 = "66666666-6666-6666-6666-666666666662"
C1_NODE = "77777777-7777-7777-7777-777777777701"
DELTA = "aaaaaaaa-0000-0000-0000-000000000004"


def current_compartments(client, headers):
    vehicles = client.get("/api/vehicles", headers=headers).json()
    c1 = next(c for v in vehicles for c in v["compartments"] if c["id"] == C1)
    c2 = next(c for v in vehicles for c in v["compartments"] if c["id"] == C2)
    return c1, c2


def test_midway_refill_partial_unload_mix_and_material_conservation(client, operator_headers, supervisor_headers):
    refill = make_event("REFILL", {
        "lines": [{"compartment_id": C1, "quantity": 100, "batch_allocations": {DELTA: 100}}],
        "expected_seal_number": "SEAL-C1-A", "new_seal_number": "SEAL-C1-REFILL"
    }, 1)
    body = client.post("/api/sync", headers=operator_headers, json={"events": [refill]}).json()
    assert body["accepted"] == 1, body
    c1, c2 = current_compartments(client, operator_headers)
    assert c1["current_node_id"] != C1_NODE

    w1 = weight_reading(client, operator_headers, Decimal("300"))
    w2 = weight_reading(client, operator_headers, Decimal("200"))
    unload = make_event("UNLOAD", {"tank_code": "T-A", "lines": [
        {"compartment_id": C1, "source_node_id": c1["current_node_id"], "quantity": 300,
         "expected_seal_number": "SEAL-C1-REFILL", "weight_reading_id": w1},
        {"compartment_id": C2, "source_node_id": c2["current_node_id"], "quantity": 200,
         "expected_seal_number": "SEAL-C2-A", "weight_reading_id": w2}]}, 2)
    body = client.post("/api/sync", headers=operator_headers, json={"events": [unload]}).json()
    assert body["accepted"] == 1, body["results"]
    c1, c2 = current_compartments(client, operator_headers)
    assert Decimal(str(client.get(f"/api/material/nodes/{c1['current_node_id']}", headers=operator_headers).json()["quantity"])) == Decimal("800")
    assert Decimal(str(client.get(f"/api/material/nodes/{c2['current_node_id']}", headers=operator_headers).json()["quantity"])) == Decimal("600")
    assert client.get("/api/material/conservation", headers=supervisor_headers).json()["global_conservation_ok"] is True


def test_unload_before_sample_lab_correction_invalidates_downstream_release(client, operator_headers, lab_headers, supervisor_headers):
    w = weight_reading(client, operator_headers, Decimal("100"))
    c1, _ = current_compartments(client, operator_headers)
    unload = make_event("UNLOAD", {"tank_code": "T-B", "lines": [
        {"compartment_id": C1, "source_node_id": c1["current_node_id"], "quantity": 100,
         "expected_seal_number": "SEAL-C1-A", "weight_reading_id": w}]}, 1)
    assert client.post("/api/sync", headers=operator_headers, json={"events": [unload]}).json()["accepted"] == 1
    tank_b = next(t for t in client.get("/api/tanks", headers=operator_headers).json() if t["tank_code"] == "T-B")

    sample_event = make_event("SAMPLE", {"sample_code": "S-LATE-001",
        "source_node_id": tank_b["current_node_id"], "quantity": 0, "farm_batch_id": None}, 2)
    assert client.post("/api/sync", headers=operator_headers, json={"events": [sample_event]}).json()["accepted"] == 1
    sample = next(s for s in client.get("/api/samples", headers=operator_headers).json() if s["sample_code"] == "S-LATE-001")
    assert client.post("/api/releases", headers=operator_headers, json={
        "application_no": "REL-LATE-001", "tank_code": "T-B",
        "expected_node_id": tank_b["current_node_id"],
        "expected_lineage_version": tank_b["lineage_version"]}).status_code == 200

    first = client.post(f"/api/samples/{sample['id']}/results", headers=lab_headers,
                        json={"result": "ACCEPT", "components": {"fat": 3.8}})
    assert first.status_code == 200, first.text
    assert client.post(f"/api/samples/{sample['id']}/results", headers=lab_headers,
                       json={"result": "REJECT", "components": {"antibiotic": "positive"},
                             "note": "late corrected result"}).status_code == 200
    releases = {r["application_no"]: r for r in client.get("/api/releases", headers=supervisor_headers).json()}
    assert releases["REL-LATE-001"]["status"] == "INVALIDATED"
    assert client.post("/api/releases/REL-LATE-001/sign", headers=supervisor_headers).status_code == 409
    impact = client.get(f"/api/samples/{sample['id']}/impact", headers=supervisor_headers).json()
    assert any(t["tank_code"] == "T-B" for t in impact["receiving_tanks"])


def test_partial_sync_failure_deduplicates_and_quarantines_overrun(client, operator_headers):
    c1, _ = current_compartments(client, operator_headers)
    valid = make_event("UNLOAD", {"tank_code": "T-A", "lines": [
        {"compartment_id": C1, "source_node_id": c1["current_node_id"], "quantity": 10,
         "expected_seal_number": "SEAL-C1-A"}]}, 1, "offline-1")
    # The overrun event is presented first, then the valid event is presented
    # in the same batch. Quarantine must not abort the rest of the batch.
    bad = make_event("UNLOAD", {"tank_code": "T-A", "lines": [
        {"compartment_id": C1, "source_node_id": c1["current_node_id"], "quantity": 99999,
         "expected_seal_number": "SEAL-C1-A"}]}, 2, "offline-1")
    body = client.post("/api/sync", headers=operator_headers, json={"events": [bad, valid]}).json()
    assert body["accepted"] == 1
    assert body["quarantined"] == 1
    assert "overdraw" in body["results"][0]["conflict_reason"]

    valid["terminal_id"] = "offline-2"
    body = client.post("/api/sync", headers=operator_headers, json={"events": [valid]}).json()
    assert body["duplicates"] == 1 and body["accepted"] == 0
    quarantined = client.get("/api/events?type=UNLOAD&status=QUARANTINED", headers=operator_headers).json()
    assert any("overdraw" in (e["conflict_reason"] or "") for e in quarantined)


def test_concurrent_unload_and_release_signature_race(client, operator_headers, supervisor_headers, db):
    # Establish a real tank snapshot, then race a signature against another unload.
    initial_weight = weight_reading(client, operator_headers, Decimal("50"), "initial-scale")
    c1, _ = current_compartments(client, operator_headers)
    initial = make_event("UNLOAD", {"tank_code": "T-A", "lines": [
        {"compartment_id": C1, "source_node_id": c1["current_node_id"], "quantity": 50,
         "expected_seal_number": "SEAL-C1-A", "weight_reading_id": initial_weight}]}, 1, "race-seed")
    assert client.post("/api/sync", headers=operator_headers, json={"events": [initial]}).json()["accepted"] == 1
    tank_before = next(t for t in client.get("/api/tanks", headers=operator_headers).json() if t["tank_code"] == "T-A")
    app_no = f"REL-RACE-{uuid.uuid4().hex[:8]}"
    created = client.post("/api/releases", headers=operator_headers, json={
        "application_no": app_no, "tank_code": "T-A",
        "expected_node_id": tank_before["current_node_id"],
        "expected_lineage_version": tank_before["lineage_version"]})
    assert created.status_code == 200, created.text

    barrier = threading.Barrier(2)
    outcomes = []

    def unload():
        barrier.wait()
        w = weight_reading(client, operator_headers, Decimal("50"), "race-scale")
        c1_now, _ = current_compartments(client, operator_headers)
        ev = make_event("UNLOAD", {"tank_code": "T-A", "lines": [
            {"compartment_id": C1, "source_node_id": c1_now["current_node_id"], "quantity": 50,
             "expected_seal_number": "SEAL-C1-A", "weight_reading_id": w}]}, 99, "race-terminal")
        outcomes.append(("unload", client.post("/api/sync", headers=operator_headers, json={"events": [ev]}).status_code))

    def sign():
        barrier.wait()
        outcomes.append(("sign", client.post(f"/api/releases/{app_no}/sign", headers=supervisor_headers).status_code))

    t1 = threading.Thread(target=unload); t2 = threading.Thread(target=sign)
    t1.start(); t2.start(); t1.join(); t2.join()
    assert dict(outcomes)["unload"] == 200
    app = next(r for r in client.get("/api/releases", headers=supervisor_headers).json() if r["application_no"] == app_no)
    tank_after = next(t for t in client.get("/api/tanks", headers=operator_headers).json() if t["tank_code"] == "T-A")
    assert app["status"] == "INVALIDATED"
    assert app["expected_node_id"] != tank_after["current_node_id"]


def test_device_adapter_replay_is_idempotent(client, operator_headers):
    from app.adapters import FileWeightAdapter

    rows = [{
        "reading_id": "adapter-scale-001", "terminal_id": "adapter-terminal",
        "device_id": "scale-file", "vehicle_plate": "V-101", "gross_kg": "123.45",
        "recorded_at": "2026-09-30T03:00:00Z",
    }]
    readings = [r.model_dump(mode="json") for r in FileWeightAdapter(rows).readings()]
    first = client.post("/api/device-readings/replay", headers=operator_headers, json={"readings": readings})
    second = client.post("/api/device-readings/replay", headers=operator_headers, json={"readings": readings})
    assert first.status_code == 200
    assert second.json()["ingested"][0]["duplicate"] is True
    stored = client.get("/api/device-readings", headers=operator_headers).json()
    assert [x for x in stored if x["reading_id"] == "adapter-scale-001"].__len__() == 1


def test_seal_conflict_is_quarantined_without_pointer_overwrite(client, operator_headers):
    c1, _ = current_compartments(client, operator_headers)
    before_node_id = c1["current_node_id"]
    ev = make_event("UNLOAD", {"tank_code": "T-A", "lines": [
        {"compartment_id": C1, "source_node_id": c1["current_node_id"], "quantity": 5,
         "expected_seal_number": "SEAL-C2-A"}]}, 1, "terminal-seal")
    body = client.post("/api/sync", headers=operator_headers, json={"events": [ev]}).json()
    assert body["accepted"] == 0
    assert body["quarantined"] == 1
    assert "seal conflict" in body["results"][0]["conflict_reason"]
    c1_after, _ = current_compartments(client, operator_headers)
    assert c1_after["current_node_id"] == before_node_id
