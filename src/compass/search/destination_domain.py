from dataclasses import dataclass
from typing import Literal, Protocol

from compass.routing.domain import Coordinate

DestinationKind = Literal["business", "address", "locality", "unknown"]


@dataclass(frozen=True, slots=True)
class DestinationSearchContext:
    location: Coordinate | None = None
    bias_radius_meters: float | None = None
    route_bounds: tuple[Coordinate, Coordinate] | None = None

    def __post_init__(self) -> None:
        if self.bias_radius_meters is not None and not 1 <= self.bias_radius_meters <= 50_000:
            raise ValueError("destination bias radius must be between 1 and 50000 metres")
        if self.route_bounds is not None:
            south_west, north_east = self.route_bounds
            if (
                south_west.latitude > north_east.latitude
                or south_west.longitude > north_east.longitude
            ):
                raise ValueError("destination route bounds are inverted")


@dataclass(frozen=True, slots=True)
class DestinationSuggestRequest:
    query: str
    session_id: str
    revision: int
    language: str = "it"
    context: DestinationSearchContext = DestinationSearchContext()

    def __post_init__(self) -> None:
        if not 1 <= len(self.query.strip()) <= 200:
            raise ValueError("destination query must contain 1 to 200 characters")
        if not self.session_id or len(self.session_id) > 100:
            raise ValueError("destination session id is invalid")
        if self.revision < 0:
            raise ValueError("destination revision must not be negative")


@dataclass(frozen=True, slots=True)
class DestinationSuggestion:
    id: str
    provider: str
    provider_ref: str
    kind: DestinationKind
    title: str
    subtitle: str | None
    address_preview: str | None
    distance_meters: int | None
    provider_rank: int
    requires_resolution: bool = True
    attribution: str = "Google Maps"


@dataclass(frozen=True, slots=True)
class AddressComponent:
    long_text: str
    short_text: str | None
    types: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class NormalizedAddress:
    street: str | None = None
    street_number: str | None = None
    locality: str | None = None
    province: str | None = None
    region: str | None = None
    postal_code: str | None = None
    country: str | None = None


@dataclass(frozen=True, slots=True)
class ResolvedDestinationSelection:
    provider: str
    provider_ref: str
    formatted_address: str | None
    address_components: tuple[AddressComponent, ...]
    normalized_address: NormalizedAddress
    coordinate: Coordinate
    kind: DestinationKind
    attribution: tuple[str, ...]
    field_sources: tuple[tuple[str, str], ...]
    resolution_status: Literal["resolved"] = "resolved"


@dataclass(frozen=True, slots=True)
class NavigationTarget:
    coordinate: Coordinate
    provider_ref: str | None
    map_label: str = "Destinazione selezionata"


class DestinationSearchError(Exception):
    """Base error for destination suggest/resolve."""


class DestinationSearchUnavailableError(DestinationSearchError):
    pass


class DestinationRateLimitedError(DestinationSearchError):
    def __init__(self, retry_after_seconds: int | None = None) -> None:
        super().__init__("destination provider rate limited the request")
        self.retry_after_seconds = retry_after_seconds


class DestinationProviderError(DestinationSearchError):
    pass


class DestinationNotFoundError(DestinationSearchError):
    pass


class DestinationUnresolvableError(DestinationSearchError):
    pass


class StaleDestinationSelectionError(DestinationSearchError):
    pass


class DestinationSearchProvider(Protocol):
    provider_name: str

    async def suggest(
        self,
        request: DestinationSuggestRequest,
        provider_session_token: str,
    ) -> tuple[DestinationSuggestion, ...]: ...

    async def resolve(
        self,
        provider_ref: str,
        *,
        language: str,
        provider_session_token: str,
    ) -> ResolvedDestinationSelection: ...
