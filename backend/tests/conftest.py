from __future__ import annotations

import os
import uuid
from collections.abc import Generator
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


def _database_url() -> str:
    return os.environ.get("DATABASE_URL", "postgresql+psycopg://milk:milk@localhost:5432/milk_receipt_test")


@pytest.fixture(scope="session")
def db_url():
    url = _database_url()
    os.environ["DATABASE_URL"] = url
    return url


@pytest.fixture(scope="session")
def engine(db_url):
    from app.config import get_settings
    get_settings.cache_clear()
    # Importing app.db before this point can cache the default engine. Rebind
    # the application session factory to the pytest database explicitly.
    import app.db as db_module
    eng = create_engine(db_url, future=True)
    db_module.engine = eng
    db_module.SessionLocal.configure(bind=eng)
    yield eng
    eng.dispose()


@pytest.fixture(autouse=True)
def migrated(engine):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    cfg = Config(os.path.join(root, "alembic.ini"))
    cfg.set_main_option("script_location", os.path.join(root, "alembic"))
    cfg.set_main_option("sqlalchemy.url", os.environ["DATABASE_URL"])
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")
    yield


@pytest.fixture
def client(db_url, migrated) -> Generator[TestClient, None, None]:
    from app.main import app
    with TestClient(app) as c:
        yield c


@pytest.fixture
def db(engine):
    Session = sessionmaker(bind=engine, future=True)
    session = Session()
    yield session
    session.close()


def login(client: TestClient, username: str, password: str) -> dict:
    r = client.post("/api/auth/login", data={"username": username, "password": password})
    assert r.status_code == 200, r.text
    token = r.json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def operator_headers(client): return login(client, "operator", "operator123")


@pytest.fixture
def lab_headers(client): return login(client, "lab", "lab123")


@pytest.fixture
def supervisor_headers(client): return login(client, "supervisor", "supervisor123")


def make_event(event_type: str, payload: dict, number: int, terminal="terminal-A") -> dict:
    return {
        "event_id": f"test-{event_type.lower()}-{uuid.uuid4()}-{number}",
        "terminal_id": terminal,
        "type": event_type,
        "client_created_at": datetime.now(timezone.utc).isoformat(),
        "payload": payload,
    }


@pytest.fixture
def event_factory(): return make_event


def weight_reading(client: TestClient, headers: dict, value: Decimal, terminal="terminal-A") -> str:
    reading_id = f"weight-{uuid.uuid4()}"
    r = client.post("/api/device-readings", headers=headers, json={
        "reading_id": reading_id, "terminal_id": terminal, "device_type": "WEIGHT",
        "device_id": "scale-1", "vehicle_plate": "V-101", "value": str(value),
        "unit": "kg", "recorded_at": datetime.now(timezone.utc).isoformat(),
        "raw_payload": {"adapter": "replay:file", "stable": True},
    })
    assert r.status_code == 200, r.text
    return reading_id
