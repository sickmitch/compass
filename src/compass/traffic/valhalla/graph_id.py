from __future__ import annotations

GRAPH_ID_LEVEL_MASK = 0x7
GRAPH_ID_TILE_MASK = ((1 << 22) - 1) << 3
GRAPH_ID_EDGE_MASK = ((1 << 21) - 1) << 25


def graph_id_to_string(value: object) -> str | None:
    if isinstance(value, str):
        if value.count("/") == 2:
            return value if _valid_graph_id_parts(value) else None
        if not value.isdigit():
            return None
        value = int(value)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    level = value & GRAPH_ID_LEVEL_MASK
    tile_id = (value & GRAPH_ID_TILE_MASK) >> 3
    edge_id = (value & GRAPH_ID_EDGE_MASK) >> 25
    return f"{level}/{tile_id}/{edge_id}"


def _valid_graph_id_parts(value: str) -> bool:
    try:
        level, tile_id, edge_id = (int(part) for part in value.split("/"))
    except ValueError:
        return False
    return 0 <= level <= 7 and tile_id >= 0 and edge_id >= 0
