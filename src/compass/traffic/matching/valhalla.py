from __future__ import annotations

from collections.abc import Mapping
from math import asin, cos, isfinite, radians, sin, sqrt
from typing import Any

import httpx

from compass.routing.domain import Coordinate
from compass.traffic.domain import TrafficEdgeMatch, TrafficFlowSegment
from compass.traffic.matching.openlr import OpenLrDecodeError, OpenLrDecoder
from compass.traffic.valhalla.graph_id import graph_id_to_string

GEOMETRY_MATCHING_VERSION = "valhalla-trace-v1"
OPENLR_GEOMETRY_MATCHING_VERSION = "valhalla-openlr-geometry-v1"


class ValhallaTraceTrafficEdgeMatcher:
    """Map-match provider geometry to ordered Valhalla directed edges."""

    def __init__(
        self,
        *,
        base_url: str,
        connect_timeout_seconds: float,
        read_timeout_seconds: float,
        user_agent: str,
        search_radius_meters: float = 75,
        gps_accuracy_meters: float = 15,
        openlr_endpoint_tolerance_meters: float = 300,
        expected_tileset_identity: str = "",
        openlr_decoder: OpenLrDecoder | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if search_radius_meters <= 0:
            raise ValueError("traffic match search radius must be positive")
        if gps_accuracy_meters <= 0:
            raise ValueError("traffic match GPS accuracy must be positive")
        if openlr_endpoint_tolerance_meters <= 0:
            raise ValueError("OpenLR endpoint tolerance must be positive")
        self._base_url = base_url.rstrip("/")
        self._timeout = httpx.Timeout(
            connect=connect_timeout_seconds,
            read=read_timeout_seconds,
            write=read_timeout_seconds,
            pool=connect_timeout_seconds,
        )
        self._headers = {"User-Agent": user_agent}
        self._search_radius_meters = search_radius_meters
        self._gps_accuracy_meters = gps_accuracy_meters
        self._openlr_endpoint_tolerance_meters = openlr_endpoint_tolerance_meters
        self._expected_tileset_identity = expected_tileset_identity
        self._openlr_decoder = openlr_decoder
        self._client = client
        self._tileset_identity: str | None = None

    async def match(self, segment: TrafficFlowSegment) -> TrafficEdgeMatch:
        if segment.geometry is None:
            source = "OpenLR" if segment.openlr else "OSM hints"
            return _unmatched(
                f"{source} present without provider geometry; no safe graph path can be traced"
            )

        tileset_identity = await self._resolve_tileset_identity()
        if tileset_identity is None:
            return _unmatched("Valhalla tileset identity could not be resolved")
        if (
            self._expected_tileset_identity
            and tileset_identity != self._expected_tileset_identity
        ):
            return _unmatched(
                "Valhalla tileset identity does not match the configured traffic mapping",
                tileset_identity=tileset_identity,
            )

        direction_match, direction_warning, openlr_decoded = (
            await self._verify_openlr_direction(segment)
        )
        payload = _trace_payload(
            segment.geometry,
            search_radius_meters=self._search_radius_meters,
            gps_accuracy_meters=self._gps_accuracy_meters,
        )
        try:
            response = await self._request("POST", "/trace_attributes", json=payload)
        except httpx.TransportError:
            return _unmatched(
                "Valhalla trace service is unavailable",
                tileset_identity=tileset_identity,
            )
        if response.status_code != 200:
            return _unmatched(
                f"Valhalla rejected trace matching with HTTP {response.status_code}",
                tileset_identity=tileset_identity,
            )
        try:
            result = _parse_trace_match(
                response.json(),
                segment=segment,
                tileset_identity=tileset_identity,
                search_radius_meters=self._search_radius_meters,
                expected_tileset_configured=bool(self._expected_tileset_identity),
                openlr_direction_match=direction_match,
                openlr_direction_warning=direction_warning,
                openlr_decoded=openlr_decoded,
            )
        except (TypeError, ValueError):
            return _unmatched(
                "Valhalla returned a malformed trace response",
                tileset_identity=tileset_identity,
            )
        return result

    async def _verify_openlr_direction(
        self, segment: TrafficFlowSegment
    ) -> tuple[bool | None, str | None, bool]:
        if not segment.openlr:
            return None, None, False
        if self._openlr_decoder is None:
            return (
                None,
                "OpenLR was not decoded; provider geometry direction was not verified",
                False,
            )
        try:
            decoded = await self._openlr_decoder.decode_line(segment.openlr)
        except OpenLrDecodeError:
            return (
                None,
                "native OpenLR decoding failed; provider geometry direction was not verified",
                False,
            )
        geometry = segment.geometry or ()
        direct_start = _haversine_meters(decoded.lrps[0], geometry[0])
        direct_end = _haversine_meters(decoded.lrps[-1], geometry[-1])
        reverse_total = _haversine_meters(decoded.lrps[0], geometry[-1]) + (
            _haversine_meters(decoded.lrps[-1], geometry[0])
        )
        direct_total = direct_start + direct_end
        verified = (
            direct_total < reverse_total
            and max(direct_start, direct_end)
            <= self._openlr_endpoint_tolerance_meters
        )
        if verified:
            return (
                True,
                "OpenLR direction verified against provider geometry endpoints "
                f"({direct_start:.1f} m start, {direct_end:.1f} m end)",
                True,
            )
        return (
            False,
            "provider geometry does not align with the decoded OpenLR direction",
            True,
        )

    async def _resolve_tileset_identity(self) -> str | None:
        if self._tileset_identity is not None:
            return self._tileset_identity
        try:
            response = await self._request("GET", "/status")
            if response.status_code != 200:
                return None
            payload = response.json()
            if not isinstance(payload, Mapping):
                return None
            version = payload.get("version")
            last_modified = payload.get("tileset_last_modified")
            if not isinstance(version, str) or not version:
                return None
            if isinstance(last_modified, bool) or not isinstance(last_modified, int | float):
                return None
            self._tileset_identity = f"valhalla-{version}:{int(last_modified)}"
            return self._tileset_identity
        except (httpx.TransportError, TypeError, ValueError):
            return None

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
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


def _trace_payload(
    geometry: tuple[Coordinate, ...],
    *,
    search_radius_meters: float,
    gps_accuracy_meters: float,
) -> dict[str, Any]:
    return {
        "shape": [
            {"lat": point.latitude, "lon": point.longitude, "type": "via"}
            for point in geometry
        ],
        "shape_match": "map_snap",
        "costing": "auto",
        "search_radius": search_radius_meters,
        "gps_accuracy": gps_accuracy_meters,
        "filters": {
            "attributes": [
                "confidence_score",
                "edge.id",
                "edge.length",
                "edge.way_id",
                "matched.type",
                "matched.distance_from_trace_point",
                "matched.begin_route_discontinuity",
                "matched.end_route_discontinuity",
            ],
            "action": "include",
        },
    }


def _parse_trace_match(
    payload: object,
    *,
    segment: TrafficFlowSegment,
    tileset_identity: str,
    search_radius_meters: float,
    expected_tileset_configured: bool,
    openlr_direction_match: bool | None,
    openlr_direction_warning: str | None,
    openlr_decoded: bool,
) -> TrafficEdgeMatch:
    if not isinstance(payload, Mapping):
        raise TypeError("trace payload must be an object")
    edges = payload.get("edges")
    if not isinstance(edges, list) or not edges:
        return _unmatched("Valhalla trace matched no edges", tileset_identity=tileset_identity)

    directed_edge_ids: list[str] = []
    matched_length_meters = 0.0
    matched_way_ids: list[int] = []
    for item in edges:
        if not isinstance(item, Mapping):
            raise TypeError("trace edge must be an object")
        graph_id = graph_id_to_string(item.get("id"))
        if graph_id is None:
            raise ValueError("trace edge has an invalid GraphId")
        if not directed_edge_ids or directed_edge_ids[-1] != graph_id:
            directed_edge_ids.append(graph_id)
        length_km = _finite_number(item.get("length"))
        begin = _finite_number(item.get("source_percent_along"), default=0.0)
        end = _finite_number(item.get("target_percent_along"), default=1.0)
        if length_km is None or begin is None or end is None or not 0 <= begin <= end <= 1:
            raise ValueError("trace edge length is invalid")
        matched_length_meters += length_km * 1000 * (end - begin)
        way_id = item.get("way_id")
        if isinstance(way_id, int) and not isinstance(way_id, bool) and way_id > 0:
            matched_way_ids.append(way_id)

    raw_confidence = _finite_number(payload.get("confidence_score"))
    if raw_confidence is None:
        return _unmatched(
            "Valhalla trace response omitted confidence_score",
            tileset_identity=tileset_identity,
        )
    valhalla_confidence = max(0.0, min(1.0, raw_confidence))
    source_length_meters = _geometry_length_meters(segment.geometry or ())
    length_quality = _length_quality(matched_length_meters, source_length_meters)
    point_quality, has_discontinuity = _matched_point_quality(
        payload.get("matched_points"), search_radius_meters=search_radius_meters
    )
    confidence = min(valhalla_confidence, length_quality, point_quality)

    warnings: list[str] = []
    if segment.openlr and not openlr_decoded:
        warnings.append(
            "OpenLR was not decoded; provider geometry was map-matched by Valhalla"
        )
    if openlr_direction_warning:
        warnings.append(openlr_direction_warning)
    if not expected_tileset_configured:
        warnings.append(
            "configured tileset identity is empty; this match is diagnostic-only"
        )
    if has_discontinuity:
        confidence = 0.0
        warnings.append("Valhalla reported a route discontinuity")
    hinted_way_ids = {reference.way_id for reference in segment.osm_way_ids}
    if hinted_way_ids:
        agreement = sum(way_id in hinted_way_ids for way_id in matched_way_ids) / max(
            1, len(matched_way_ids)
        )
        confidence = min(confidence, agreement)
        if agreement < 1:
            warnings.append(
                f"OSM way hint agreement is {agreement:.3f}; hints are validation only"
            )

    direction_match = openlr_direction_match
    if direction_match is None and segment.direction in {"forward", "backward"}:
        direction_match = not has_discontinuity
    elif direction_match is None:
        warnings.append(
            "provider direction is not explicit; direction was not independently verified"
        )
    if direction_match is False:
        confidence = 0.0

    return TrafficEdgeMatch(
        directed_edge_ids=tuple(directed_edge_ids),
        match_method="geometry_trace",
        confidence=confidence,
        matched_length_meters=matched_length_meters,
        source_length_meters=source_length_meters,
        direction_match=direction_match,
        valhalla_tileset_version=tileset_identity,
        mapping_version=(
            OPENLR_GEOMETRY_MATCHING_VERSION
            if openlr_decoded
            else GEOMETRY_MATCHING_VERSION
        ),
        warnings=tuple(warnings),
    )


def _matched_point_quality(
    value: object, *, search_radius_meters: float
) -> tuple[float, bool]:
    if not isinstance(value, list) or not value:
        return 1.0, False
    maximum_distance = 0.0
    has_discontinuity = False
    for item in value:
        if not isinstance(item, Mapping):
            raise TypeError("matched point must be an object")
        if item.get("type") == "unmatched":
            return 0.0, True
        distance = _finite_number(item.get("distance_from_trace_point"), default=0.0)
        if distance is None or distance < 0:
            raise ValueError("matched point distance is invalid")
        maximum_distance = max(maximum_distance, distance)
        has_discontinuity = has_discontinuity or bool(
            item.get("begin_route_discontinuity", False)
            or item.get("end_route_discontinuity", False)
        )
    return max(0.0, 1 - maximum_distance / search_radius_meters), has_discontinuity


def _geometry_length_meters(geometry: tuple[Coordinate, ...]) -> float:
    return sum(
        _haversine_meters(start, end)
        for start, end in zip(geometry, geometry[1:], strict=False)
    )


def _haversine_meters(start: Coordinate, end: Coordinate) -> float:
    earth_radius_meters = 6_371_008.8
    latitude_delta = radians(end.latitude - start.latitude)
    longitude_delta = radians(end.longitude - start.longitude)
    start_latitude = radians(start.latitude)
    end_latitude = radians(end.latitude)
    value = sin(latitude_delta / 2) ** 2 + (
        cos(start_latitude) * cos(end_latitude) * sin(longitude_delta / 2) ** 2
    )
    return 2 * earth_radius_meters * asin(sqrt(min(1.0, value)))


def _length_quality(matched: float, source: float) -> float:
    if matched <= 0 or source <= 0:
        return 0.0
    return min(matched, source) / max(matched, source)


def _finite_number(value: object, *, default: float | None = None) -> float | None:
    if value is None:
        return default
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    result = float(value)
    return result if isfinite(result) else None


def _unmatched(
    warning: str, *, tileset_identity: str | None = None
) -> TrafficEdgeMatch:
    return TrafficEdgeMatch(
        directed_edge_ids=(),
        match_method="unmatched",
        confidence=0.0,
        valhalla_tileset_version=tileset_identity,
        mapping_version=GEOMETRY_MATCHING_VERSION,
        warnings=(warning,),
    )
