"""Bounded road-context matching, not a certificate of camera/carriageway association."""
import math
from typing import Literal, Self

import httpx
from pydantic import BaseModel, ConfigDict, Field, model_validator

from compass.api.contracts import StrictModel
from compass.config import Settings


class TracePoint(StrictModel):
    latitude: float = Field(ge=-90, le=90, allow_inf_nan=False)
    longitude: float = Field(ge=-180, le=180, allow_inf_nan=False)
    elapsed_seconds: float = Field(ge=0, le=60, allow_inf_nan=False)
    accuracy_meters: float = Field(gt=0, le=100, allow_inf_nan=False)


def distance(a: TracePoint, b: TracePoint) -> float:
    lat1, lat2 = math.radians(a.latitude), math.radians(b.latitude)
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(
        math.radians(b.longitude - a.longitude) / 2
    ) ** 2
    return 12_742_000 * math.asin(math.sqrt(min(1, h)))


class RoadContextRequest(StrictModel):
    points: list[TracePoint] = Field(min_length=3, max_length=12)

    @model_validator(mode="after")
    def continuous(self) -> Self:
        for first, second in zip(self.points, self.points[1:], strict=False):
            seconds = second.elapsed_seconds - first.elapsed_seconds
            if not 0 < seconds <= 10:
                raise ValueError("Trace times must increase with gaps no greater than 10 seconds")
            maximum = seconds * 70 + first.accuracy_meters + second.accuracy_meters
            if distance(first, second) > maximum:
                raise ValueError("Trace contains an implausible GPS jump")
        return self


class RoadEdge(StrictModel):
    # Valhalla IDs are graph-version-specific and must not be persisted without a graph identity.
    edge_id: str
    osm_way_id: str
    forward: bool | None
    begin_heading: float
    end_heading: float
    speed_limit_kph: float | None


class RoadContext(StrictModel):
    state: Literal["candidate", "uncertain", "insufficient_movement"]
    reason: str
    edges: list[RoadEdge] = Field(default_factory=list)
    current_edge_index: int | None = None
    latitude: float | None = None
    longitude: float | None = None
    distance_from_trace_meters: float | None = None
    alert_eligible: Literal[False] = False
    persistable: Literal[False] = False


class _MatchedPoint(BaseModel):
    model_config = ConfigDict(extra="ignore")
    lat: float = Field(ge=-90, le=90, allow_inf_nan=False)
    lon: float = Field(ge=-180, le=180, allow_inf_nan=False)
    type: Literal["matched", "interpolated", "unmatched"]
    edge_index: int | None = Field(default=None, ge=0)
    distance_from_trace_point: float | None = Field(default=None, ge=0, allow_inf_nan=False)
    begin_route_discontinuity: bool = False
    end_route_discontinuity: bool = False


class RoadContextUnavailable(Exception):
    pass


def decode_context(payload: dict, request: RoadContextRequest) -> RoadContext:
    points = payload.get("matched_points", [])
    edges = payload.get("edges", [])
    if not isinstance(points, list) or not isinstance(edges, list):
        raise ValueError("Invalid matching arrays")
    if any(not isinstance(item, dict) for item in points + edges):
        raise ValueError("Invalid matching records")
    if len(points) != len(request.points) or not edges or len(edges) > 512:
        return RoadContext(state="uncertain", reason="incomplete_match")
    if any(point.get("type") == "unmatched" for point in points):
        return RoadContext(state="uncertain", reason="unmatched_gps_points")
    parsed = [_MatchedPoint.model_validate(point) for point in points]
    if any(
        point.type != "matched" or point.edge_index is None
        or point.edge_index >= len(edges)
        or point.begin_route_discontinuity or point.end_route_discontinuity
        or point.distance_from_trace_point is None
        or point.distance_from_trace_point > min(25, sample.accuracy_meters)
        for point, sample in zip(parsed, request.points, strict=True)
    ):
        return RoadContext(state="uncertain", reason="unreliable_or_discontinuous_match")
    # A plausible best match alone cannot establish unique carriageway identity.
    result = []
    for edge in edges:
        edge_id, way_id = edge.get("id"), edge.get("way_id")
        if (type(edge_id) is not int or edge_id < 0
                or type(way_id) is not int or way_id <= 0):
            return RoadContext(state="uncertain", reason="missing_edge_identity")
        heading = [edge.get("begin_heading"), edge.get("end_heading")]
        if any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v < 360
               for v in heading):
            return RoadContext(state="uncertain", reason="missing_edge_direction")
        speed = edge.get("speed_limit")
        speed = speed if type(speed) in (float, int) and 0 < speed <= 300 else None
        result.append(RoadEdge(
            edge_id=str(edge_id), osm_way_id=str(way_id),
            forward=edge.get("forward") if type(edge.get("forward")) is bool else None,
            begin_heading=heading[0], end_heading=heading[1], speed_limit_kph=speed,
        ))
    last = parsed[-1]
    return RoadContext(
        state="candidate", reason="carriageway_uniqueness_not_verified",
        edges=result, current_edge_index=last.edge_index, latitude=last.lat, longitude=last.lon,
        distance_from_trace_meters=last.distance_from_trace_point,
    )


async def match_context(
    request: RoadContextRequest, client: httpx.AsyncClient, settings: Settings,
) -> RoadContext:
    if any(point.accuracy_meters > 25 for point in request.points):
        return RoadContext(state="uncertain", reason="poor_gps_accuracy")
    if distance(request.points[0], request.points[-1]) < 20:
        return RoadContext(state="insufficient_movement", reason="heading_not_observable")
    payload = {
        "shape": [{"lat": p.latitude, "lon": p.longitude} for p in request.points],
        "shape_match": "map_snap", "costing": "auto", "units": "kilometers",
        "trace_options": {
            "gps_accuracy": max(p.accuracy_meters for p in request.points),
            "search_radius": 40, "breakage_distance": 1000,
        },
        "filters": {"action": "include", "attributes": [
            "edge.id", "edge.way_id", "edge.forward", "edge.begin_heading", "edge.end_heading",
            "edge.speed_limit", "matched.point", "matched.type", "matched.edge_index",
            "matched.distance_from_trace_point", "matched.begin_route_discontinuity",
            "matched.end_route_discontinuity",
        ]},
    }
    try:
        response = await client.post(
            settings.valhalla_url.rstrip("/") + "/trace_attributes", json=payload,
            timeout=httpx.Timeout(10, connect=min(settings.valhalla_connect_timeout_seconds, 5)),
            headers={"User-Agent": settings.http_user_agent},
        )
        response.raise_for_status()
        data = response.json()
        if not isinstance(data, dict):
            raise ValueError("Invalid map matching response")
        return decode_context(data, request)
    except (httpx.HTTPError, ValueError, TypeError, KeyError) as error:
        raise RoadContextUnavailable("Road context unavailable") from error
