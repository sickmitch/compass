from functools import lru_cache
from urllib.parse import urlsplit

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
    valhalla_connect_timeout_seconds: float = Field(default=5, gt=0)
    valhalla_read_timeout_seconds: float = Field(default=60, gt=0)
    valhalla_route_language: str = Field(default="it-IT", pattern=r"^[A-Za-z]{2}(-[A-Za-z]{2})?$")
    cng_corridor_range_fraction: float = Field(default=0.20, gt=0, le=1)
    cng_corridor_minimum_radius_km: float = Field(default=5, gt=0)
    cng_corridor_maximum_radius_km: float = Field(default=50, gt=0)
    cng_corridor_candidate_limit: int = Field(default=200, gt=0, le=1000)
    route_geometry_max_points: int = Field(default=200_000, ge=2)
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


@lru_cache
def get_settings() -> Settings:
    return Settings()


async def get_api_settings() -> Settings:
    """Resolve cached settings without scheduling a trivial thread-pool dependency."""
    return get_settings()
