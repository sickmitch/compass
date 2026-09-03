import pytest
from pydantic import ValidationError

from compass.config import Settings


def test_valhalla_url_is_normalized() -> None:
    settings = Settings(_env_file=None, valhalla_url="http://router.internal:8002/")

    assert settings.valhalla_url == "http://router.internal:8002"


def test_phase12_search_and_refuelling_defaults_are_explicit() -> None:
    settings = Settings(_env_file=None)

    assert settings.geocoding_provider == "nominatim"
    assert settings.geocoding_country_codes == "it"
    assert settings.geocoding_result_limit == 8
    assert settings.cng_refuel_dwell_seconds == 20 * 60


def test_refuelling_dwell_is_configurable() -> None:
    settings = Settings(_env_file=None, cng_refuel_dwell_seconds=900)

    assert settings.cng_refuel_dwell_seconds == 900


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


def test_traffic_is_disabled_by_default() -> None:
    settings = Settings(_env_file=None)

    assert settings.traffic_enabled is False
    assert settings.traffic_provider == "none"
    assert settings.traffic_valhalla_overlay_enabled is False


def test_traffic_provider_can_be_configured_while_disabled() -> None:
    settings = Settings(_env_file=None, traffic_provider="mock")

    assert settings.traffic_enabled is False
    assert settings.traffic_provider == "mock"


def test_enabled_traffic_requires_provider() -> None:
    with pytest.raises(ValidationError, match="traffic_provider"):
        Settings(_env_file=None, traffic_enabled=True)


def test_tomtom_feed_credentials_are_not_required_by_overlay_consumers() -> None:
    settings = Settings(
        _env_file=None,
        traffic_enabled=True,
        traffic_provider="tomtom",
        traffic_valhalla_overlay_enabled=True,
        traffic_valhalla_tileset_version="tileset-test",
    )

    assert settings.tomtom_api_key == ""
    assert settings.tomtom_flow_segment_points == ""


def test_tomtom_flow_segment_mode_requires_api_key_and_points() -> None:
    settings = Settings(
        _env_file=None,
        traffic_enabled=True,
        traffic_provider="tomtom",
        tomtom_api_key="test-key",
        tomtom_flow_segment_points="45.4642,9.19;44.4949,11.3426",
    )

    assert settings.tomtom_traffic_api_mode == "flow_segment"
    assert settings.tomtom_flow_segment_openlr is True
    assert settings.traffic_openlr_decoder_path == ""
    assert settings.traffic_openlr_decoder_timeout_seconds == 2
    assert settings.traffic_openlr_endpoint_tolerance_meters == 300
    assert settings.traffic_writer_timeout_seconds == 60
    assert settings.traffic_update_segment_limit == 1000
    assert settings.traffic_state_path == "/custom_files/compass_traffic_state/state.json"
    assert settings.traffic_health_path == "/custom_files/compass_traffic_state/health.json"


@pytest.mark.parametrize("value", [0, 10001])
def test_traffic_update_segment_limit_is_bounded(value: int) -> None:
    with pytest.raises(ValidationError, match="traffic_update_segment_limit"):
        Settings(_env_file=None, traffic_update_segment_limit=value)


def test_traffic_overlay_requires_tileset_version() -> None:
    with pytest.raises(ValidationError, match="tileset_version"):
        Settings(
            _env_file=None,
            traffic_enabled=True,
            traffic_provider="mock",
            traffic_valhalla_overlay_enabled=True,
        )
