import asyncio
import logging
import math
import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher

from compass.search.domain import PlaceSearchProvider, PlaceSearchRequest, PlaceSearchResult

_WORD_PATTERN = re.compile(r"[a-z0-9]+")
_HOUSE_NUMBER_PATTERN = re.compile(
    r"(?<![a-z0-9])(\d{1,5}(?:\s*(?:[/\-]\s*)?(?:bis|ter|quater|rosso|[a-z]))?)"
    r"(?![a-z0-9])",
    re.IGNORECASE,
)
logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class _ScoredResult:
    result: PlaceSearchResult
    score: float
    original_index: int
    house_match: str


class CorroboratedPlaceSearchProvider:
    """Ranks Nominatim content using ephemeral Google Places corroboration.

    Google text, coordinates and identifiers never cross this provider boundary. The returned
    records remain Nominatim records, and callers must not persist the Google-influenced ordering.
    """

    provider_name = "nominatim_google_corroborated"
    cacheable = False

    def __init__(
        self,
        *,
        primary: PlaceSearchProvider,
        corroborator: PlaceSearchProvider,
        corroboration_radius_meters: float,
    ) -> None:
        if corroboration_radius_meters <= 0:
            raise ValueError("corroboration radius must be positive")
        self._primary = primary
        self._corroborator = corroborator
        self._corroboration_radius_meters = corroboration_radius_meters

    async def search(self, request: PlaceSearchRequest) -> tuple[PlaceSearchResult, ...]:
        expanded = PlaceSearchRequest(
            query=request.query,
            limit=min(20, max(12, request.limit * 2)),
            language=request.language,
        )
        primary_result, corroborating_result = await asyncio.gather(
            self._primary.search(expanded),
            self._corroborator.search(expanded),
            return_exceptions=True,
        )
        if isinstance(primary_result, BaseException):
            raise primary_result
        if isinstance(corroborating_result, BaseException):
            logger.warning(
                "Place corroborator unavailable; using primary search results",
                extra={
                    "provider": "google_places_new",
                    "error_type": type(corroborating_result).__name__,
                },
            )
            corroborating = ()
        else:
            corroborating = corroborating_result
            logger.info(
                "Place corroboration completed",
                extra={
                    "provider": "google_places_new",
                    "primary_result_count": len(primary_result),
                    "corroborating_result_count": len(corroborating),
                },
            )
        return rank_results_for_query(
            request.query,
            primary_result,
            corroborating,
            limit=request.limit,
            corroboration_radius_meters=self._corroboration_radius_meters,
        )


def rank_results_for_query(
    query: str,
    primary_results: tuple[PlaceSearchResult, ...],
    corroborating_results: tuple[PlaceSearchResult, ...] = (),
    *,
    limit: int,
    corroboration_radius_meters: float,
) -> tuple[PlaceSearchResult, ...]:
    if limit <= 0:
        raise ValueError("result limit must be positive")
    query_house_number = extract_query_house_number(query)
    scored = [
        _score_result(
            query=query,
            query_house_number=query_house_number,
            result=result,
            corroborating_results=corroborating_results,
            corroboration_radius_meters=corroboration_radius_meters,
            original_index=index,
        )
        for index, result in enumerate(primary_results)
    ]
    if query_house_number is not None:
        scored = [item for item in scored if item.house_match != "conflict"]
    scored.sort(key=lambda item: (-item.score, item.original_index, item.result.result_id))
    return tuple(item.result for item in scored[:limit])


def extract_query_house_number(query: str) -> str | None:
    normalized_segments = [_normalize_text(segment) for segment in query.split(",")]
    if not normalized_segments or "via" not in _WORD_PATTERN.findall(normalized_segments[0]):
        return None
    for segment in normalized_segments[1:]:
        match = _HOUSE_NUMBER_PATTERN.fullmatch(segment)
        if match is None:
            continue
        candidate = _normalize_house_number(match.group(1))
        if not (candidate.isdigit() and len(candidate) == 5):
            return candidate
    return None


def _score_result(
    *,
    query: str,
    query_house_number: str | None,
    result: PlaceSearchResult,
    corroborating_results: tuple[PlaceSearchResult, ...],
    corroboration_radius_meters: float,
    original_index: int,
) -> _ScoredResult:
    candidate_text = " ".join(
        part for part in (result.poi_name, result.display_name, result.address) if part is not None
    )
    query_normalized = _normalize_text(query)
    candidate_normalized = _normalize_text(candidate_text)
    query_tokens = set(_WORD_PATTERN.findall(query_normalized))
    candidate_tokens = set(_WORD_PATTERN.findall(candidate_normalized))
    token_coverage = (
        len(query_tokens & candidate_tokens) / len(query_tokens) if query_tokens else 0.0
    )
    sequence_similarity = SequenceMatcher(
        None, query_normalized, candidate_normalized, autojunk=False
    ).ratio()
    score = 0.62 * token_coverage + 0.23 * sequence_similarity

    house_match = _house_match(query_house_number, result.house_number)
    if house_match == "exact":
        score += 0.32
        if result.kind == "address":
            score += 0.08
    elif house_match == "missing":
        score -= 0.15
    elif house_match == "conflict":
        score -= 0.70

    if _is_corroborated(
        query_house_number=query_house_number,
        primary=result,
        candidates=corroborating_results,
        radius_meters=corroboration_radius_meters,
    ):
        score += 0.28
    return _ScoredResult(result, score, original_index, house_match)


def _is_corroborated(
    *,
    query_house_number: str | None,
    primary: PlaceSearchResult,
    candidates: tuple[PlaceSearchResult, ...],
    radius_meters: float,
) -> bool:
    for candidate in candidates:
        if _distance_meters(primary, candidate) > radius_meters:
            continue
        if query_house_number is not None:
            if _house_match(query_house_number, primary.house_number) != "exact":
                continue
            if _house_match(query_house_number, candidate.house_number) != "exact":
                continue
            if _text_similarity(primary.street_name, candidate.street_name) < 0.70:
                continue
            if (
                primary.locality is not None
                and candidate.locality is not None
                and _text_similarity(primary.locality, candidate.locality) < 0.70
            ):
                continue
            return True

        primary_name = primary.poi_name or primary.display_name
        candidate_name = candidate.poi_name or candidate.display_name
        if _text_similarity(primary_name, candidate_name) >= 0.72:
            return True
    return False


def _house_match(query_house_number: str | None, result_house_number: str | None) -> str:
    if query_house_number is None:
        return "not_requested"
    if result_house_number is None:
        return "missing"
    if _normalize_house_number(result_house_number) == query_house_number:
        return "exact"
    return "conflict"


def _text_similarity(left: str | None, right: str | None) -> float:
    if left is None or right is None:
        return 0.0
    left_tokens = set(_WORD_PATTERN.findall(_normalize_text(left)))
    right_tokens = set(_WORD_PATTERN.findall(_normalize_text(right)))
    if not left_tokens or not right_tokens:
        return 0.0
    return len(left_tokens & right_tokens) / len(left_tokens | right_tokens)


def _distance_meters(left: PlaceSearchResult, right: PlaceSearchResult) -> float:
    latitude_1 = math.radians(left.coordinate.latitude)
    latitude_2 = math.radians(right.coordinate.latitude)
    delta_latitude = latitude_2 - latitude_1
    delta_longitude = math.radians(right.coordinate.longitude - left.coordinate.longitude)
    haversine = (
        math.sin(delta_latitude / 2) ** 2
        + math.cos(latitude_1) * math.cos(latitude_2) * math.sin(delta_longitude / 2) ** 2
    )
    return 2 * 6_371_000 * math.asin(min(1.0, math.sqrt(haversine)))


def _normalize_text(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value.casefold())
    without_marks = "".join(char for char in decomposed if not unicodedata.combining(char))
    return " ".join(_WORD_PATTERN.findall(without_marks))


def _normalize_house_number(value: str) -> str:
    return "".join(character for character in _normalize_text(value) if character.isalnum())
