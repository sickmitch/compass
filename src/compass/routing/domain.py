from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class Coordinate:
    latitude: float
    longitude: float


@dataclass(frozen=True, slots=True)
class RouteRequest:
    origin: Coordinate
    destination: Coordinate
    costing: str = "auto"
    language: str = "it-IT"


@dataclass(frozen=True, slots=True)
class WaypointRouteRequest:
    origin: Coordinate
    destination: Coordinate
    waypoints: tuple[Coordinate, ...]
    costing: str = "auto"
    language: str = "it-IT"

    def __post_init__(self) -> None:
        if not self.waypoints:
            raise ValueError("waypoint route must contain at least one waypoint")


@dataclass(frozen=True, slots=True)
class Maneuver:
    type: int
    instruction: str
    distance_meters: float
    duration_seconds: float
    begin_shape_index: int
    end_shape_index: int
    street_names: tuple[str, ...] = ()
    verbal_transition_alert_instruction: str | None = None
    verbal_pre_transition_instruction: str | None = None
    verbal_post_transition_instruction: str | None = None
    bearing_before: int | None = None
    bearing_after: int | None = None
    travel_mode: str | None = None
    travel_type: str | None = None


@dataclass(frozen=True, slots=True)
class BaseRoute:
    distance_meters: float
    duration_seconds: float
    encoded_polyline: str
    maneuvers: tuple[Maneuver, ...]
    provider: str


@dataclass(frozen=True, slots=True)
class RouteLeg:
    distance_meters: float
    duration_seconds: float
    encoded_polyline: str
    maneuvers: tuple[Maneuver, ...]


@dataclass(frozen=True, slots=True)
class WaypointRoute:
    distance_meters: float
    duration_seconds: float
    legs: tuple[RouteLeg, ...]
    provider: str


@dataclass(frozen=True, slots=True)
class MatrixRequest:
    sources: tuple[Coordinate, ...]
    targets: tuple[Coordinate, ...]
    costing: str = "auto"

    def __post_init__(self) -> None:
        if not self.sources:
            raise ValueError("matrix sources must not be empty")
        if not self.targets:
            raise ValueError("matrix targets must not be empty")


@dataclass(frozen=True, slots=True)
class MatrixCost:
    distance_meters: float
    duration_seconds: float


@dataclass(frozen=True, slots=True)
class MatrixResult:
    costs: tuple[tuple[MatrixCost | None, ...], ...]
    provider: str
    algorithm: str


class RoutingError(Exception):
    """Base error raised at the routing provider boundary."""


class RoutingUnavailableError(RoutingError):
    """The provider could not be reached or returned a transient failure."""


class RoutingProviderError(RoutingError):
    """The provider rejected the request or returned an invalid contract."""


class NoRouteError(RoutingError):
    """The provider could not find a route between valid locations."""


class MatrixLocationError(RoutingProviderError):
    """One or more matrix locations could not be correlated to the routing graph."""


class RoutingProvider(Protocol):
    async def route(self, request: RouteRequest) -> BaseRoute: ...

    async def route_with_waypoints(self, request: WaypointRouteRequest) -> WaypointRoute: ...

    async def matrix(self, request: MatrixRequest) -> MatrixResult: ...

    async def is_ready(self) -> bool: ...
