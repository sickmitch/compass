from functools import lru_cache
from math import isclose
from typing import Literal
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+psycopg://compass:compass-local-only@localhost:5432/compass"
    log_level: str = "INFO"
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
    traffic_enabled: bool = False
    traffic_provider: Literal["none", "mock", "tomtom"] = "none"
    traffic_refresh_mode: Literal["periodic", "on_demand"] = "on_demand"
    traffic_refresh_seconds: float = Field(default=60, gt=0)
    traffic_route_refresh_min_interval_seconds: float = Field(default=300, gt=0)
    traffic_route_probe_spacing_km: float = Field(default=25, gt=0)
    traffic_route_max_probes: int = Field(default=16, ge=2, le=100)
    traffic_route_refresh_timeout_seconds: float = Field(default=45, gt=0)
    traffic_updater_url: str = "http://traffic-updater:8003"
    traffic_refresh_ledger_path: str = (
        "/custom_files/compass_traffic_state/route_refresh.json"
    )
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
    tomtom_traffic_api_mode: Literal["flow_segment", "intermediate_json"] = (
        "flow_segment"
    )
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
            raise ValueError(
                "cng corridor minimum radius must not exceed its maximum radius"
            )
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
    def validate_traffic_configuration(self) -> "Settings":
        if self.traffic_enabled and self.traffic_provider == "none":
            raise ValueError("traffic_provider must be mock or tomtom when traffic is enabled")
        if self.traffic_valhalla_overlay_enabled and not self.traffic_enabled:
            raise ValueError("traffic overlay cannot be enabled while traffic is disabled")
        if (
            self.traffic_valhalla_overlay_enabled
            and not self.traffic_valhalla_tileset_version
        ):
            raise ValueError(
                "traffic_valhalla_tileset_version is required when overlay is enabled"
            )
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
