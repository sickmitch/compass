import argparse
import json
from unittest.mock import MagicMock
from urllib.parse import parse_qs

import httpx
import pytest

from compass.config import Settings
from compass.enforcement.acquisition import download_inventory
from compass.enforcement.association import source_path
from compass.enforcement.osm import parse_report
from compass.etl.acquisition import AcquisitionError


def inventory():
    return {"elements": [
        {"type": "relation", "id": 7,
         "tags": {"type": "enforcement", "enforcement": "maxspeed"},
         "members": [{"type": "node", "ref": 1, "role": "from"},
                     {"type": "node", "ref": 3, "role": "to"}]},
        {"type": "node", "id": 1, "lat": 45, "lon": 9},
        {"type": "node", "id": 3, "lat": 45, "lon": 9.002},
    ]}


def roads():
    return {"elements": [
        {"type": "way", "id": 10, "nodes": [1, 2, 3], "tags": {"highway": "primary"}},
        *inventory()["elements"][1:],
        {"type": "node", "id": 2, "lat": 45, "lon": 9.001},
    ]}


def test_retry_then_enrichment_preserves_topology_and_deduplicates():
    calls, delays = [], []

    def handle(request):
        statement = parse_qs(request.content.decode())["data"][0]
        calls.append(statement)
        if len(calls) == 1:
            return httpx.Response(502)
        if len(calls) == 2:
            assert 'way(bn' not in statement
            return httpx.Response(200, json=inventory())
        assert "node(id:1,3)" in statement
        return httpx.Response(200, json=roads())

    result = download_inventory(
        Settings(), transport=httpx.MockTransport(handle), sleep=delays.append,
    )
    assert delays == [10]
    assert len(calls) == 3
    data = json.loads(result.content)
    assert len(data["elements"]) == 5
    assert data["compass_acquisition"]["requests_completed"] == 2
    assert source_path(parse_report(result.content).controls[0]["raw"]).state == "source_path"


@pytest.mark.parametrize("status,header,expected_calls,expected_delays", [
    (502, {}, 3, [10, 30]),
    (429, {"Retry-After": "40"}, 3, [40, 40]),
    (503, {"Retry-After": "600"}, 1, []),
    (503, {"Retry-After": "nan"}, 1, []),
    (400, {}, 1, []),
    (401, {}, 1, []),
])
def test_bounded_retries_and_server_backoff(status, header, expected_calls, expected_delays):
    calls, delays = [], []

    def handle(request):
        calls.append(request)
        return httpx.Response(status, headers=header)

    with pytest.raises(AcquisitionError):
        download_inventory(Settings(), transport=httpx.MockTransport(handle), sleep=delays.append)
    assert len(calls) == expected_calls
    assert delays == expected_delays


def test_partial_road_response_is_not_published_to_importer(monkeypatch):
    from compass.etl import cli

    calls = []

    def handle(request):
        calls.append(request)
        return httpx.Response(200, json=inventory() if len(calls) == 1 else {
            "elements": [], "remark": "runtime error: timeout",
        })

    def fetch(settings):
        return download_inventory(settings, transport=httpx.MockTransport(handle))

    monkeypatch.setattr("compass.enforcement.acquisition.download_inventory", fetch)
    sessions = MagicMock()
    monkeypatch.setattr(cli, "SessionLocal", sessions)
    with pytest.raises(AcquisitionError, match="Incomplete"):
        cli._enforcement(argparse.Namespace(input_file=None))
    sessions.assert_not_called()
    assert len(calls) == 2


def test_total_download_budget_covers_all_batches():
    calls = []
    first = json.dumps(inventory()).encode()

    def handle(request):
        calls.append(request)
        return httpx.Response(200, content=first if len(calls) == 1 else b"x" * 51)

    with pytest.raises(AcquisitionError, match="byte limit"):
        download_inventory(Settings(overpass_max_download_bytes=len(first) + 50),
                           transport=httpx.MockTransport(handle))
    assert len(calls) == 2


def test_many_endpoints_are_split_into_sequential_bounded_requests():
    statements = []
    elements = [
        {"type": "relation", "id": i, "tags": {"type": "enforcement"},
         "members": [{"type": "node", "ref": i, "role": "from"}]}
        for i in range(1, 202)
    ]

    def handle(request):
        statements.append(parse_qs(request.content.decode())["data"][0])
        return httpx.Response(200, json={"elements": elements if len(statements) == 1 else []})

    download_inventory(Settings(), transport=httpx.MockTransport(handle))
    assert len(statements) == 3
    assert "node(id:" + ",".join(map(str, range(1, 201))) + ")" in statements[1]
    assert "node(id:201)" in statements[2]


def test_unchanged_inventory_has_stable_bytes_across_acquisition_times():
    def handle(request):
        statement = parse_qs(request.content.decode())["data"][0]
        return httpx.Response(200, json=inventory() if "area[" in statement else roads())

    first = download_inventory(Settings(), transport=httpx.MockTransport(handle))
    second = download_inventory(Settings(), transport=httpx.MockTransport(handle))
    assert first.fetched_at <= second.fetched_at
    assert first.content == second.content
