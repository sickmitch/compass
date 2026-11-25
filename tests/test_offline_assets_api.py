import hashlib
import json

import pytest
from fastapi.testclient import TestClient

from compass.api.main import app
from compass.api.offline_assets import AREAS
from compass.config import Settings, get_api_settings


@pytest.fixture
def assets(tmp_path):
    files = {}
    for area in AREAS:
        data = area.encode()
        filename = f"{area}_22112026.zip"
        (tmp_path / filename).write_bytes(data)
        files[filename] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    record = {
        "schema_version": 1,
        "created_at": "2026-11-22T04:00:00+01:00",
        "graph_family": "a" * 64,
        "files": files,
    }
    for name in ("latest.json", "set-22112026.json"):
        (tmp_path / name).write_text(json.dumps(record))
    app.dependency_overrides[get_api_settings] = lambda: Settings(
        offline_assets_path=str(tmp_path),
        api_auth_enabled=True,
        api_auth_username="test",
        api_auth_password="password",
    )
    yield tmp_path
    app.dependency_overrides.clear()


def test_catalog_requires_auth_and_has_geographic_names(assets):
    with TestClient(app) as client:
        assert client.get("/api/v1/offline-routing/packages").status_code == 401
        response = client.get("/api/v1/offline-routing/packages", auth=("test", "password"))
        assert response.status_code == 200
        catalog = response.json()
        assert len(catalog["packages"]) == 5
        assert {p["label"] for p in catalog["packages"]} == set(AREAS.values())
        assert catalog["graph_family"] == "a" * 64


def test_download_is_streamed_range_resumable_and_only_published_files(assets):
    with TestClient(app) as client:
        url = "/api/v1/offline-routing/packages/nord-est_22112026.zip"
        assert client.get(url).status_code == 401
        response = client.get(url, auth=("test", "password"), headers={"Range": "bytes=2-"})
        assert response.status_code == 206
        assert response.content == b"rd-est"
        assert response.headers["content-range"] == "bytes 2-7/8"
        assert response.headers["etag"] == '"' + hashlib.sha256(b"nord-est").hexdigest() + '"'
        (assets / "italia_23112026.zip").write_bytes(b"unpublished")
        for filename in ("italia_23112026.zip", "latest.json", "random.zip", "..%2Flatest.json"):
            assert (
                client.get(
                    "/api/v1/offline-routing/packages/" + filename, auth=("test", "password")
                ).status_code
                == 404
            )


def test_incomplete_or_symlink_publication_is_not_advertised(assets):
    target = assets / "italia_22112026.zip"
    target.unlink()
    with TestClient(app) as client:
        assert (
            client.get("/api/v1/offline-routing/packages", auth=("test", "password")).status_code
            == 503
        )
        target.symlink_to(assets / "nord-est_22112026.zip")
        assert (
            client.get("/api/v1/offline-routing/packages", auth=("test", "password")).status_code
            == 503
        )
        assert (
            client.get(
                "/api/v1/offline-routing/packages/italia_22112026.zip", auth=("test", "password")
            ).status_code
            == 404
        )


def test_no_publication_returns_empty_catalog(assets):
    (assets / "latest.json").unlink()
    with TestClient(app) as client:
        assert client.get("/api/v1/offline-routing/packages", auth=("test", "password")).json() == {
            "graph_family": None,
            "created_at": None,
            "packages": [],
        }


@pytest.mark.parametrize("payload", [{"files": []}, {"files": None}, []])
def test_malformed_publication_returns_controlled_error(assets, payload):
    (assets / "latest.json").write_text(json.dumps(payload))
    with TestClient(app) as client:
        response = client.get("/api/v1/offline-routing/packages", auth=("test", "password"))
        assert response.status_code == 503
        assert response.json()["code"] == "offline_catalog_unavailable"
