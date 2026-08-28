from collections.abc import Mapping
from math import isfinite
from typing import Any

import httpx

from compass.routing.domain import (
    BaseRoute,
    Maneuver,
    MatrixCost,
    MatrixLocationError,
    MatrixRequest,
    MatrixResult,
    NoRouteError,
    RouteRequest,
    RoutingProviderError,
    RoutingUnavailableError,
)

VALHALLA_NO_ROUTE_ERROR_CODES = {442}
VALHALLA_MATRIX_LOCATION_ERROR_CODES = {171}


class ValhallaRoutingAdapter:
    """Translate the Compass routing boundary to Valhalla's HTTP API."""

    def __init__(
        self,
        *,
        base_url: str,
        connect_timeout_seconds: float,
        read_timeout_seconds: float,
        user_agent: str,
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
        self._client = client

    async def route(self, request: RouteRequest) -> BaseRoute:
        payload = {
            "locations": [
                {
                    "lat": request.origin.latitude,
                    "lon": request.origin.longitude,
                    "type": "break",
                },
                {
                    "lat": request.destination.latitude,
                    "lon": request.destination.longitude,
                    "type": "break",
                },
            ],
            "costing": request.costing,
            "units": "kilometers",
            "language": request.language,
            "directions_type": "instructions",
            "shape_format": "polyline6",
        }
        response = await self._request("POST", "/route", json=payload)
        if response.status_code >= 500:
            raise RoutingUnavailableError(
                f"Valhalla returned HTTP {response.status_code}"
            )
        if response.status_code >= 400:
            error_code = _optional_int(_json_mapping(response).get("error_code"))
            if error_code in VALHALLA_NO_ROUTE_ERROR_CODES:
                raise NoRouteError("Valhalla could not find a route between the locations")
            raise RoutingProviderError(
                f"Valhalla rejected the route request with HTTP {response.status_code}"
            )
        if response.status_code >= 300:
            raise RoutingProviderError(
                f"Valhalla returned unexpected HTTP {response.status_code}"
            )

        return _parse_route(_json_mapping(response))

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
        response = await self._request("POST", "/sources_to_targets", json=payload)
        if response.status_code >= 500:
            raise RoutingUnavailableError(
                f"Valhalla returned HTTP {response.status_code}"
            )
        if response.status_code >= 400:
            error_code = _optional_int(_json_mapping(response).get("error_code"))
            if error_code in VALHALLA_MATRIX_LOCATION_ERROR_CODES:
                raise MatrixLocationError(
                    "Valhalla could not correlate one or more matrix locations"
                )
            if error_code in VALHALLA_NO_ROUTE_ERROR_CODES:
                return MatrixResult(
                    costs=tuple(
                        tuple(None for _target in request.targets)
                        for _source in request.sources
                    ),
                    provider="valhalla",
                    algorithm="no_route",
                )
            raise RoutingProviderError(
                f"Valhalla rejected the matrix request with HTTP {response.status_code}"
            )
        if response.status_code >= 300:
            raise RoutingProviderError(
                f"Valhalla returned unexpected HTTP {response.status_code}"
            )

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
        leg = _mapping(legs[0], "trip.legs[0]")
        shape = leg["shape"]
        if not isinstance(shape, str) or not shape:
            raise RoutingProviderError("trip.legs[0].shape must be a non-empty string")

        maneuvers = tuple(
            _parse_maneuver(_mapping(value, f"trip.legs[0].maneuvers[{index}]"), index)
            for index, value in enumerate(_list(leg["maneuvers"], "trip.legs[0].maneuvers"))
        )
        if not maneuvers:
            raise RoutingProviderError("A base route must contain at least one maneuver")
        return BaseRoute(
            distance_meters=_number(summary["length"], "trip.summary.length") * 1000,
            duration_seconds=_number(summary["time"], "trip.summary.time"),
            encoded_polyline=shape,
            maneuvers=maneuvers,
            provider="valhalla",
        )
    except KeyError as error:
        raise RoutingProviderError(
            f"Valhalla response is missing {error.args[0]}"
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


def _parse_maneuver(value: Mapping[str, Any], index: int) -> Maneuver:
    field = f"trip.legs[0].maneuvers[{index}]"
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
            begin_shape_index=_integer(
                value["begin_shape_index"], f"{field}.begin_shape_index"
            ),
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
