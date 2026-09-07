from functools import lru_cache
from math import isclose
from typing import Literal
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from compass.navigation.domain import DEFAULT_CNG_REFUEL_DWELL_SECONDS


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+psycopg://compass:compass-local-only@localhost:5432/compass"
    log_level: str = "INFO"
    api_auth_enabled: bool = False
    api_auth_username: str = ""
    api_auth_password: SecretStr = SecretStr("")
    http_timeout_seconds: float = Field(default=180, gt=0)
    http_user_agent: str = "compass-cng/0.1.0"
    mimit_stations_url: str = (
        "https://www.mimit.gov.it/images/exportCSV/anagrafica_impianti_attivi.csv"
    )
    mimit_prices_url: str = "https://www.mimit.gov.it/images/exportCSV/prezzo_alle_8.csv"
    mimit_max_download_bytes: int = Field(default=50 * 1024 * 1024, gt=0)
    overpass_url: str = "https://overpass-api.de/api/interpreter"
    overpass_area_iso3166_1: str = "IT"
    overpass_max_download_bytes: int = Field(default=100 * 1024 * 1024, gt=0)
    valhalla_url: str = "http://valhalla:8002"
    valhalla_tile_dir: str = "/custom_files/valhalla_tiles"
    valhalla_tile_extract: str = "/custom_files/valhalla_tiles.tar"
    valhalla_traffic_extract: str = "/custom_files/traffic.tar"
    valhalla_config_path: str = "/custom_files/valhalla.json"
    valhalla_connect_timeout_seconds: float = Field(default=5, gt=0)
    valhalla_read_timeout_seconds: float = Field(default=60, gt=0)
    valhalla_route_language: str = Field(default="it-IT", pattern=r"^[A-Za-z]{2}(-[A-Za-z]{2})?$")
    valhalla_matrix_batch_size: int = Field(default=40, gt=0, le=100)
    valhalla_speed_limits_enabled: bool = True
    destination_search_providers: str = "none"
    google_places_enabled: bool = False
    tomtom_search_enabled: bool = False
    destination_search_fallback_enabled: bool = False
    destination_search_debounce_ms: int = Field(default=300, ge=100, le=2_000)
    destination_search_min_chars: int = Field(default=3, ge=1, le=20)
    destination_search_language: str = Field(default="it", pattern=r"^[a-z]{2}$")
    destination_search_region: str = Field(default="it", pattern=r"^[a-z]{2}$")
    destination_search_included_regions: str = Field(
        default="it", pattern=r"^[a-z]{2}(,[a-z]{2})*$"
    )
    destination_search_bias_radius_meters: float = Field(default=25_000, ge=1, le=50_000)
    destination_search_timeout_seconds: float = Field(default=10, gt=0, le=60)
    destination_search_session_ttl_seconds: int = Field(default=900, ge=60, le=3_600)
    destination_search_max_concurrency: int = Field(default=8, ge=1, le=64)
    destination_search_rate_limit_per_minute: int = Field(default=60, ge=1, le=1_000)
    google_places_text_search_enabled: bool = False
    google_places_contract_regime: Literal["unverified", "eea", "non_eea"] = "unverified"
    geocoding_provider: Literal["none", "nominatim", "nominatim_google"] = "nominatim"
    nominatim_url: str = "https://nominatim.openstreetmap.org"
    google_places_url: str = "https://places.googleapis.com/v1"
    google_places_api_key: SecretStr = SecretStr("")
    google_places_region_code: str = Field(default="IT", pattern=r"^[A-Z]{2}$")
    google_places_corroboration_radius_meters: float = Field(default=75, gt=0, le=500)
    geocoding_timeout_seconds: float = Field(default=15, gt=0)
    geocoding_country_codes: str = Field(default="it", pattern=r"^[a-z]{2}(,[a-z]{2})*$")
    geocoding_result_limit: int = Field(default=8, ge=1, le=20)
    cng_refuel_dwell_seconds: int = Field(
        default=DEFAULT_CNG_REFUEL_DWELL_SECONDS,
        ge=0,
        le=24 * 60 * 60,
    )
    traffic_enabled: bool = False
    traffic_provider: Literal["none", "mock", "tomtom"] = "none"
    traffic_refresh_mode: Literal["periodic", "on_demand"] = "on_demand"
    traffic_refresh_seconds: float = Field(default=60, gt=0)
    traffic_route_refresh_min_interval_seconds: float = Field(default=300, gt=0)
    traffic_route_probe_spacing_km: float = Field(default=25, gt=0)
    traffic_route_max_probes: int = Field(default=16, ge=2, le=100)
    traffic_route_refresh_timeout_seconds: float = Field(default=45, gt=0)
    traffic_updater_url: str = "http://traffic-updater:8003"
    traffic_refresh_ledger_path: str = "/custom_files/compass_traffic_state/route_refresh.json"
    traffic_expiry_sweep_seconds: float = Field(default=30, gt=0)
    traffic_update_segment_limit: int = Field(default=1000, gt=0, le=10000)
    traffic_max_age_seconds: float = Field(default=300, gt=0)
    traffic_min_confidence: float = Field(default=0.5, ge=0, le=1)
    traffic_min_match_confidence: float = Field(default=0.75, ge=0, le=1)
    traffic_match_search_radius_meters: float = Field(default=75, gt=0)
    traffic_match_gps_accuracy_meters: float = Field(default=15, gt=0)
    traffic_openlr_decoder_path: str = ""
    traffic_openlr_decoder_timeout_seconds: float = Field(default=2, gt=0)
    traffic_openlr_endpoint_tolerance_meters: float = Field(default=300, gt=0)
    traffic_writer_timeout_seconds: float = Field(default=60, gt=0)
    traffic_max_speed_kph: float = Field(default=180, gt=0)
    traffic_valhalla_overlay_enabled: bool = False
    traffic_valhalla_tileset_version: str = ""
    traffic_mapping_version: str = "unbuilt"
    traffic_state_path: str = "/custom_files/compass_traffic_state/state.json"
    traffic_health_path: str = "/custom_files/compass_traffic_state/health.json"
    traffic_mock_fixture_path: str = ""
    tomtom_traffic_api_mode: Literal["flow_segment", "intermediate_json"] = "flow_segment"
    tomtom_traffic_url: str = ""
    tomtom_flow_segment_points: str = ""
    tomtom_flow_segment_style: Literal[
        "absolute",
        "relative",
        "relative0",
        "relative0-dark",
        "relative-delay",
        "reduced-sensitivity",
    ] = "absolute"
    tomtom_flow_segment_zoom: int = Field(default=10, ge=0, le=22)
    tomtom_flow_segment_unit: Literal["kmph", "mph"] = "kmph"
    tomtom_flow_segment_openlr: bool = True
    tomtom_api_key: str = ""
    tomtom_timeout_seconds: float = Field(default=20, gt=0)
    tomtom_max_retries: int = Field(default=3, ge=0, le=8)
    tomtom_max_concurrency: int = Field(default=2, ge=1, le=16)
    tomtom_backoff_base_seconds: float = Field(default=0.5, gt=0)
    cng_corridor_range_fraction: float = Field(default=0.20, gt=0, le=1)
    cng_corridor_minimum_radius_km: float = Field(default=5, gt=0)
    cng_corridor_maximum_radius_km: float = Field(default=50, gt=0)
    cng_corridor_candidate_limit: int = Field(default=200, gt=0, le=1000)
    route_geometry_max_points: int = Field(default=200_000, ge=2)
    opening_hours_timezone: str = "Europe/Rome"
    cng_ranking_detour_weight: float = Field(default=0.50, ge=0, le=1)
    cng_ranking_opening_weight: float = Field(default=0.25, ge=0, le=1)
    cng_ranking_price_weight: float = Field(default=0.15, ge=0, le=1)
    cng_ranking_price_freshness_weight: float = Field(default=0.10, ge=0, le=1)
    cng_ranking_unknown_opening_score: float = Field(default=0.25, ge=0, le=1)
    cng_ranking_closed_score_multiplier: float = Field(default=0.25, ge=0, le=1)
    cng_price_freshness_hours: float = Field(default=168, gt=0)
    mimit_data_freshness_hours: float = Field(default=48, gt=0)
    osm_data_freshness_hours: float = Field(default=168, gt=0)
    reconciliation_data_freshness_hours: float = Field(default=48, gt=0)
    reconciliation_max_distance_meters: float = Field(default=250, gt=0)
    reconciliation_auto_match_distance_meters: float = Field(default=50, gt=0)
    reconciliation_named_match_distance_meters: float = Field(default=150, gt=0)
    reconciliation_name_similarity_threshold: float = Field(default=0.75, ge=0, le=1)
    reconciliation_ambiguity_score_margin: float = Field(default=0.08, ge=0, le=1)

    @field_validator("valhalla_url")
    @classmethod
    def validate_valhalla_url(cls, value: str) -> str:
        normalized = value.rstrip("/")
        parsed = urlsplit(normalized)
        if parsed.scheme not in {"http", "https"}:
            raise ValueError("valhalla_url must use http or https")
        if not parsed.hostname:
            raise ValueError("valhalla_url must include a host")
        return normalized

    @field_validator("api_auth_username")
    @classmethod
    def normalize_api_auth_username(cls, value: str) -> str:
        return value.strip()

    @field_validator("tomtom_traffic_url")
    @classmethod
    def validate_optional_tomtom_url(cls, value: str) -> str:
        if not value:
            return value
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"}:
            raise ValueError("tomtom_traffic_url must use http or https")
        if not parsed.hostname:
            raise ValueError("tomtom_traffic_url must include a host")
        return value

    @field_validator("nominatim_url")
    @classmethod
    def validate_nominatim_url(cls, value: str) -> str:
        normalized = value.rstrip("/")
        parsed = urlsplit(normalized)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("nominatim_url must use http or https and include a host")
        return normalized

    @field_validator("google_places_url")
    @classmethod
    def validate_google_places_url(cls, value: str) -> str:
        normalized = value.rstrip("/")
        parsed = urlsplit(normalized)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("google_places_url must use https and include a host")
        return normalized

    @field_validator("traffic_updater_url")
    @classmethod
    def validate_traffic_updater_url(cls, value: str) -> str:
        normalized = value.rstrip("/")
        parsed = urlsplit(normalized)
        if parsed.scheme not in {"http", "https"}:
            raise ValueError("traffic_updater_url must use http or https")
        if not parsed.hostname:
            raise ValueError("traffic_updater_url must include a host")
        return normalized

    @field_validator("opening_hours_timezone")
    @classmethod
    def validate_opening_hours_timezone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as error:
            raise ValueError("opening_hours_timezone must be a valid IANA timezone") from error
        return value

    @model_validator(mode="after")
    def validate_reconciliation_distances(self) -> "Settings":
        if not (
            self.reconciliation_auto_match_distance_meters
            <= self.reconciliation_named_match_distance_meters
            <= self.reconciliation_max_distance_meters
        ):
            raise ValueError(
                "reconciliation distances must satisfy auto-match <= named-match <= maximum"
            )
        return self

    @model_validator(mode="after")
    def validate_corridor_radii(self) -> "Settings":
        if self.cng_corridor_minimum_radius_km > self.cng_corridor_maximum_radius_km:
            raise ValueError("cng corridor minimum radius must not exceed its maximum radius")
        return self

    @model_validator(mode="after")
    def validate_ranking_weights(self) -> "Settings":
        total = (
            self.cng_ranking_detour_weight
            + self.cng_ranking_opening_weight
            + self.cng_ranking_price_weight
            + self.cng_ranking_price_freshness_weight
        )
        if not isclose(total, 1.0, abs_tol=1e-9):
            raise ValueError("CNG ranking weights must sum to one")
        return self

    @model_validator(mode="after")
    def validate_api_authentication(self) -> "Settings":
        if self.api_auth_enabled and (
            not self.api_auth_username.strip() or not self.api_auth_password.get_secret_value()
        ):
            raise ValueError(
                "api_auth_username and api_auth_password are required when API auth is enabled"
            )
        if self.api_auth_enabled:
            password = self.api_auth_password.get_secret_value()
            if (
                ":" in self.api_auth_username
                or not self.api_auth_username.isascii()
                or any(
                    ord(character) < 32 or ord(character) > 126
                    for character in self.api_auth_username
                )
                or not password.isascii()
                or any(ord(character) < 32 or ord(character) > 126 for character in password)
            ):
                raise ValueError(
                    "API auth credentials must use printable ASCII and username cannot contain ':'"
                )
        return self

    @model_validator(mode="after")
    def validate_google_places_configuration(self) -> "Settings":
        active_destination_providers = tuple(
            item.strip()
            for item in self.destination_search_providers.split(",")
            if item.strip() and item.strip() != "none"
        )
        if active_destination_providers not in {(), ("google_places_new",)}:
            raise ValueError(
                "destination_search_providers must be none or google_places_new during testing"
            )
        if self.google_places_enabled != (active_destination_providers == ("google_places_new",)):
            raise ValueError(
                "google_places_enabled must match the google_places_new provider registry"
            )
        if active_destination_providers and self.geocoding_provider != "none":
            raise ValueError(
                "geocoding_provider must be none while Google-only destination search is active"
            )
        if self.tomtom_search_enabled or self.destination_search_fallback_enabled:
            raise ValueError("TomTom destination search and fallback must remain disabled")
        if self.google_places_text_search_enabled:
            raise ValueError("Google Text Search must remain disabled in this increment")
        if self.google_places_enabled and self.google_places_contract_regime != "eea":
            raise ValueError(
                "google_places_contract_regime must be eea after billing-account verification; "
                "non_eea integration requires a separate terms review before enabling"
            )
        if (
            self.google_places_enabled or self.geocoding_provider == "nominatim_google"
        ) and not self.google_places_api_key.get_secret_value():
            raise ValueError(
                "google_places_api_key is required when Google destination search is enabled"
            )
        return self

    @model_validator(mode="after")
    def validate_traffic_configuration(self) -> "Settings":
        if self.traffic_enabled and self.traffic_provider == "none":
            raise ValueError("traffic_provider must be mock or tomtom when traffic is enabled")
        if self.traffic_valhalla_overlay_enabled and not self.traffic_enabled:
            raise ValueError("traffic overlay cannot be enabled while traffic is disabled")
        if self.traffic_valhalla_overlay_enabled and not self.traffic_valhalla_tileset_version:
            raise ValueError("traffic_valhalla_tileset_version is required when overlay is enabled")
        if (
            self.traffic_enabled
            and self.traffic_provider == "tomtom"
            and self.tomtom_traffic_api_mode == "flow_segment"
            and self.traffic_refresh_mode == "periodic"
            and not self.tomtom_flow_segment_points.strip()
        ):
            raise ValueError(
                "periodic TomTom Flow Segment mode requires TOMTOM_FLOW_SEGMENT_POINTS"
            )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()


async def get_api_settings() -> Settings:
    """Resolve cached settings without scheduling a trivial thread-pool dependency."""
    return get_settings()
