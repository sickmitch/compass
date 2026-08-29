from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from math import isclose, isfinite
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from compass.detours.domain import (
    EligibleDetourCandidate,
    NetworkDetourRequest,
    NetworkDetourResult,
)

OpeningState = Literal["open", "closed", "unknown"]
OpeningHoursValidation = Literal["valid", "missing", "invalid"]
PriceFreshnessState = Literal["fresh", "stale", "future_observation", "unknown"]


@dataclass(frozen=True, slots=True)
class OpeningHoursEvaluation:
    state: OpeningState
    validation: OpeningHoursValidation
    opening_hours: str | None
    source: Literal["osm"] | None
    source_confidence: float | None
    evaluated_at: datetime
    timezone: str
    next_change_at: datetime | None = None
    comment: str | None = None
    warnings: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class CurrentCngPrice:
    unit_price: Decimal
    currency: str
    unit: str
    service_mode: Literal["self", "served"]
    observed_at: datetime
    ingested_at: datetime
    source_name: str


@dataclass(frozen=True, slots=True)
class EvaluatedCngPrice:
    unit_price: Decimal
    currency: str
    unit: str
    service_mode: Literal["self", "served"]
    observed_at: datetime
    ingested_at: datetime
    source_name: str
    age_seconds: float | None
    freshness_state: PriceFreshnessState


@dataclass(frozen=True, slots=True)
class CandidateEnrichment:
    opening_hours: str | None = None
    phone: str | None = None
    brand: str | None = None
    operator: str | None = None
    osm_match_confidence: float | None = None
    current_price: CurrentCngPrice | None = None


@dataclass(frozen=True, slots=True)
class RankingPolicy:
    detour_weight: float = 0.50
    opening_weight: float = 0.25
    price_weight: float = 0.15
    price_freshness_weight: float = 0.10
    unknown_opening_score: float = 0.25
    closed_score_multiplier: float = 0.25
    price_freshness_seconds: float = 7 * 24 * 60 * 60
    opening_hours_timezone: str = "Europe/Rome"
    opening_hours_country: str = "IT"
    price_selection: Literal["lowest_current_cng_unit_price"] = (
        "lowest_current_cng_unit_price"
    )

    def __post_init__(self) -> None:
        weights = (
            self.detour_weight,
            self.opening_weight,
            self.price_weight,
            self.price_freshness_weight,
        )
        if any(not isfinite(weight) or weight < 0 or weight > 1 for weight in weights):
            raise ValueError("ranking weights must be finite values between zero and one")
        if not isclose(sum(weights), 1.0, abs_tol=1e-9):
            raise ValueError("ranking weights must sum to one")
        if (
            not isfinite(self.unknown_opening_score)
            or self.unknown_opening_score < 0
            or self.unknown_opening_score > 1
        ):
            raise ValueError("unknown_opening_score must be between zero and one")
        if (
            not isfinite(self.closed_score_multiplier)
            or self.closed_score_multiplier < 0
            or self.closed_score_multiplier > 1
        ):
            raise ValueError("closed_score_multiplier must be between zero and one")
        if not isfinite(self.price_freshness_seconds) or self.price_freshness_seconds <= 0:
            raise ValueError("price_freshness_seconds must be greater than zero")
        try:
            ZoneInfo(self.opening_hours_timezone)
        except ZoneInfoNotFoundError as error:
            raise ValueError("opening_hours_timezone must be a valid IANA timezone") from error
        if not (
            len(self.opening_hours_country) == 2
            and self.opening_hours_country.isascii()
            and self.opening_hours_country.isalpha()
            and self.opening_hours_country.isupper()
        ):
            raise ValueError("opening_hours_country must be an ISO 3166-1 alpha-2 code")


@dataclass(frozen=True, slots=True)
class RankedCandidatesRequest:
    network_request: NetworkDetourRequest
    include_closed: bool = False


@dataclass(frozen=True, slots=True)
class RankingBreakdown:
    rank: int
    total_score: float
    detour_score: float
    opening_score: float
    price_score: float
    price_freshness_score: float
    detour_contribution: float
    opening_contribution: float
    price_contribution: float
    price_freshness_contribution: float
    availability_multiplier: float


@dataclass(frozen=True, slots=True)
class RankedCandidate:
    detour: EligibleDetourCandidate
    opening: OpeningHoursEvaluation
    phone: str | None
    brand: str | None
    operator: str | None
    osm_match_confidence: float | None
    price: EvaluatedCngPrice | None
    ranking: RankingBreakdown


@dataclass(frozen=True, slots=True)
class RankingMetrics:
    detour_eligible_candidate_count: int
    opening_open_count: int
    opening_closed_count: int
    opening_unknown_count: int
    opening_valid_count: int
    opening_missing_count: int
    opening_invalid_count: int
    excluded_closed_count: int
    price_available_count: int
    price_missing_count: int
    ranked_candidate_count: int
    enrichment_queries: int


@dataclass(frozen=True, slots=True)
class RankedCandidatesResult:
    network_result: NetworkDetourResult
    policy: RankingPolicy
    include_closed: bool
    metrics: RankingMetrics
    candidates: tuple[RankedCandidate, ...]
