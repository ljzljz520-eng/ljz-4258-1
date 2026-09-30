from datetime import datetime, timedelta, timezone
from decimal import Decimal

from app.db import SessionLocal
from app.models import (
    Arrival, ArrivalItem, Compartment, Farm, MaterialFlow, MaterialNode,
    PastureBatch, ReceivingTank, Seal, User, Vehicle,
)
from app.security import hash_password

USERS = [
    ("receiver", "receiver", "接收员"),
    ("sampler", "sampler", "采样员"),
    ("lab", "lab", "实验室分析员"),
    ("quality", "quality", "质量放行员"),
    ("supervisor", "supervisor", "主管/协调人"),
    ("device", "device", "设备网关"),
]


def get_or_create(db, model, defaults=None, **filters):
    obj = db.query(model).filter_by(**filters).one_or_none()
    if obj:
        return obj, False
    obj = model(**filters, **(defaults or {}))
    db.add(obj)
    db.flush()
    return obj, True


def main():
    db = SessionLocal()
    now = datetime.now(timezone.utc)
    with db.begin():
        for username, password, name in USERS:
            user, _ = get_or_create(db, User, username=username,
                                    defaults={"hashed_password": hash_password(password), "role": username,
                                              "full_name": name, "is_active": True})
            user.role = username
            user.full_name = name

        farm_a, _ = get_or_create(db, Farm, code="F-A", defaults={"name": "一号牧场"})
        farm_b, _ = get_or_create(db, Farm, code="F-B", defaults={"name": "二号牧场"})
        batch_a, created_a = get_or_create(
            db, PastureBatch, code="B-A-20260930",
            defaults={"farm_id": farm_a.id, "collected_at": now - timedelta(hours=4),
                      "declared_amount": Decimal("1000.000")},
        )
        batch_b, created_b = get_or_create(
            db, PastureBatch, code="B-B-20260930",
            defaults={"farm_id": farm_b.id, "collected_at": now - timedelta(hours=5),
                      "declared_amount": Decimal("900.000")},
        )
        vehicle, _ = get_or_create(db, Vehicle, code="V-01", defaults={"plate": "蒙A·MILK01"})
        c1, _ = get_or_create(db, Compartment, vehicle_id=vehicle.id, code="C1",
                              defaults={"capacity": Decimal("600.000"), "version": 1})
        c2, _ = get_or_create(db, Compartment, vehicle_id=vehicle.id, code="C2",
                              defaults={"capacity": Decimal("600.000"), "version": 1})
        tank1, _ = get_or_create(db, ReceivingTank, code="T-1",
                                 defaults={"name": "一号接收罐", "capacity": Decimal("5000.000"), "version": 1})
        tank2, _ = get_or_create(db, ReceivingTank, code="T-2",
                                 defaults={"name": "二号接收罐", "capacity": Decimal("5000.000"), "version": 1})

        existing_arrival = db.query(Arrival).filter(Arrival.event_id == "seed-arrival-001").one_or_none()
        if not existing_arrival:
            supervisor = db.query(User).filter(User.username == "supervisor").one()
            arrival = Arrival(event_id="seed-arrival-001", vehicle_id=vehicle.id,
                              arrived_at=now - timedelta(hours=1), created_by=supervisor.id)
            db.add(arrival)
            db.flush()
            for batch, comp, amount in [(batch_a, c1, Decimal("300.000")), (batch_b, c2, Decimal("250.000"))]:
                source = MaterialNode(
                    node_type="pasture_batch", pasture_batch_id=batch.id,
                    remaining_amount=batch.declared_amount - amount, version=2,
                )
                comp_node = MaterialNode(
                    node_type="compartment", compartment_id=comp.id,
                    pasture_batch_id=batch.id, remaining_amount=amount, version=1,
                )
                db.add_all([source, comp_node])
                db.flush()
                db.add(MaterialFlow(from_node_id=source.id, to_node_id=comp_node.id,
                                    amount=amount, cause="load"))
                comp.current_node_id = comp_node.id
                item = ArrivalItem(arrival_id=arrival.id, compartment_id=comp.id,
                                   pasture_batch_id=batch.id, declared_amount=amount,
                                   initial_node_id=comp_node.id)
                db.add(item)
                db.flush()
                db.add(Seal(arrival_item_id=item.id, code=f"SEAL-{comp.code}-001",
                            registered_by=supervisor.id))

    print("Seed complete. Logins: receiver/receiver sampler/sampler lab/lab quality/quality supervisor/supervisor device/device")


if __name__ == "__main__":
    main()
