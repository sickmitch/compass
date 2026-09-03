from dataclasses import dataclass
from typing import Literal, Protocol

from compass.routing.domain import Coordinate

PlaceKind = Literal["address", "locality", "poi", "coordinate", "unknown"]


@dataclass(frozen=True, slots=True)
class PlaceSearchRequest:
    query: str
    limit: int = 8
    language: str = "it"

    def __post_init__(self) -> None:
        normalized = self.query.strip()
        if not normalized or len(normalized) > 200:
            raise ValueError("place search query must contain 1 to 200 characters")
        if not 1 <= self.limit <= 20:
            raise ValueError("place search limit must be between 1 and 20")


@dataclass(frozen=True, slots=True)
class PlaceSearchResult:
    result_id: str
    display_name: str
    address: str | None
    coordinate: Coordinate
    kind: PlaceKind
    category: str | None = None
    poi_name: str | None = None
    provider: str = "coordinate"
    provider_place_id: str | None = None


class PlaceSearchError(Exception):
    """Base error at the geocoding provider boundary."""


class PlaceSearchUnavailableError(PlaceSearchError):
    """The configured provider is temporarily unavailable."""


class PlaceSearchProviderError(PlaceSearchError):
    """The provider returned an invalid or rejected response."""


class PlaceSearchProvider(Protocol):
    async def search(self, request: PlaceSearchRequest) -> tuple[PlaceSearchResult, ...]: ...
