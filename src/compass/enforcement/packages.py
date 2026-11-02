"""Bounded, versioned control snapshots; road association is not a driving alert."""
from datetime import UTC, datetime
from typing import Literal

from pydantic import Field, ValidationError
from sqlalchemy import text
from sqlalchemy.orm import Session

from compass.api.contracts import StrictModel
from compass.candidates.geometry import decode_polyline6
from compass.enforcement.identity import physical_control_key
from compass.routing.domain import RoutingProviderError

MAX_CONTROLS = 200
MAX_BYTES = 2_000_000


class DirectedControlPath(StrictModel):
    osm_way_id: str = Field(pattern=r"^[1-9][0-9]*$")
    forward: bool
    from_node: str = Field(pattern=r"^[1-9][0-9]*$")
    to_node: str = Field(pattern=r"^[1-9][0-9]*$")
    edge_ids: list[str] = Field(min_length=1, max_length=512)
    encoded_polyline: str = Field(min_length=1, max_length=50_000)
    length_meters: float = Field(gt=0, le=50_000, allow_inf_nan=False)


class PackageControl(StrictModel):
    source_key: str
    physical_control_key: str
    source_import_id: str
    kind: Literal["fixed", "average_speed"]
    latitude: float = Field(ge=-90, le=90, allow_inf_nan=False)
    longitude: float = Field(ge=-180, le=180, allow_inf_nan=False)
    # Source-facing camera orientation is NOT vehicle travel direction.
    direction_degrees: float | None
    speed_limit_kph: float | None
    fetched_at: datetime
    association_state: Literal[
        "matched", "ambiguous", "unmatched", "unknown_direction", "stale",
        "missing_source_geometry", "invalid_evidence",
    ]
    association_reason: str
    directed_path: DirectedControlPath | None = None
    alert_eligible: Literal[False] = False


class ControlPackage(StrictModel):
    schema_version: Literal[1] = 1
    graph_identity: str
    generated_at: datetime
    latitude: float
    longitude: float
    radius_meters: int
    spatial_basis: Literal["control_location"] = "control_location"
    controls: list[PackageControl]
    truncated: bool
    attribution: str = "© OpenStreetMap contributors — ODbL 1.0"


def directed_path(evidence: dict) -> DirectedControlPath:
    # Reject malformed persisted proofs instead of letting Pydantic coerce IDs/bools.
    for key in ("osm_way_id", "from_node", "to_node"):
        if type(evidence.get(key)) is not int or evidence[key] <= 0:
            raise ValueError("Invalid source ID")
    if type(evidence.get("length_meters")) not in (int, float):
        raise ValueError("Invalid network length")
    if type(evidence.get("forward")) is not bool:
        raise ValueError("Invalid source direction")
    ids = evidence.get("edge_ids")
    if not isinstance(ids, list) or any(
        not isinstance(value, str) or not value.isascii() or not value.isdecimal() for value in ids
    ):
        raise ValueError("Invalid directed edge IDs")
    path = DirectedControlPath(
        **{key: str(evidence[key]) for key in ("osm_way_id", "from_node", "to_node")},
        **{key: evidence[key] for key in (
            "forward", "edge_ids", "encoded_polyline", "length_meters",
        )},
    )
    geometry = decode_polyline6(path.encoded_polyline, max_points=5000)
    if len(geometry) < 2 or path.from_node == path.to_node:
        raise ValueError("Invalid directed shape")
    return path


def package_control(row, identity: str) -> PackageControl:
    state, reason, path = "unmatched", "not_processed", None
    raw = row["raw"]
    if raw.get("type") == "relation" and "road_ways" not in raw:
        state, reason = "missing_source_geometry", "enriched_import_required"
    elif row["association_graph"] is not None:
        if row["association_graph"] != identity or row["association_import"] != row["import_id"]:
            state, reason = "stale", "graph_or_source_changed"
        elif row["state"] == "matched":
            try:
                path = directed_path(row["evidence"])
                state, reason = "matched", row["reason"]
            except (ValidationError, ValueError, KeyError, TypeError, RoutingProviderError):
                state, reason = "invalid_evidence", "invalid_persisted_association"
        elif row["state"] in {"ambiguous", "unmatched", "unknown_direction"}:
            state, reason = row["state"], row["reason"]
        else:
            state, reason = "invalid_evidence", "unknown_association_state"
    return PackageControl(
        **{key: row[key] for key in (
            "source_key", "kind", "latitude", "longitude", "direction_degrees",
            "speed_limit_kph", "fetched_at",
        )},
        physical_control_key=physical_control_key(row["source_key"], row["kind"], raw),
        source_import_id=str(row["import_id"]), association_state=state,
        association_reason=reason, directed_path=path,
    )


def read_package(session: Session, identity: str, latitude: float, longitude: float,
                 radius: int) -> ControlPackage:
    # One spatial query, not one association request per nearby control. IDs remain source-specific.
    rows = session.execute(text("""
        SELECT c.source_key,c.kind,c.import_id,c.fetched_at,c.raw,
               ST_Y(c.location::geometry) AS latitude,ST_X(c.location::geometry) AS longitude,
               c.direction_degrees,c.speed_limit_kph,
               a.import_id AS association_import,a.graph_identity AS association_graph,
               a.state,a.reason,a.evidence
        FROM enforcement_controls c
        LEFT JOIN enforcement_associations a ON a.source_key=c.source_key
        WHERE c.active AND ST_DWithin(c.location,
            ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,:radius)
        ORDER BY ST_Distance(c.location,
            ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography),c.source_key
        LIMIT :limit
    """), {"lat": latitude, "lon": longitude, "radius": radius,
           "limit": MAX_CONTROLS + 1}).mappings().all()
    package = ControlPackage(graph_identity=identity, generated_at=datetime.now(UTC),
                             latitude=latitude, longitude=longitude, radius_meters=radius,
                             controls=[], truncated=len(rows) > MAX_CONTROLS)
    size = len(package.model_dump_json().encode())
    for row in rows[:MAX_CONTROLS]:
        control = package_control(row, identity)
        additional = len(control.model_dump_json().encode()) + 1
        if size + additional > MAX_BYTES:
            package.truncated = True
            break
        package.controls.append(control)
        size += additional
    return package
