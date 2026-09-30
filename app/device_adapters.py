from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Iterable, Protocol


@dataclass(frozen=True)
class TemperatureReading:
    reading_id: str
    device_id: str
    value: Decimal
    observed_at: datetime
    unit: str = "C"


@dataclass(frozen=True)
class WeightReading:
    reading_id: str
    device_id: str
    value: Decimal
    observed_at: datetime
    unit: str = "kg"


class TemperatureAdapter(Protocol):
    def replay(self, cursor: int | None = None) -> Iterable[TemperatureReading]: ...


class WeightAdapter(Protocol):
    def replay(self, cursor: int | None = None) -> Iterable[WeightReading]: ...


class ReplayableTemperatureAdapter:
    """Deterministic in-memory adapter used by tests and device simulators.

    Adapters only observe sensors; there is deliberately no valve command API.
    """
    def __init__(self, device_id: str, readings: Iterable[tuple[int, Decimal, datetime]]):
        self.device_id = device_id
        self.readings = sorted(readings, key=lambda item: item[0])

    def replay(self, cursor: int | None = None):
        start = cursor if cursor is not None else 0
        for seq, value, observed_at in self.readings:
            if seq >= start:
                yield TemperatureReading(
                    reading_id=f"temp-{self.device_id}-{seq}", device_id=self.device_id,
                    value=value, observed_at=observed_at,
                )


class ReplayableWeightAdapter:
    def __init__(self, device_id: str, readings: Iterable[tuple[int, Decimal, datetime]]):
        self.device_id = device_id
        self.readings = sorted(readings, key=lambda item: item[0])

    def replay(self, cursor: int | None = None):
        start = cursor if cursor is not None else 0
        for seq, value, observed_at in self.readings:
            if seq >= start:
                yield WeightReading(
                    reading_id=f"weight-{self.device_id}-{seq}", device_id=self.device_id,
                    value=value, observed_at=observed_at,
                )
