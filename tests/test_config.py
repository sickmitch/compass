import pytest
from pydantic import ValidationError

from compass.config import Settings


def test_valhalla_url_is_normalized() -> None:
    settings = Settings(_env_file=None, valhalla_url="http://router.internal:8002/")

    assert settings.valhalla_url == "http://router.internal:8002"


@pytest.mark.parametrize("value", ["router.internal:8002", "file:///tiles", "http:///route"])
def test_valhalla_url_requires_http_host(value: str) -> None:
    with pytest.raises(ValidationError, match="valhalla_url"):
        Settings(_env_file=None, valhalla_url=value)


def test_corridor_radius_bounds_are_ordered() -> None:
    with pytest.raises(ValidationError, match="minimum radius"):
        Settings(
            _env_file=None,
            cng_corridor_minimum_radius_km=51,
            cng_corridor_maximum_radius_km=50,
        )


@pytest.mark.parametrize("value", [0, 101])
def test_matrix_batch_size_is_bounded(value: int) -> None:
    with pytest.raises(ValidationError, match="valhalla_matrix_batch_size"):
        Settings(_env_file=None, valhalla_matrix_batch_size=value)


def test_opening_hours_timezone_must_be_valid_iana_name() -> None:
    with pytest.raises(ValidationError, match="opening_hours_timezone"):
        Settings(_env_file=None, opening_hours_timezone="Europe/Not-A-Place")


def test_ranking_weights_must_sum_to_one() -> None:
    with pytest.raises(ValidationError, match="weights must sum to one"):
        Settings(_env_file=None, cng_ranking_detour_weight=0.6)


@pytest.mark.parametrize("value", [-0.1, 1.1])
def test_closed_station_multiplier_is_bounded(value: float) -> None:
    with pytest.raises(ValidationError, match="closed_score_multiplier"):
        Settings(_env_file=None, cng_ranking_closed_score_multiplier=value)


def test_phase7_freshness_threshold_defaults_are_explicit() -> None:
    settings = Settings(_env_file=None)

    assert settings.mimit_data_freshness_hours == 48
    assert settings.osm_data_freshness_hours == 168
    assert settings.reconciliation_data_freshness_hours == 48


@pytest.mark.parametrize(
    "field",
    [
        "mimit_data_freshness_hours",
        "osm_data_freshness_hours",
        "reconciliation_data_freshness_hours",
    ],
)
def test_phase7_freshness_thresholds_must_be_positive(field: str) -> None:
    with pytest.raises(ValidationError, match=field):
        Settings(_env_file=None, **{field: 0})
