"""Match explicit OSM from/to topology, never camera-facing direction, to Valhalla."""
import math
from dataclasses import asdict, dataclass

import httpx

from compass.candidates.geometry import decode_polyline6
from compass.config import Settings
from compass.routing.domain import RoutingProviderError


@dataclass
class Association:
    state: str
    reason: str
    evidence: dict

    def as_dict(self):
        return asdict(self)


def source_path(raw: dict) -> Association:
    if raw.get("type") != "relation":
        return Association("unknown_direction", "explicit_from_to_required", {})
    members = raw.get("members", [])
    endpoints = [
        {m["ref"] for m in members if m.get("role") == role and m.get("type") == "node"}
        for role in ("from", "to")
    ]
    if any(len(ids) != 1 for ids in endpoints):
        return Association("ambiguous", "single_explicit_from_to_required", {})
    start, end = next(iter(endpoints[0])), next(iter(endpoints[1]))
    candidates = []
    for way in raw.get("road_ways", []):
        nodes = way["nodes"]
        if start == end or nodes.count(start) != 1 or nodes.count(end) != 1:
            continue
        a, b = nodes.index(start), nodes.index(end)
        step = 1 if b > a else -1
        geometry = way["geometry"]
        if len(geometry) != len(nodes):
            continue
        shape = [geometry[i] for i in range(a, b + step, step)]
        if not 2 <= len(shape) <= 512:
            continue
        if any(not isinstance(p, dict) or
               any(type(p.get(k)) not in (float, int) or not math.isfinite(p[k])
                   or abs(p[k]) > bound for k, bound in (("lat", 90), ("lon", 180)))
               for p in shape):
            continue
        candidates.append({"osm_way_id": way["id"], "forward": step == 1, "shape": shape,
                           "from_node": start, "to_node": end})
    if len(candidates) != 1:
        return Association("ambiguous", "unique_shared_osm_way_required", {})
    return Association("source_path", "explicit_from_to", candidates[0])


async def graph_identity(client: httpx.AsyncClient, settings: Settings) -> str:
    response = await client.get(settings.valhalla_url.rstrip("/") + "/status", timeout=5)
    response.raise_for_status()
    data = response.json()
    version, modified = data.get("version"), data.get("tileset_last_modified")
    if not isinstance(version, str) or not version or type(modified) is not int or modified <= 0:
        raise ValueError("Valhalla graph identity unavailable")
    return f"{version}:{modified}"


def validate_trace(data: dict, source: Association) -> Association:
    edges = data.get("edges")
    if not isinstance(edges, list) or not edges or len(edges) > 512:
        return Association("unmatched", "missing_edges", {})
    identities = []
    total = 0.0
    for edge in edges:
        if not isinstance(edge, dict):
            return Association("unmatched", "invalid_edge", {})
        if (edge.get("way_id") != source.evidence["osm_way_id"]
                or type(edge.get("forward")) is not bool
                or edge["forward"] != source.evidence["forward"]):
            return Association("ambiguous", "road_or_direction_mismatch", {})
        edge_id, length = edge.get("id"), edge.get("length")
        if (type(edge_id) is not int or edge_id < 0 or type(length) not in (float, int)
                or not math.isfinite(length) or length <= 0):
            return Association("unmatched", "invalid_edge_identity_or_length", {})
        identities.append(str(edge_id))
        total += length * 1000
    if total > 50_000:
        return Association("ambiguous", "section_too_long", {})
    if not isinstance(data.get("shape"), str) or not data["shape"]:
        return Association("unmatched", "missing_matched_shape", {})
    try:
        geometry = decode_polyline6(data["shape"], max_points=5000)
    except RoutingProviderError:
        return Association("unmatched", "invalid_matched_shape", {})
    for matched, original in zip(
        (geometry[0], geometry[-1]),
        (source.evidence["shape"][0], source.evidence["shape"][-1]), strict=True,
    ):
        # Short-distance conservative endpoint check, with longitude wrap.
        dx = ((matched.longitude - original["lon"] + 180) % 360 - 180)
        dx *= math.cos(math.radians(original["lat"]))
        dy = matched.latitude - original["lat"]
        if math.hypot(dx, dy) * 111_320 > 10:
            return Association("ambiguous", "matched_endpoint_mismatch", {})
    return Association("matched", "explicit_osm_topology_and_edge_walk", {
        **source.evidence, "edge_ids": identities,
        "length_meters": total, "encoded_polyline": data["shape"],
    })


async def associate(raw: dict, client: httpx.AsyncClient, settings: Settings) -> Association:
    source = source_path(raw)
    if source.state != "source_path":
        return source
    # edge_walk requires actual source road geometry; never falls back to a nearest-road snap.
    response = await client.post(
        settings.valhalla_url.rstrip("/") + "/trace_attributes",
        json={"shape": source.evidence["shape"], "shape_match": "edge_walk", "costing": "auto",
              "units": "kilometers", "filters": {"action": "include", "attributes": [
                  "edge.id", "edge.way_id", "edge.forward", "edge.length", "shape",
              ]}}, timeout=15,
    )
    if response.status_code == 400:
        return Association("unmatched", "source_geometry_not_walkable", {})
    response.raise_for_status()
    data = response.json()
    if not isinstance(data, dict):
        raise ValueError("Invalid association response")
    return validate_trace(data, source)
