from datetime import datetime

import pytest

from compass.ranking.opening_hours import evaluate_opening_hours


def _evaluate(expression: str | None, eta: str):
    return evaluate_opening_hours(
        expression,
        eta=datetime.fromisoformat(eta),
        latitude=45.4642,
        longitude=9.19,
        timezone_name="Europe/Rome",
        country="IT",
        source_confidence=0.95,
    )


@pytest.mark.parametrize(
    ("eta", "expected_state"),
    [
        ("2026-08-28T10:00:00+02:00", "open"),  # Friday
        ("2026-08-28T20:00:00+02:00", "closed"),
        ("2026-08-29T10:00:00+02:00", "open"),  # Saturday
        ("2026-08-30T10:00:00+02:00", "closed"),  # Sunday
    ],
)
def test_weekday_weekend_schedule_at_eta(eta: str, expected_state: str) -> None:
    result = _evaluate("Mo-Fr 08:00-18:00; Sa 09:00-12:00; Su off", eta)

    assert result.state == expected_state
    assert result.validation == "valid"
    assert result.source == "osm"
    assert result.source_confidence == 0.95
    assert result.timezone == "Europe/Rome"


def test_spaced_weekday_holiday_selector_is_closed_on_sunday() -> None:
    expression = "Mo-Sa 06:30-12:30, 14:30-19:00; Su, PH off"

    result = _evaluate(expression, "2026-08-30T11:48:13+02:00")

    assert result.state == "closed"
    assert result.validation == "valid"
    assert result.opening_hours == expression
    assert result.next_change_at is not None
    assert result.next_change_at.isoformat() == "2026-08-31T06:30:00+02:00"


def test_day_selector_canonicalization_does_not_rewrite_comments() -> None:
    result = _evaluate(
        'Su, PH unknown "call Su, PH before visiting"',
        "2026-08-30T11:48:13+02:00",
    )

    assert result.state == "unknown"
    assert result.comment == "call Su, PH before visiting"
    assert result.opening_hours == 'Su, PH unknown "call Su, PH before visiting"'


def test_overnight_schedule_carries_into_next_day() -> None:
    result = _evaluate("Mo-Fr 22:00-06:00", "2026-08-29T01:00:00+02:00")

    assert result.state == "open"
    assert result.next_change_at is not None
    assert result.next_change_at.isoformat() == "2026-08-29T06:00:00+02:00"


def test_eta_is_converted_to_station_timezone_before_evaluation() -> None:
    result = _evaluate("Mo-Fr 09:00-11:00", "2026-08-28T08:00:00+00:00")

    assert result.state == "open"
    assert result.evaluated_at.isoformat() == "2026-08-28T10:00:00+02:00"


def test_missing_and_invalid_hours_are_explicit_unknown_states() -> None:
    missing = _evaluate(None, "2026-08-28T10:00:00+02:00")
    invalid = _evaluate("not valid OSM hours", "2026-08-28T10:00:00+02:00")

    assert (missing.state, missing.validation, missing.source) == (
        "unknown",
        "missing",
        None,
    )
    assert (invalid.state, invalid.validation, invalid.source) == (
        "unknown",
        "invalid",
        "osm",
    )


def test_valid_unknown_expression_remains_distinct_from_invalid() -> None:
    result = _evaluate("24/7 unknown", "2026-08-28T10:00:00+02:00")

    assert result.state == "unknown"
    assert result.validation == "valid"


def test_eta_requires_timezone_offset() -> None:
    with pytest.raises(ValueError, match="UTC offset"):
        _evaluate("24/7", "2026-08-28T10:00:00")
