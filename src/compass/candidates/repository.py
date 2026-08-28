from dataclasses import dataclass

from sqlalchemy import text
from sqlalchemy.orm import Session

from compass.candidates.domain import CandidateQueryResult, SpatialCandidate


@dataclass(frozen=True, slots=True)
class PostgisCandidateRepository:
    session: Session

    def within_corridor(
        self,
        *,
        route_wkt: str,
        radius_meters: float,
        limit: int,
    ) -> CandidateQueryResult:
        counts = self.session.execute(
            text(
                "SELECT "
                "count(*) FILTER (WHERE is_active) AS active_station_count, "
                "count(*) FILTER (WHERE is_active AND location IS NOT NULL) "
                "AS active_station_with_location_count "
                "FROM stations"
            )
        ).one()

        rows = self.session.execute(
            text(
                "WITH route AS ("
                "  SELECT ST_GeomFromText(:route_wkt, 4326) AS geometry"
                "), candidates AS ("
                "  SELECT s.id AS station_id, s.mimit_station_id, s.name, "
                "         s.municipality, s.province, "
                "         ST_Y(s.location::geometry) AS latitude, "
                "         ST_X(s.location::geometry) AS longitude, "
                "         ST_Distance(s.location, route.geometry::geography) "
                "           AS straight_line_distance_to_route_meters, "
                "         ST_LineLocatePoint(route.geometry, s.location::geometry) "
                "           AS route_fraction "
                "  FROM stations AS s CROSS JOIN route "
                "  WHERE s.is_active "
                "    AND s.location IS NOT NULL "
                "    AND ST_DWithin(s.location, route.geometry::geography, :radius_meters)"
                "), counted AS ("
                "  SELECT candidates.*, count(*) OVER () AS corridor_candidate_count "
                "  FROM candidates"
                ") "
                "SELECT * FROM counted "
                "ORDER BY straight_line_distance_to_route_meters, route_fraction, station_id "
                "LIMIT :candidate_limit"
            ),
            {
                "route_wkt": route_wkt,
                "radius_meters": radius_meters,
                "candidate_limit": limit,
            },
        ).all()

        corridor_count = int(rows[0].corridor_candidate_count) if rows else 0
        candidates = tuple(
            SpatialCandidate(
                station_id=int(row.station_id),
                mimit_station_id=row.mimit_station_id,
                name=row.name,
                municipality=row.municipality,
                province=row.province,
                latitude=float(row.latitude),
                longitude=float(row.longitude),
                straight_line_distance_to_route_meters=float(
                    row.straight_line_distance_to_route_meters
                ),
                route_fraction=float(row.route_fraction),
            )
            for row in rows
        )
        return CandidateQueryResult(
            active_station_count=int(counts.active_station_count),
            active_station_with_location_count=int(counts.active_station_with_location_count),
            corridor_candidate_count=corridor_count,
            candidates=candidates,
        )
