from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta
from hashlib import sha256

DEFAULT_CNG_REFUEL_DWELL_SECONDS = 20 * 60


@dataclass(frozen=True, slots=True)
class NavigationTiming:
    route_id: str
    driving_duration_seconds: float
    remaining_driving_duration_seconds: float
    refueling_stop_count: int
    dwell_seconds_per_refueling_stop: int
    total_refueling_dwell_seconds: float
    total_trip_duration_seconds: float
    departure_at: datetime | None
    driving_arrival_at: datetime | None
    trip_arrival_at: datetime | None


def build_navigation_timing(
    *,
    encoded_polylines: Iterable[str],
    driving_duration_seconds: float,
    fuel_stop_ids: Iterable[str] = (),
    departure_at: datetime | None = None,
    provider: str = "valhalla",
    dwell_seconds_per_refueling_stop: int = DEFAULT_CNG_REFUEL_DWELL_SECONDS,
) -> NavigationTiming:
    """Build stable route identity and trip timing without changing routing cost.

    Valhalla remains authoritative for driving time. Refuelling dwell is a Compass
    trip-planning concern and is therefore exposed separately.
    """
    if driving_duration_seconds < 0:
        raise ValueError("driving duration must not be negative")
    if dwell_seconds_per_refueling_stop < 0:
        raise ValueError("refuelling dwell must not be negative")

    shapes = tuple(encoded_polylines)
    stops = tuple(fuel_stop_ids)
    if not shapes or any(not shape for shape in shapes):
        raise ValueError("at least one encoded route shape is required")

    identity = sha256()
    for component in (provider, *shapes, *stops):
        encoded = component.encode("utf-8")
        identity.update(len(encoded).to_bytes(4, byteorder="big"))
        identity.update(encoded)

    dwell_total = float(len(stops) * dwell_seconds_per_refueling_stop)
    total_duration = float(driving_duration_seconds + dwell_total)
    driving_arrival = (
        departure_at + timedelta(seconds=driving_duration_seconds)
        if departure_at is not None
        else None
    )
    trip_arrival = (
        departure_at + timedelta(seconds=total_duration)
        if departure_at is not None
        else None
    )
    return NavigationTiming(
        route_id=f"route_{identity.hexdigest()[:32]}",
        driving_duration_seconds=float(driving_duration_seconds),
        remaining_driving_duration_seconds=float(driving_duration_seconds),
        refueling_stop_count=len(stops),
        dwell_seconds_per_refueling_stop=dwell_seconds_per_refueling_stop,
        total_refueling_dwell_seconds=dwell_total,
        total_trip_duration_seconds=total_duration,
        departure_at=departure_at,
        driving_arrival_at=driving_arrival,
        trip_arrival_at=trip_arrival,
    )


def fuel_stop_arrival_times(
    *,
    departure_at: datetime,
    leg_durations_seconds: Iterable[float],
    stop_count: int,
    dwell_seconds_per_refueling_stop: int = DEFAULT_CNG_REFUEL_DWELL_SECONDS,
) -> tuple[datetime, ...]:
    """Return arrival time at each stop, including dwell at earlier stops."""
    if stop_count < 0:
        raise ValueError("stop count must not be negative")
    legs = tuple(leg_durations_seconds)
    if len(legs) < stop_count:
        raise ValueError("one inbound driving leg is required for every stop")

    elapsed_seconds = 0.0
    arrivals: list[datetime] = []
    for index in range(stop_count):
        elapsed_seconds += legs[index]
        arrivals.append(departure_at + timedelta(seconds=elapsed_seconds))
        elapsed_seconds += dwell_seconds_per_refueling_stop
    return tuple(arrivals)
