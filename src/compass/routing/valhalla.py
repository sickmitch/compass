import logging
from collections.abc import Mapping
from datetime import datetime
from math import isfinite
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    Maneuver,
    MatrixCost,
    MatrixLocationError,
    MatrixRequest,
    MatrixResult,
    NoRouteError,
    RouteLeg,
    RouteRequest,
    RoutingProviderError,
    RoutingUnavailableError,
    WaypointRoute,
    WaypointRouteRequest,
)

VALHALLA_NO_ROUTE_ERROR_CODES = {442}
VALHALLA_MATRIX_LOCATION_ERROR_CODES = {171}
DEFAULT_DEPARTURE_TIMEZONE = ZoneInfo("Europe/Rome")
LOGGER = logging.getLogger("compass.routing.valhalla")


class ValhallaRoutingAdapter:
    """Translate the Compass routing boundary to Valhalla's HTTP API."""

    def __init__(
        self,
        *,
        base_url: str,
        connect_timeout_seconds: float,
        read_timeout_seconds: float,
        user_agent: str,
        traffic_aware: bool = False,
        traffic_speed_types: tuple[str, ...] = (
            "current",
            "predicted",
            "constrained",
            "freeflow",
        ),
        departure_timezone: str = "Europe/Rome",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = httpx.Timeout(
            connect=connect_timeout_seconds,
            read=read_timeout_seconds,
            write=read_timeout_seconds,
            pool=connect_timeout_seconds,
        )
        self._headers = {"User-Agent": user_agent}
        self._traffic_aware = traffic_aware
        self._traffic_speed_types = traffic_speed_types
        self._departure_timezone = ZoneInfo(departure_timezone)
        self._client = client

    async def route(self, request: RouteRequest) -> BaseRoute:
        payload = _route_payload(
            (request.origin, request.destination),
            costing=request.costing,
            language=request.language,
            departure_at=request.departure_at,
            traffic_aware=self._traffic_aware,
            traffic_speed_types=self._traffic_speed_types,
            departure_timezone=self._departure_timezone,
        )
        response = await self._request("POST", "/route", json=payload)
        if self._traffic_aware and _is_no_route_response(response):
            LOGGER.warning(
                "time-dependent route found no path; retrying with Valhalla graph speeds",
                extra={"routing_fallback": "valhalla_graph_speeds"},
            )
            response = await self._request(
                "POST",
                "/route",
                json=_without_time_dependent_costing(payload),
            )
        _raise_route_http_error(response)

        return _parse_route(_json_mapping(response))

    async def route_with_waypoints(self, request: WaypointRouteRequest) -> WaypointRoute:
        locations = (request.origin, *request.waypoints, request.destination)
        payload = _route_payload(
            locations,
            costing=request.costing,
            language=request.language,
            departure_at=request.departure_at,
            traffic_aware=self._traffic_aware,
            traffic_speed_types=self._traffic_speed_types,
            departure_timezone=self._departure_timezone,
        )
        response = await self._request(
            "POST",
            "/route",
            json=payload,
        )
        if self._traffic_aware and _is_no_route_response(response):
            LOGGER.warning(
                "time-dependent waypoint route found no path; retrying with Valhalla graph speeds",
                extra={"routing_fallback": "valhalla_graph_speeds"},
            )
            response = await self._request(
                "POST",
                "/route",
                json=_without_time_dependent_costing(payload),
            )
        _raise_route_http_error(response)
        return _parse_waypoint_route(_json_mapping(response), expected_leg_count=len(locations) - 1)

    async def matrix(self, request: MatrixRequest) -> MatrixResult:
        payload = {
            "sources": [
                {"lat": coordinate.latitude, "lon": coordinate.longitude}
                for coordinate in request.sources
            ],
            "targets": [
                {"lat": coordinate.latitude, "lon": coordinate.longitude}
                for coordinate in request.targets
            ],
            "costing": request.costing,
            "units": "kilometers",
            "verbose": True,
            "shape_format": "no_shape",
        }
        _apply_time_dependent_costing(
            payload,
            costing=request.costing,
            departure_at=request.departure_at,
            traffic_aware=self._traffic_aware,
            traffic_speed_types=self._traffic_speed_types,
            departure_timezone=self._departure_timezone,
        )
        response = await self._request("POST", "/sources_to_targets", json=payload)
        if response.status_code >= 500:
            raise RoutingUnavailableError(f"Valhalla returned HTTP {response.status_code}")
        if response.status_code >= 400:
            error_code = _optional_int(_json_mapping(response).get("error_code"))
            if error_code in VALHALLA_MATRIX_LOCATION_ERROR_CODES:
                raise MatrixLocationError(
                    "Valhalla could not correlate one or more matrix locations"
                )
            if error_code in VALHALLA_NO_ROUTE_ERROR_CODES:
                return MatrixResult(
                    costs=tuple(
                        tuple(None for _target in request.targets) for _source in request.sources
                    ),
                    provider="valhalla",
                    algorithm="no_route",
                )
            raise RoutingProviderError(
                f"Valhalla rejected the matrix request with HTTP {response.status_code}"
            )
        if response.status_code >= 300:
            raise RoutingProviderError(f"Valhalla returned unexpected HTTP {response.status_code}")

        return _parse_matrix(
            _json_mapping(response),
            source_count=len(request.sources),
            target_count=len(request.targets),
        )

    async def is_ready(self) -> bool:
        try:
            response = await self._request("GET", "/status")
            if response.status_code != 200:
                return False
            _json_mapping(response)
        except (RoutingUnavailableError, RoutingProviderError):
            return False
        return True

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        try:
            if self._client is not None:
                return await self._client.request(
                    method,
                    f"{self._base_url}{path}",
                    timeout=self._timeout,
                    headers=self._headers,
                    **kwargs,
                )
            async with httpx.AsyncClient() as client:
                return await client.request(
                    method,
                    f"{self._base_url}{path}",
                    timeout=self._timeout,
                    headers=self._headers,
                    **kwargs,
                )
        except httpx.TransportError as error:
            raise RoutingUnavailableError("Valhalla is unavailable") from error


def _json_mapping(response: httpx.Response) -> Mapping[str, Any]:
    try:
        payload = response.json()
    except ValueError as error:
        raise RoutingProviderError("Valhalla returned invalid JSON") from error
    if not isinstance(payload, Mapping):
        raise RoutingProviderError("Valhalla returned an invalid response object")
    return payload


def _route_payload(
    locations: tuple[Coordinate, ...],
    *,
    costing: str,
    language: str,
    departure_at: datetime | None = None,
    traffic_aware: bool = False,
    traffic_speed_types: tuple[str, ...] = (
        "current",
        "predicted",
        "constrained",
        "freeflow",
    ),
    departure_timezone: ZoneInfo = DEFAULT_DEPARTURE_TIMEZONE,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "locations": [
            {
                "lat": coordinate.latitude,
                "lon": coordinate.longitude,
                "type": "break",
            }
            for coordinate in locations
        ],
        "costing": costing,
        "units": "kilometers",
        "language": language,
        "directions_type": "instructions",
        "shape_format": "polyline6",
    }
    _apply_time_dependent_costing(
        payload,
        costing=costing,
        departure_at=departure_at,
        traffic_aware=traffic_aware,
        traffic_speed_types=traffic_speed_types,
        departure_timezone=departure_timezone,
    )
    return payload


def _apply_time_dependent_costing(
    payload: dict[str, Any],
    *,
    costing: str,
    departure_at: datetime | None,
    traffic_aware: bool,
    traffic_speed_types: tuple[str, ...],
    departure_timezone: ZoneInfo,
) -> None:
    if not traffic_aware:
        return
    payload["date_time"] = _date_time_payload(departure_at, departure_timezone)
    if costing == "auto":
        payload["costing_options"] = {
            "auto": {"speed_types": list(traffic_speed_types)}
        }


def _date_time_payload(
    departure_at: datetime | None,
    departure_timezone: ZoneInfo,
) -> dict[str, Any]:
    if departure_at is None:
        return {"type": 0}
    if departure_at.tzinfo is None or departure_at.utcoffset() is None:
        raise ValueError("departure_at must include a UTC offset")
    local_departure = departure_at.astimezone(departure_timezone)
    return {
        "type": 1,
        "value": local_departure.replace(tzinfo=None).isoformat(timespec="minutes"),
    }


def _raise_route_http_error(response: httpx.Response) -> None:
    if response.status_code >= 500:
        raise RoutingUnavailableError(f"Valhalla returned HTTP {response.status_code}")
    if response.status_code >= 400:
        error_code = _optional_int(_json_mapping(response).get("error_code"))
        if error_code in VALHALLA_NO_ROUTE_ERROR_CODES:
            raise NoRouteError("Valhalla could not find a route between the locations")
        raise RoutingProviderError(
            f"Valhalla rejected the route request with HTTP {response.status_code}"
        )
    if response.status_code >= 300:
        raise RoutingProviderError(f"Valhalla returned unexpected HTTP {response.status_code}")


def _is_no_route_response(response: httpx.Response) -> bool:
    if response.status_code < 400:
        return False
    try:
        return _optional_int(_json_mapping(response).get("error_code")) in (
            VALHALLA_NO_ROUTE_ERROR_CODES
        )
    except RoutingProviderError:
        return False


def _without_time_dependent_costing(payload: Mapping[str, Any]) -> dict[str, Any]:
    fallback = dict(payload)
    fallback.pop("date_time", None)
    fallback.pop("costing_options", None)
    return fallback


def _parse_route(payload: Mapping[str, Any]) -> BaseRoute:
    try:
        trip = _mapping(payload["trip"], "trip")
        status = _number(trip["status"], "trip.status")
        if status != 0:
            raise RoutingProviderError(f"Valhalla returned route status {status:g}")
        summary = _mapping(trip["summary"], "trip.summary")
        legs = _list(trip["legs"], "trip.legs")
        if len(legs) != 1:
            raise RoutingProviderError("A base route must contain exactly one leg")
        leg = _parse_leg(_mapping(legs[0], "trip.legs[0]"), leg_index=0)
        distance_meters = _number(summary["length"], "trip.summary.length") * 1000
        duration_seconds = _number(summary["time"], "trip.summary.time")
        if distance_meters <= 0 or duration_seconds <= 0:
            raise RoutingProviderError("Valhalla returned a non-navigable zero-cost route")
        return BaseRoute(
            distance_meters=distance_meters,
            duration_seconds=duration_seconds,
            encoded_polyline=leg.encoded_polyline,
            maneuvers=leg.maneuvers,
            provider="valhalla",
        )
    except KeyError as error:
        raise RoutingProviderError(f"Valhalla response is missing {error.args[0]}") from error


def _parse_waypoint_route(payload: Mapping[str, Any], *, expected_leg_count: int) -> WaypointRoute:
    try:
        trip = _mapping(payload["trip"], "trip")
        status = _number(trip["status"], "trip.status")
        if status != 0:
            raise RoutingProviderError(f"Valhalla returned route status {status:g}")
        summary = _mapping(trip["summary"], "trip.summary")
        raw_legs = _list(trip["legs"], "trip.legs")
        if len(raw_legs) != expected_leg_count:
            raise RoutingProviderError(
                "Valhalla waypoint route contains an unexpected number of legs"
            )
        legs = tuple(
            _parse_leg(_mapping(value, f"trip.legs[{index}]"), leg_index=index)
            for index, value in enumerate(raw_legs)
        )
        return WaypointRoute(
            distance_meters=_number(summary["length"], "trip.summary.length") * 1000,
            duration_seconds=_number(summary["time"], "trip.summary.time"),
            legs=legs,
            provider="valhalla",
        )
    except KeyError as error:
        raise RoutingProviderError(f"Valhalla response is missing {error.args[0]}") from error


def _parse_leg(value: Mapping[str, Any], *, leg_index: int) -> RouteLeg:
    field = f"trip.legs[{leg_index}]"
    try:
        summary = _mapping(value["summary"], f"{field}.summary")
        shape = value["shape"]
        if not isinstance(shape, str) or not shape:
            raise RoutingProviderError(f"{field}.shape must be a non-empty string")
        maneuvers = tuple(
            _parse_maneuver(
                _mapping(maneuver, f"{field}.maneuvers[{index}]"),
                index,
                leg_index=leg_index,
            )
            for index, maneuver in enumerate(_list(value["maneuvers"], f"{field}.maneuvers"))
        )
        if not maneuvers:
            raise RoutingProviderError(f"{field} must contain at least one maneuver")
        distance_meters = _number(summary["length"], f"{field}.summary.length") * 1000
        duration_seconds = _number(summary["time"], f"{field}.summary.time")
        if distance_meters <= 0 or duration_seconds <= 0:
            raise RoutingProviderError(f"{field} contains a non-navigable zero-cost route")
        return RouteLeg(
            distance_meters=distance_meters,
            duration_seconds=duration_seconds,
            encoded_polyline=shape,
            maneuvers=maneuvers,
        )
    except KeyError as error:
        raise RoutingProviderError(
            f"Valhalla response is missing {field}.{error.args[0]}"
        ) from error


def _parse_matrix(
    payload: Mapping[str, Any], *, source_count: int, target_count: int
) -> MatrixResult:
    try:
        units = _string(payload["units"], "units")
        if units != "kilometers":
            raise RoutingProviderError("Valhalla matrix units must be kilometers")
        algorithm = _string(payload["algorithm"], "algorithm")
        rows = _list(payload["sources_to_targets"], "sources_to_targets")
        if len(rows) != source_count:
            raise RoutingProviderError("Valhalla matrix source dimension is invalid")

        parsed_rows: list[tuple[MatrixCost | None, ...]] = []
        for source_index, row_value in enumerate(rows):
            row = _list(row_value, f"sources_to_targets[{source_index}]")
            if len(row) != target_count:
                raise RoutingProviderError("Valhalla matrix target dimension is invalid")
            parsed_row: list[MatrixCost | None] = []
            for target_index, cell_value in enumerate(row):
                field = f"sources_to_targets[{source_index}][{target_index}]"
                cell = _mapping(cell_value, field)
                if _integer(cell["from_index"], f"{field}.from_index") != source_index:
                    raise RoutingProviderError(f"{field}.from_index is invalid")
                if _integer(cell["to_index"], f"{field}.to_index") != target_index:
                    raise RoutingProviderError(f"{field}.to_index is invalid")
                distance = cell.get("distance")
                duration = cell.get("time")
                if distance is None and duration is None:
                    parsed_row.append(None)
                elif distance is None or duration is None:
                    raise RoutingProviderError(
                        f"{field} must contain both distance and time or neither"
                    )
                else:
                    parsed_row.append(
                        MatrixCost(
                            distance_meters=_number(distance, f"{field}.distance") * 1000,
                            duration_seconds=_number(duration, f"{field}.time"),
                        )
                    )
            parsed_rows.append(tuple(parsed_row))
        return MatrixResult(
            costs=tuple(parsed_rows),
            provider="valhalla",
            algorithm=algorithm,
        )
    except KeyError as error:
        raise RoutingProviderError(
            f"Valhalla matrix response is missing {error.args[0]}"
        ) from error


def _parse_maneuver(value: Mapping[str, Any], index: int, *, leg_index: int = 0) -> Maneuver:
    field = f"trip.legs[{leg_index}].maneuvers[{index}]"
    try:
        street_names_value = value.get("street_names", [])
        if not isinstance(street_names_value, list) or not all(
            isinstance(name, str) for name in street_names_value
        ):
            raise RoutingProviderError(f"{field}.street_names must be a string list")
        return Maneuver(
            type=_integer(value["type"], f"{field}.type"),
            instruction=_string(value["instruction"], f"{field}.instruction"),
            distance_meters=_number(value["length"], f"{field}.length") * 1000,
            duration_seconds=_number(value["time"], f"{field}.time"),
            begin_shape_index=_integer(value["begin_shape_index"], f"{field}.begin_shape_index"),
            end_shape_index=_integer(value["end_shape_index"], f"{field}.end_shape_index"),
            street_names=tuple(street_names_value),
            verbal_transition_alert_instruction=_optional_string(
                value.get("verbal_transition_alert_instruction"),
                f"{field}.verbal_transition_alert_instruction",
            ),
            verbal_pre_transition_instruction=_optional_string(
                value.get("verbal_pre_transition_instruction"),
                f"{field}.verbal_pre_transition_instruction",
            ),
            verbal_post_transition_instruction=_optional_string(
                value.get("verbal_post_transition_instruction"),
                f"{field}.verbal_post_transition_instruction",
            ),
            bearing_before=_optional_int(value.get("bearing_before"), f"{field}.bearing_before"),
            bearing_after=_optional_int(value.get("bearing_after"), f"{field}.bearing_after"),
            travel_mode=_optional_string(value.get("travel_mode"), f"{field}.travel_mode"),
            travel_type=_optional_string(value.get("travel_type"), f"{field}.travel_type"),
        )
    except KeyError as error:
        raise RoutingProviderError(
            f"Valhalla response is missing {field}.{error.args[0]}"
        ) from error


def _mapping(value: Any, field: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise RoutingProviderError(f"{field} must be an object")
    return value


def _list(value: Any, field: str) -> list[Any]:
    if not isinstance(value, list):
        raise RoutingProviderError(f"{field} must be a list")
    return value


def _number(value: Any, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise RoutingProviderError(f"{field} must be a number")
    if not isfinite(value):
        raise RoutingProviderError(f"{field} must be finite")
    if value < 0:
        raise RoutingProviderError(f"{field} must not be negative")
    return float(value)


def _integer(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise RoutingProviderError(f"{field} must be an integer")
    if value < 0:
        raise RoutingProviderError(f"{field} must not be negative")
    return value


def _string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise RoutingProviderError(f"{field} must be a non-empty string")
    return value


def _optional_string(value: Any, field: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise RoutingProviderError(f"{field} must be a string or null")
    return value


def _optional_int(value: Any, field: str = "value") -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise RoutingProviderError(f"{field} must be an integer or null")
    return value
