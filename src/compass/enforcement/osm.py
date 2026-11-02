"""Conservative OSM enforcement inventory. Proximity is never proof of carriageway."""
import json
import math
import re
from dataclasses import dataclass


def query(country: str, *, include_roads: bool = True) -> str:
    if not re.fullmatch(r"[A-Z]{2}", country):
        raise ValueError("Expected ISO 3166-1 alpha-2 country")
    base = (
        '[out:json][timeout:180];'
        f'area["ISO3166-1"="{country}"]["admin_level"="2"]->.country;'
        '(node(area.country)["highway"="speed_camera"];'
        'rel(area.country)["type"="enforcement"]["enforcement"~"^(maxspeed|average_speed)$"];)'
    )
    if not include_roads:
        return base + ";out body; >; out body;"
    return base + (
        '->.controls; .controls > ->.members;'
        '(.controls;.members;way(bn.members)["highway"];);'
        'out body; >; out body;'
    )


def roads_query(node_ids: list[int]) -> str:
    if not node_ids or len(node_ids) > 200 or any(type(i) is not int or i <= 0 for i in node_ids):
        raise ValueError("Expected 1–200 positive node IDs")
    ids = ",".join(str(i) for i in node_ids)
    return (
        f'[out:json][timeout:90];node(id:{ids});'
        'way(bn)["highway"];out body; >; out body;'
    )


def number(value: object, maximum: float) -> float | None:
    try:
        result = float(str(value))
    except ValueError:
        return None
    return result if math.isfinite(result) and 0 <= result <= maximum else None


@dataclass(frozen=True)
class ParseReport:
    controls: list[dict]
    rejected: list[dict]


def parse(content: bytes) -> list[dict]:
    return parse_report(content).controls


def _invalid_json_constant(value: str):
    raise ValueError(f"Invalid JSON numeric constant: {value}")


def parse_report(content: bytes) -> ParseReport:
    payload = json.loads(content, parse_constant=_invalid_json_constant)
    if payload.get("remark") or not isinstance(payload.get("elements"), list):
        raise ValueError("Incomplete or invalid Overpass response")
    elements = {(e["type"], e["id"]): e for e in payload["elements"]}
    roads_by_node = {}
    for (element_type, _), way in elements.items():
        if element_type == "way" and way.get("tags", {}).get("highway"):
            nodes = way.get("nodes", [])
            geometry = [elements.get(("node", ref)) for ref in nodes]
            road = {"id": way["id"], "nodes": nodes, "geometry": [
                {"lat": node.get("lat"), "lon": node.get("lon")} if node else None
                for node in geometry
            ]}
            for ref in set(nodes):
                roads_by_node.setdefault(ref, []).append(road)
    result = []
    rejected = []
    for (kind, osm_id), element in elements.items():
        tags = element.get("tags", {})
        is_node = kind == "node" and tags.get("highway") == "speed_camera"
        is_relation = (
            kind == "relation" and tags.get("type") == "enforcement"
            and tags.get("enforcement") in {"maxspeed", "average_speed"}
        )
        if not (is_node or is_relation):
            continue
        if any(tags.get(key) == "yes" for key in ("disused", "abandoned", "demolished")):
            continue
        members = element.get("members", [])
        # Keep complete membership for future directed graph association. A member
        # is not removed merely because it also occurs in another enforcement relation.
        resolved = [
            dict(member, element=elements.get((member["type"], member["ref"])))
            for member in members
        ]
        points = [element] if is_node else [
            item["element"] for item in resolved
            if item["role"] in {"device", "from", "to"} and item["element"] is not None
        ]
        points = [p for p in points if "lat" in p and "lon" in p]
        anchor_ids = {osm_id} if is_node else {
            member["ref"] for member in members if member["type"] == "node"
        }
        roads = {road["id"]: road for ref in anchor_ids for road in roads_by_node.get(ref, [])}
        raw = dict(element, resolved_members=resolved, road_ways=list(roads.values()))
        reason = None
        if not points:
            reason = "unresolved_location"
        else:
            try:
                lat, lon = float(points[0]["lat"]), float(points[0]["lon"])
                if not (math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90
                        and -180 <= lon <= 180):
                    reason = "invalid_coordinate"
            except (ValueError, TypeError):
                reason = "invalid_coordinate"
        if reason:
            rejected.append({
                "source_key": f"osm:{kind}:{osm_id}", "reason": reason, "raw": raw,
            })
            continue
        direction = number(tags.get("direction"), 360)
        speed = number(tags.get("maxspeed"), 300)
        result.append({
            "source_key": f"osm:{kind}:{osm_id}",
            "kind": "average_speed" if tags.get("enforcement") == "average_speed" else "fixed",
            "latitude": lat, "longitude": lon,
            "direction_degrees": direction % 360 if direction is not None else None,
            "speed_limit_kph": speed if speed and speed > 0 else None,
            "raw": raw,
        })
    return ParseReport(
        sorted(result, key=lambda item: item["source_key"]),
        sorted(rejected, key=lambda item: item["source_key"]),
    )
