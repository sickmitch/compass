from compass.traffic.domain import TrafficEdgeMatch, TrafficFlowSegment


class UnmatchedTrafficEdgeMatcher:
    """Safe placeholder until OpenLR/Valhalla matching is implemented."""

    async def match(self, segment: TrafficFlowSegment) -> TrafficEdgeMatch:
        method = "openlr" if segment.openlr else "osm_way_hint"
        return TrafficEdgeMatch(
            directed_edge_ids=(),
            match_method="unmatched",
            confidence=0.0,
            warnings=(
                f"{method} matching is not implemented yet; segment was not applied",
            ),
        )
