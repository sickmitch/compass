import json

import pytest

from compass.enforcement.osm import parse, parse_report, query


def payload(*elements, **extra):
    return json.dumps({"elements": list(elements), **extra}).encode()


def camera(**tags):
    return {"type": "node", "id": 1, "lat": 45.0, "lon": 9.0,
            "tags": {"highway": "speed_camera", **tags}}


def test_identity_deduplicates_repeated_osm_elements_not_nearby_opposite_carriageways():
    other = dict(camera(direction="180"), id=2)
    rows = parse(payload(camera(direction="0"), camera(direction="0"), other))
    assert len(rows) == 2
    assert [row["direction_degrees"] for row in rows] == [0, 180]


@pytest.mark.parametrize("value", ["forward", "backward", "N", "both", "NaN", "-10", "500"])
def test_unresolved_direction_stays_unknown(value):
    assert parse(payload(camera(direction=value)))[0]["direction_degrees"] is None


def test_does_not_guess_special_speed_tags():
    row = parse(payload(camera(maxspeed="IT:urban", direction="360")))[0]
    assert row["speed_limit_kph"] is None
    assert row["direction_degrees"] == 0
    assert row["raw"]["tags"]["maxspeed"] == "IT:urban"


def test_tutor_membership_preserved_without_inventing_segment_geometry():
    relation = {"type": "relation", "id": 7,
                "tags": {"type": "enforcement", "enforcement": "average_speed"},
                "members": [{"type": "node", "ref": 1, "role": "from"},
                            {"type": "node", "ref": 2, "role": "to"}]}
    rows = parse(payload(relation, camera(), dict(camera(), id=2)))
    tutor = next(row for row in rows if row["kind"] == "average_speed")
    assert len(tutor["raw"]["resolved_members"]) == 2
    assert tutor["direction_degrees"] is None


def test_partial_responses_rejected():
    with pytest.raises(ValueError, match="Incomplete"):
        parse(payload(camera(), remark="runtime error: timeout"))


def test_invalid_coordinates_rejected():
    report = parse_report(payload(dict(camera(), lat=91)))
    assert report.controls == []
    assert report.rejected[0]["reason"] == "invalid_coordinate"


def test_query_is_bounded_and_cannot_inject_country():
    assert 'average_speed' in query("IT")
    with pytest.raises(ValueError):
        query('IT"];out;')


def test_inventory_never_claims_unmatched_controls_are_alert_eligible():
    from datetime import UTC, datetime

    from compass.api.enforcement import Control
    control = Control(
        source_key="osm:node:1", kind="fixed", latitude=45, longitude=9,
        direction_degrees=None, speed_limit_kph=None, fetched_at=datetime.now(UTC),
    )
    assert control.alert_eligible is False
    assert control.association_state == "unmatched"


def test_unresolved_relation_is_quarantined_without_blocking_valid_nodes():
    relation = {"type": "relation", "id": 8826292,
                "tags": {"type": "enforcement", "enforcement": "average_speed"},
                "members": [{"type": "way", "ref": 12, "role": "section"}]}
    report = parse_report(payload(camera(), relation))
    assert len(report.controls) == 1
    assert report.rejected[0]["source_key"] == "osm:relation:8826292"
    assert report.rejected[0]["reason"] == "unresolved_location"
    assert report.rejected[0]["raw"]["members"] == relation["members"]


def test_unresolved_member_is_not_replaced_by_invented_coordinates():
    relation = {"type": "relation", "id": 1,
                "tags": {"type": "enforcement", "enforcement": "maxspeed"},
                "members": [{"type": "node", "ref": 999, "role": "device"}]}
    report = parse_report(payload(camera(), relation))
    assert len(report.controls) == 1
    assert report.rejected[0]["raw"]["resolved_members"][0]["element"] is None


@pytest.mark.parametrize("coordinate", [None, "bad", "NaN", "Infinity"])
def test_malformed_coordinate_does_not_block_other_controls(coordinate):
    report = parse_report(payload(camera(), dict(camera(), id=2, lat=coordinate)))
    assert len(report.controls) == 1
    assert report.rejected[0]["reason"] == "invalid_coordinate"

def test_all_unresolvable_records_do_not_start_a_database_transaction():
    from datetime import UTC, datetime
    from unittest.mock import Mock

    from compass.enforcement.importer import ingest
    from compass.etl.acquisition import DownloadedPayload

    session = Mock()
    data = DownloadedPayload(payload(dict(camera(), lat=91)), "fixture://invalid",
                             "application/json", datetime.now(UTC))
    with pytest.raises(ValueError, match="Empty enforcement"):
        ingest(session, data, "TEST")
    session.begin.assert_not_called()
