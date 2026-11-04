"""Vehicle approach checks using directed source evidence and competing Valhalla roads."""
import math
from datetime import UTC, datetime
from typing import Literal

import httpx
from pydantic import Field
from sqlalchemy import text
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool

from compass.api.contracts import StrictModel
from compass.candidates.geometry import decode_polyline6, encode_polyline6
from compass.config import Settings
from compass.enforcement.association import graph_identity
from compass.enforcement.identity import physical_control_key
from compass.enforcement.packages import directed_path
from compass.enforcement.road_context import RoadContextRequest, distance, match_context
from compass.routing.domain import Coordinate, RoutingProviderError


class ApproachAlert(StrictModel):
    source_key: str
    physical_control_key: str
    kind: Literal["fixed", "average_speed"]
    osm_way_id: str
    forward: bool
    speed_limit_kph: float | None
    distance_meters: float = Field(gt=0, le=1200)
    # Actual road shape from the matched fix to the control, never a straight-line connector.
    encoded_polyline: str
    target_latitude: float
    target_longitude: float


class ApproachResult(StrictModel):
    state: Literal["approach", "none", "uncertain"]
    reason: str
    graph_identity: str | None = None
    observed_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    alert: ApproachAlert | None = None


def heading(a, b):
    y = math.sin(math.radians(b.longitude - a.longitude)) * math.cos(math.radians(b.latitude))
    x = (math.cos(math.radians(a.latitude)) * math.sin(math.radians(b.latitude)) -
         math.sin(math.radians(a.latitude)) * math.cos(math.radians(b.latitude)) *
         math.cos(math.radians(b.longitude - a.longitude)))
    return math.degrees(math.atan2(y, x)) % 360


def angle(a, b):
    return abs((a - b + 180) % 360 - 180)


def project(point, shape):
    """Nearest segment, offset, cumulative distance, projected point and segment heading."""
    best = None
    along = 0.0
    for i, (a, b) in enumerate(zip(shape, shape[1:], strict=False)):
        scale = math.cos(math.radians(point.latitude))
        dx, dy = (b.longitude - a.longitude) * scale, b.latitude - a.latitude
        length = math.hypot(dx, dy) * 111_320
        if length == 0:
            continue
        px, py = (point.longitude - a.longitude) * scale, point.latitude - a.latitude
        fraction = max(0., min(1., (px * dx + py * dy) / (dx * dx + dy * dy)))
        offset = math.hypot(px - fraction * dx, py - fraction * dy) * 111_320
        snapped = Coordinate(a.latitude + fraction * dy,
                             a.longitude + fraction * (b.longitude - a.longitude))
        candidate = (offset, i, along + fraction * length, snapped, heading(a, b))
        if best is None or offset < best[0]:
            best = candidate
        along += length
    if best is None:
        raise ValueError("Empty road geometry")
    return best


def unique_road(payload, point, bearing, expected_edge):
    """All plausible heading-compatible roads in the reported GPS error envelope must agree."""
    if not isinstance(payload, list) or len(payload) != 1 or not isinstance(payload[0], dict):
        return False
    if payload[0].get("warnings"):
        return False
    edges = payload[0].get("edges")
    if not isinstance(edges, list) or not 1 <= len(edges) <= 128:
        return False
    viable = set()
    expected_present = False
    for item in edges:
        try:
            info, edge = item["edge_info"], item["edge"]
            if not isinstance(info, dict) or not isinstance(edge, dict):
                return False
            if type(edge.get("forward")) is not bool or type(info.get("way_id")) is not int:
                return False
            shape = decode_polyline6(info["shape"], max_points=5000)
            if not edge["forward"]:
                shape = tuple(reversed(shape))
            projection = project(point, shape)
            if projection[0] <= max(10, 2 * point.accuracy_meters) and angle(
                projection[4], bearing,
            ) <= 35:
                viable.add((str(info["way_id"]), edge["forward"]))
                if str(item["edge_id"]["value"]) == expected_edge.edge_id:
                    expected_present = True
        except (KeyError, TypeError, ValueError, RoutingProviderError):
            return False
    return expected_present and viable == {(expected_edge.osm_way_id, expected_edge.forward)}


def read_controls(session: Session, identity, point, way_id, forward):
    return session.execute(text("""
        SELECT c.source_key,c.kind,c.raw,c.speed_limit_kph,a.evidence,
               ST_Y(c.location::geometry) AS latitude,ST_X(c.location::geometry) AS longitude
        FROM enforcement_controls c JOIN enforcement_associations a USING(source_key)
        WHERE c.active AND a.state='matched' AND a.import_id=c.import_id
          AND a.graph_identity=:graph AND a.evidence->>'osm_way_id'=:way
          AND a.evidence->>'forward'=:forward
          AND ST_DWithin(c.location,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,1500)
        ORDER BY c.source_key LIMIT 101
    """), {"graph": identity, "way": way_id, "forward": str(forward).lower(),
           "lat": point.latitude, "lon": point.longitude}).mappings().all()


def candidate(row, point, bearing):
    proof = directed_path(row["evidence"])
    roads = [way for way in row["raw"].get("road_ways", [])
             if str(way["id"]) == proof.osm_way_id]
    if len(roads) != 1:
        return None
    way = roads[0]
    nodes, geometry = way["nodes"], way["geometry"]
    # Looped/self-repeating ways cannot establish an unambiguous approach chain.
    if not isinstance(nodes, list) or len(set(nodes)) != len(nodes):
        return None
    if len(nodes) != len(geometry) or not 2 <= len(nodes) <= 5000:
        return None
    if any(not isinstance(p, dict) or any(
        type(p.get(k)) not in (int, float) or not math.isfinite(p[k]) or abs(p[k]) > limit
        for k, limit in (("lat", 90), ("lon", 180))
    ) for p in geometry):
        return None
    shape = [Coordinate(p["lat"], p["lon"]) for p in geometry]
    if not proof.forward:
        shape.reverse()
        nodes = list(reversed(nodes))
    start, end = int(proof.from_node), int(proof.to_node)
    if nodes.count(start) != 1 or nodes.count(end) != 1:
        return None
    a, b = nodes.index(start), nodes.index(end)
    if a >= b:
        return None
    zone = shape[a:b + 1]
    if row["kind"] == "average_speed":
        target = zone[0]
    else:
        on_zone = project(Coordinate(row["latitude"], row["longitude"]), zone)
        if on_zone[0] > 50:
            return None
        target = on_zone[3]
    current = project(point, shape)
    destination = project(target, shape)
    remaining = destination[2] - current[2]
    if (current[0] > min(10, point.accuracy_meters) or angle(current[4], bearing) > 25
            or not 5 < remaining <= 1200 or current[1] > destination[1]):
        return None
    corridor = [current[3], *shape[current[1] + 1:destination[1] + 1], target]
    if not 2 <= len(corridor) <= 512:
        return None
    return ApproachAlert(
        source_key=row["source_key"],
        physical_control_key=physical_control_key(row["source_key"], row["kind"], row["raw"]),
        kind=row["kind"], osm_way_id=proof.osm_way_id, forward=proof.forward,
        speed_limit_kph=row["speed_limit_kph"], distance_meters=remaining,
        encoded_polyline=encode_polyline6(tuple(corridor)),
        target_latitude=target.latitude, target_longitude=target.longitude,
    )


async def check_approach(body: RoadContextRequest, client: httpx.AsyncClient,
                         settings: Settings, session: Session) -> ApproachResult:
    last = body.points[-1]
    # Stationary fixes, poor accuracy and unobservable heading cannot authorize a warning.
    if any(p.accuracy_meters > 12 for p in body.points) or distance(body.points[0], last) < 20:
        return ApproachResult(state="uncertain", reason="insufficient_gps_evidence")
    bearing = heading(body.points[-2], last)
    identity = await graph_identity(client, settings)
    context = await match_context(body, client, settings)
    if context.state != "candidate" or context.current_edge_index is None:
        return ApproachResult(state="uncertain", reason=context.reason)
    edge = context.edges[context.current_edge_index]
    if edge.forward is None:
        return ApproachResult(state="uncertain", reason="unknown_road_direction")
    response = await client.post(settings.valhalla_url.rstrip("/") + "/locate", json={
        "locations": [{"lat": last.latitude, "lon": last.longitude,
                       "radius": 50, "search_cutoff": 50}],
        "costing": "auto", "verbose": True,
    }, timeout=5)
    response.raise_for_status()
    if not unique_road(response.json(), last, bearing, edge):
        return ApproachResult(state="uncertain", reason="competing_or_unresolved_roads")
    rows = await run_in_threadpool(read_controls, session, identity, last,
                                   edge.osm_way_id, edge.forward)
    if len(rows) > 100:
        return ApproachResult(state="uncertain", reason="too_many_controls")
    alerts = []
    for row in rows:
        try:
            result = candidate(row, last, bearing)
            if result:
                alerts.append(result)
        except (KeyError, ValueError, TypeError, RoutingProviderError):
            continue
    if identity != await graph_identity(client, settings):
        return ApproachResult(state="uncertain", reason="graph_changed")
    alert = min(alerts, key=lambda a: a.distance_meters) if alerts else None
    if alert:
        limits = {a.speed_limit_kph for a in alerts
                  if a.physical_control_key == alert.physical_control_key
                  and a.kind == alert.kind and a.speed_limit_kph is not None}
        if len(limits) > 1:
            alert = alert.model_copy(update={"speed_limit_kph": None})
    return ApproachResult(state="approach" if alert else "none", graph_identity=identity,
                          reason="directed_approach" if alert else "no_verified_control_ahead",
                          alert=alert)
