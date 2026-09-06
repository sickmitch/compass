import logging
from collections.abc import Mapping
from dataclasses import replace
from datetime import datetime
from math import isfinite
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from compass.routing.domain import (
    BaseRoute,
    Coordinate,
    Maneuver,
    ManeuverSign,
    ManeuverSignElement,
    MatrixCost,
    MatrixLocationError,
    MatrixRequest,
    MatrixResult,
    NoRouteError,
    RouteLeg,
    RouteRequest,
    RouteSpeedLimit,
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
        speed_limits_enabled: bool = False,
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
        self._speed_limits_enabled = speed_limits_enabled
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
            route = await self._with_base_speed_limits(
                _parse_route(_json_mapping(response)),
                costing=request.costing,
            )
            return replace(route, traffic_fallback_used=True)
        _raise_route_http_error(response)
        route = await self._with_base_speed_limits(
            _parse_route(_json_mapping(response)),
            costing=request.costing,
        )
        if not self._traffic_aware:
            return route
        baseline = await self._route_baseline(payload, expected_leg_count=1)
        return replace(
            route,
            traffic_aware=True,
            traffic_delay_seconds=(
                max(0.0, route.duration_seconds - baseline.duration_seconds)
                if isinstance(baseline, BaseRoute)
                else None
            ),
        )

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
            route = await self._with_waypoint_speed_limits(
                _parse_waypoint_route(
                    _json_mapping(response), expected_leg_count=len(locations) - 1
                ),
                costing=request.costing,
            )
            return replace(route, traffic_fallback_used=True)
        _raise_route_http_error(response)
        route = await self._with_waypoint_speed_limits(
            _parse_waypoint_route(
                _json_mapping(response), expected_leg_count=len(locations) - 1
            ),
            costing=request.costing,
        )
        if not self._traffic_aware:
            return route
        baseline = await self._route_baseline(
            payload, expected_leg_count=len(locations) - 1
        )
        return replace(
            route,
            traffic_aware=True,
            traffic_delay_seconds=(
                max(0.0, route.duration_seconds - baseline.duration_seconds)
                if isinstance(baseline, WaypointRoute)
                else None
            ),
        )

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

    async def _route_baseline(
        self,
        payload: Mapping[str, Any],
        *,
        expected_leg_count: int,
    ) -> BaseRoute | WaypointRoute | None:
        """Calculate a graph-speed baseline without invalidating a valid live route."""
        try:
            response = await self._request(
                "POST", "/route", json=_without_time_dependent_costing(payload)
            )
            _raise_route_http_error(response)
            value = _json_mapping(response)
            if expected_leg_count == 1:
                return _parse_route(value)
            return _parse_waypoint_route(value, expected_leg_count=expected_leg_count)
        except (RoutingUnavailableError, RoutingProviderError, NoRouteError):
            LOGGER.warning(
                "traffic route succeeded but graph-speed delay baseline was unavailable",
                extra={"routing_fallback": "traffic_delay_unavailable"},
            )
            return None

    async def _with_base_speed_limits(
        self,
        route: BaseRoute,
        *,
        costing: str,
    ) -> BaseRoute:
        speed_limits, source = await self._speed_limit_profile(
            route.encoded_polyline,
            costing=costing,
            maximum_shape_index=max(
                (maneuver.end_shape_index for maneuver in route.maneuvers),
                default=0,
            ),
        )
        return replace(
            route,
            speed_limits=speed_limits,
            speed_limit_source=source,
        )

    async def _with_waypoint_speed_limits(
        self,
        route: WaypointRoute,
        *,
        costing: str,
    ) -> WaypointRoute:
        legs: list[RouteLeg] = []
        for leg in route.legs:
            speed_limits, source = await self._speed_limit_profile(
                leg.encoded_polyline,
                costing=costing,
                maximum_shape_index=max(
                    (maneuver.end_shape_index for maneuver in leg.maneuvers),
                    default=0,
                ),
            )
            legs.append(
                replace(
                    leg,
                    speed_limits=speed_limits,
                    speed_limit_source=source,
                )
            )
        return replace(route, legs=tuple(legs))

    async def _speed_limit_profile(
        self,
        encoded_polyline: str,
        *,
        costing: str,
        maximum_shape_index: int,
    ) -> tuple[tuple[RouteSpeedLimit, ...], str | None]:
        if not self._speed_limits_enabled:
            return (), None
        try:
            response = await self._request(
                "POST",
                "/trace_attributes",
                json=_speed_limit_trace_payload(encoded_polyline, costing=costing),
            )
            if response.status_code < 200 or response.status_code >= 300:
                raise RoutingProviderError(
                    f"Valhalla rejected speed-limit enrichment with HTTP {response.status_code}"
                )
            return (
                _parse_speed_limit_profile(
                    _json_mapping(response),
                    maximum_shape_index=maximum_shape_index,
                ),
                "valhalla_graph",
            )
        except (RoutingUnavailableError, RoutingProviderError):
            LOGGER.warning(
                "route speed-limit enrichment unavailable; continuing without speed limits",
                extra={"road_context_fallback": "speed_limits_unavailable"},
            )
            return (), None

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


def _speed_limit_trace_payload(
    encoded_polyline: str,
    *,
    costing: str,
) -> dict[str, Any]:
    return {
        "encoded_polyline": encoded_polyline,
        "shape_match": "edge_walk",
        "costing": costing,
        "filters": {
            "attributes": [
                "edge.begin_shape_index",
                "edge.end_shape_index",
                "edge.speed_limit",
            ],
            "action": "include",
        },
    }


def _parse_speed_limit_profile(
    payload: Mapping[str, Any],
    *,
    maximum_shape_index: int | None = None,
) -> tuple[RouteSpeedLimit, ...]:
    edges = _list(payload.get("edges"), "edges")
    if not edges:
        raise RoutingProviderError("Valhalla speed-limit trace contains no edges")

    profile: list[RouteSpeedLimit] = []
    previous_end = 0
    for index, raw_edge in enumerate(edges):
        field = f"edges[{index}]"
        edge = _mapping(raw_edge, field)
        begin = _integer(edge.get("begin_shape_index"), f"{field}.begin_shape_index")
        end = _integer(edge.get("end_shape_index"), f"{field}.end_shape_index")
        if (
            begin < 0
            or end <= begin
            or begin < previous_end
            or (maximum_shape_index is not None and end > maximum_shape_index)
        ):
            raise RoutingProviderError(f"{field} has invalid shape indexes")
        previous_end = end

        raw_limit = edge.get("speed_limit")
        if raw_limit is None:
            continue
        speed_limit = _integer(raw_limit, f"{field}.speed_limit")
        # Valhalla uses zero for absent data and 255 for an unlimited edge.
        # Neither is a numeric regulatory limit suitable for the UI.
        if speed_limit in {0, 255}:
            continue
        if not 1 <= speed_limit <= 250:
            raise RoutingProviderError(f"{field}.speed_limit is outside the supported range")

        if (
            profile
            and profile[-1].speed_limit_kph == speed_limit
            and profile[-1].end_shape_index == begin
        ):
            previous = profile[-1]
            profile[-1] = RouteSpeedLimit(
                begin_shape_index=previous.begin_shape_index,
                end_shape_index=end,
                speed_limit_kph=speed_limit,
            )
        else:
            profile.append(
                RouteSpeedLimit(
                    begin_shape_index=begin,
                    end_shape_index=end,
                    speed_limit_kph=speed_limit,
                )
            )
    return tuple(profile)


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
    if traffic_aware:
        # Valhalla's bidirectional mode consumes current traffic close to the
        # departure and fades it along the route. It avoids the convergence
        # limits of single-direction time-dependent A* on long road routes.
        payload["prioritize_bidirectional"] = True
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
        # Valhalla 3.8.3 can return an immediate 442 for long current-time
        # routes forced through bidirectional A*. An explicit depart-at value
        # for the current local minute has the same traffic semantics and is
        # the mode for which prioritize_bidirectional provides time-aware
        # forward costing with a recosted reverse search.
        departure_at = datetime.now(departure_timezone)
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
    fallback.pop("prioritize_bidirectional", None)
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
            sign=_parse_maneuver_sign(value.get("sign"), f"{field}.sign"),
            roundabout_exit_count=_optional_non_negative_int(
                value.get("roundabout_exit_count"),
                f"{field}.roundabout_exit_count",
            ),
        )
    except KeyError as error:
        raise RoutingProviderError(
            f"Valhalla response is missing {field}.{error.args[0]}"
        ) from error


def _parse_maneuver_sign(value: Any, field: str) -> ManeuverSign | None:
    if value is None:
        return None
    sign = _mapping(value, field)
    parsed = ManeuverSign(
        exit_number_elements=_parse_sign_elements(
            sign.get("exit_number_elements", []),
            f"{field}.exit_number_elements",
        ),
        exit_branch_elements=_parse_sign_elements(
            sign.get("exit_branch_elements", []),
            f"{field}.exit_branch_elements",
        ),
        exit_toward_elements=_parse_sign_elements(
            sign.get("exit_toward_elements", []),
            f"{field}.exit_toward_elements",
        ),
        exit_name_elements=_parse_sign_elements(
            sign.get("exit_name_elements", []),
            f"{field}.exit_name_elements",
        ),
    )
    if not any(
        (
            parsed.exit_number_elements,
            parsed.exit_branch_elements,
            parsed.exit_toward_elements,
            parsed.exit_name_elements,
        )
    ):
        return None
    return parsed


def _parse_sign_elements(value: Any, field: str) -> tuple[ManeuverSignElement, ...]:
    elements = _list(value, field)
    parsed: list[ManeuverSignElement] = []
    for index, element_value in enumerate(elements):
        element_field = f"{field}[{index}]"
        element = _mapping(element_value, element_field)
        text = _string(element.get("text"), f"{element_field}.text").strip()
        if not text:
            raise RoutingProviderError(f"{element_field}.text must not be blank")
        parsed.append(
            ManeuverSignElement(
                text=text,
                consecutive_count=_optional_non_negative_int(
                    element.get("consecutive_count"),
                    f"{element_field}.consecutive_count",
                ),
            )
        )
    return tuple(parsed)


def _optional_non_negative_int(value: Any, field: str) -> int | None:
    if value is None:
        return None
    parsed = _integer(value, field)
    if parsed < 0:
        raise RoutingProviderError(f"{field} must not be negative")
    return parsed


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
