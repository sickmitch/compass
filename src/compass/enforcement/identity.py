"""Semantic aliasing only: never infer shared devices from spatial proximity."""


def physical_control_key(source_key: str, kind: str, raw: dict) -> str:
    if kind != "fixed" or raw.get("type") != "relation":
        return source_key
    devices = {
        f"osm:node:{member['ref']}"
        for member in raw.get("members", [])
        if member.get("role") == "device" and member.get("type") == "node"
        and type(member.get("ref")) is int and member["ref"] > 0
    }
    return next(iter(devices)) if len(devices) == 1 else source_key
