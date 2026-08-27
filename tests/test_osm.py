from decimal import Decimal
from pathlib import Path

import pytest

from compass.etl.osm import (
    build_cng_query,
    cng_feature_collection_sha256,
    parse_cng_features,
)

FIXTURES = Path(__file__).parent / "fixtures"


def test_query_targets_cng_fuel_features_in_configured_country() -> None:
    query = build_cng_query("it")
    assert '["ISO3166-1"="IT"]' in query
    assert '["amenity"="fuel"]["fuel:cng"="yes"]' in query
    assert "out center tags" in query


def test_query_rejects_unsafe_area_code() -> None:
    with pytest.raises(ValueError):
        build_cng_query('IT"];out;')


def test_parse_osm_nodes_and_way_centers() -> None:
    result, observed_at = parse_cng_features((FIXTURES / "osm_cng.json").read_bytes())
    assert result.rows_seen == 3
    assert len(result.records) == 2
    assert result.records[0].osm_type == "node"
    assert result.records[0].tags["opening_hours"] == "Mo-Su 06:00-22:00"
    assert result.records[1].latitude == Decimal("44.4949")
    assert observed_at is not None
    assert observed_at.isoformat() == "2026-08-25T07:55:00+00:00"


def test_feature_collection_identity_is_order_independent() -> None:
    result, _ = parse_cng_features((FIXTURES / "osm_cng.json").read_bytes())

    assert cng_feature_collection_sha256(result.records) == cng_feature_collection_sha256(
        list(reversed(result.records))
    )
