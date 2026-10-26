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
    assert settings.google_places_api_key.get_secret_value() == ""
    assert settings.google_places_corroboration_radius_meters == 75
    assert settings.cng_refuel_dwell_seconds == 20 * 60


def test_google_corroboration_requires_an_api_key() -> None:
    with pytest.raises(ValidationError, match="google_places_api_key"):
        Settings(_env_file=None, geocoding_provider="nominatim_google")

    settings = Settings(
        _env_file=None,
        geocoding_provider="nominatim_google",
        google_places_api_key="test-secret",
    )
    assert settings.geocoding_provider == "nominatim_google"
    assert settings.google_places_api_key.get_secret_value() == "test-secret"


def test_api_authentication_is_disabled_by_default() -> None:
    settings = Settings(_env_file=None)

    assert settings.api_auth_enabled is False
    assert settings.api_auth_username == ""
    assert settings.api_auth_password.get_secret_value() == ""


@pytest.mark.parametrize(
    ("username", "password"),
    [("", "secret"), ("compass", "")],
)
def test_enabled_api_authentication_requires_both_credentials(
    username: str,
    password: str,
) -> None:
    with pytest.raises(ValidationError, match="api_auth_username and api_auth_password"):
        Settings(
            _env_file=None,
            api_auth_enabled=True,
            api_auth_username=username,
            api_auth_password=password,
        )


@pytest.mark.parametrize(
    ("username", "password"),
    [("mobile:user", "secret"), ("mòbile", "secret"), ("mobile", "segrèto")],
)
def test_api_authentication_rejects_credentials_outside_basic_ascii_contract(
    username: str,
    password: str,
) -> None:
    with pytest.raises(ValidationError, match="printable ASCII"):
        Settings(
            _env_file=None,
            api_auth_enabled=True,
            api_auth_username=username,
            api_auth_password=password,
        )


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
