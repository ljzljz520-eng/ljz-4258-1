"""Replayable device adapter contracts.

Adapters are observation-only: they normalize historical temperature and scale
records into the same DeviceReading shape. They must never command valves,
pumps, or any field actuator.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Iterable, Iterator

from .schemas import DeviceReadingIn


class DeviceAdapter(ABC):
    device_type: str

    @abstractmethod
    def replay(self) -> Iterable[dict]:
        """Yield raw records in chronological/replay-position order."""

    def normalize(self, raw: dict, position: int) -> DeviceReadingIn:
        raise NotImplementedError

    def readings(self) -> Iterator[DeviceReadingIn]:
        for position, raw in enumerate(self.replay()):
            yield self.normalize(raw, position)


class FileTemperatureAdapter(DeviceAdapter):
    device_type = "TEMPERATURE"

    def __init__(self, rows: Iterable[dict]):
        self.rows = list(rows)

    def replay(self):
        return sorted(self.rows, key=lambda r: (r["recorded_at"], r.get("reading_id", "")))

    def normalize(self, raw: dict, position: int) -> DeviceReadingIn:
        return DeviceReadingIn(
            reading_id=raw["reading_id"], terminal_id=raw["terminal_id"],
            device_type="TEMPERATURE", device_id=raw["device_id"],
            vehicle_plate=raw.get("vehicle_plate"), value=raw["value_c"], unit="C",
            recorded_at=raw["recorded_at"], replay_position=position,
            raw_payload=raw.get("raw_payload", {"adapter": self.__class__.__name__}),
        )


class FileWeightAdapter(DeviceAdapter):
    device_type = "WEIGHT"

    def __init__(self, rows: Iterable[dict]):
        self.rows = list(rows)

    def replay(self):
        return sorted(self.rows, key=lambda r: (r["recorded_at"], r.get("reading_id", "")))

    def normalize(self, raw: dict, position: int) -> DeviceReadingIn:
        return DeviceReadingIn(
            reading_id=raw["reading_id"], terminal_id=raw["terminal_id"],
            device_type="WEIGHT", device_id=raw["device_id"],
            vehicle_plate=raw.get("vehicle_plate"), value=raw["gross_kg"], unit="kg",
            recorded_at=raw["recorded_at"], replay_position=position,
            raw_payload=raw.get("raw_payload", {"adapter": self.__class__.__name__}),
        )
