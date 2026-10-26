from datetime import datetime

import pytest

from compass.navigation.domain import (
    DEFAULT_CNG_REFUEL_DWELL_SECONDS,
    build_navigation_timing,
    fuel_stop_arrival_times,
)


def test_navigation_timing_keeps_driving_and_refuelling_costs_separate() -> None:
    departure = datetime.fromisoformat("2026-09-02T08:00:00+02:00")

    timing = build_navigation_timing(
        encoded_polylines=("leg-one", "leg-two", "leg-three"),
        driving_duration_seconds=7_200,
        fuel_stop_ids=("3618", "46660"),
        departure_at=departure,
    )

    assert timing.driving_duration_seconds == 7_200
    assert timing.total_refueling_dwell_seconds == 2_400
    assert timing.total_trip_duration_seconds == 9_600
    assert timing.driving_arrival_at == datetime.fromisoformat(
        "2026-09-02T10:00:00+02:00"
    )
    assert timing.trip_arrival_at == datetime.fromisoformat(
        "2026-09-02T10:40:00+02:00"
    )


def test_route_identity_changes_with_shape_or_fuel_plan() -> None:
    base = build_navigation_timing(
        encoded_polylines=("shape",), driving_duration_seconds=100
    )
    same = build_navigation_timing(
        encoded_polylines=("shape",), driving_duration_seconds=999
    )
    changed_stop = build_navigation_timing(
        encoded_polylines=("shape",),
        driving_duration_seconds=100,
        fuel_stop_ids=("3618",),
    )

    assert base.route_id == same.route_id
    assert changed_stop.route_id != base.route_id


def test_fuel_stop_arrivals_include_only_prior_dwell() -> None:
    departure = datetime.fromisoformat("2026-09-02T08:00:00+02:00")

    arrivals = fuel_stop_arrival_times(
        departure_at=departure,
        leg_durations_seconds=(600, 900, 1200),
        stop_count=2,
    )

    assert arrivals == (
        datetime.fromisoformat("2026-09-02T08:10:00+02:00"),
        datetime.fromisoformat("2026-09-02T08:45:00+02:00"),
    )
    assert DEFAULT_CNG_REFUEL_DWELL_SECONDS == 1_200


def test_navigation_timing_rejects_missing_geometry() -> None:
    with pytest.raises(ValueError, match="encoded route shape"):
        build_navigation_timing(encoded_polylines=(), driving_duration_seconds=10)
