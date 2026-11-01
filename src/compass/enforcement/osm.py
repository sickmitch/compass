"""Conservative OSM enforcement inventory. Proximity is never proof of carriageway."""
import json
import math
import re


def query(country: str) -> str:
    if not re.fullmatch(r"[A-Z]{2}", country):
        raise ValueError("Expected ISO 3166-1 alpha-2 country")
    return (
        '[out:json][timeout:180];'
        f'area["ISO3166-1"="{country}"]["admin_level"="2"]->.country;'
        '(node(area.country)["highway"="speed_camera"];'
        'rel(area.country)["type"="enforcement"]["enforcement"~"^(maxspeed|average_speed)$"];);'
        'out body; >; out body;'
    )


def number(value: object, maximum: float) -> float | None:
    try:
        result = float(str(value))
    except ValueError:
        return None
    return result if math.isfinite(result) and 0 <= result <= maximum else None


def parse(content: bytes) -> list[dict]:
    payload = json.loads(content)
    if payload.get("remark") or not isinstance(payload.get("elements"), list):
        raise ValueError("Incomplete or invalid Overpass response")
    elements = {(e["type"], e["id"]): e for e in payload["elements"]}
    result = []
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
        if not points:
            raise ValueError(f"Enforcement {kind}/{osm_id} has no resolved location")
        lat, lon = float(points[0]["lat"]), float(points[0]["lon"])
        if not (math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90
                and -180 <= lon <= 180):
            raise ValueError("Invalid enforcement coordinate")
        direction = number(tags.get("direction"), 360)
        speed = number(tags.get("maxspeed"), 300)
        result.append({
            "source_key": f"osm:{kind}:{osm_id}",
            "kind": "average_speed" if tags.get("enforcement") == "average_speed" else "fixed",
            "latitude": lat, "longitude": lon,
            "direction_degrees": direction % 360 if direction is not None else None,
            "speed_limit_kph": speed if speed and speed > 0 else None,
            "raw": dict(element, resolved_members=resolved),
        })
    return sorted(result, key=lambda item: item["source_key"])
