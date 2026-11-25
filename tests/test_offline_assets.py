import hashlib
import importlib.util
import json
from datetime import datetime
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("offline_assets", "tools/build-offline-assets.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def stage(tmp_path):
    folder = tmp_path / "stage"
    folder.mkdir(exist_ok=True)
    for name in module.NAMES:
        (folder / f"{name}.zip").write_bytes(name.encode())
    return folder


def publish(tmp_path, date):
    return module.publish(stage(tmp_path), tmp_path, datetime.fromisoformat(date), "family")


def test_all_five_names_use_creation_date_in_rome_and_sha256(tmp_path):
    record = publish(tmp_path, "2026-11-21T23:30:00+00:00")
    assert set(record["files"]) == {f"{name}_22112026.zip" for name in module.NAMES}
    for name, metadata in record["files"].items():
        content = (tmp_path / name).read_bytes()
        assert metadata == {"bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
    assert json.loads((tmp_path / "latest.json").read_text()) == record
    assert module.read_set(tmp_path, "22112026") == record


def test_rerun_same_date_is_idempotent_and_recovers_latest_marker(tmp_path):
    first = publish(tmp_path, "2026-11-21T10:00:00+01:00")
    (tmp_path / "latest.json").unlink()
    (tmp_path / "stage" / "italia.zip").unlink()  # next build has separate files
    (tmp_path / "stage" / "italia.zip").write_bytes(b"changed")
    again = module.publish(
        tmp_path / "stage",
        tmp_path,
        datetime.fromisoformat("2026-11-21T20:00:00+01:00"),
        "different",
    )
    assert again == first
    assert (tmp_path / "italia_21112026.zip").read_bytes() == b"italia"
    assert json.loads((tmp_path / "latest.json").read_text()) == first


def test_partial_publish_rolls_back_without_touching_previous_set(tmp_path, monkeypatch):
    first = publish(tmp_path, "2026-11-14T10:00:00+01:00")
    link = module.os.link
    calls = []

    def fail(source, target):
        calls.append(target)
        if len(calls) == 3:
            raise OSError("disk full")
        link(source, target)

    monkeypatch.setattr(module.os, "link", fail)
    with pytest.raises(OSError, match="disk full"):
        publish(tmp_path, "2026-11-21T10:00:00+01:00")
    assert not list(tmp_path.glob("*_21112026.zip"))
    assert json.loads((tmp_path / "latest.json").read_text()) == first
    assert module.read_set(tmp_path, "14112026") == first


def test_retention_keeps_only_latest_and_preserves_unrelated_files(tmp_path):
    for date in ("2026-10-31", "2026-11-07", "2026-11-14"):
        publish(tmp_path, date + "T10:00:00+01:00")
    unrelated = tmp_path / "personal.zip"
    unrelated.write_bytes(b"keep")
    module.prune(tmp_path)
    assert not list(tmp_path.glob("*_31102026.zip"))
    assert module.read_set(tmp_path, "07112026") is None
    assert not list(tmp_path.glob("*_07112026.zip"))
    assert module.read_set(tmp_path, "14112026")
    assert unrelated.read_bytes() == b"keep"


@pytest.mark.parametrize("failure", ["build", "probe", "export", None])
def test_pipeline_failures_preserve_published_set_and_cleanup(tmp_path, monkeypatch, failure):
    output, work = tmp_path / "output", tmp_path / "work"
    output.mkdir()
    previous = publish(output, "2026-11-14T10:00:00+01:00")
    monkeypatch.setattr(module, "OUTPUT", output)
    monkeypatch.setattr(module, "WORK", work)
    monkeypatch.setattr(module, "now", lambda: datetime.fromisoformat("2026-11-21T10:00:00+01:00"))
    calls = []

    def action(phase):
        calls.append(phase)
        if phase == failure:
            raise RuntimeError(phase)

    def build(folder):
        folder.mkdir()
        action("build")

    def export(folder, staging, regions):
        action("export")
        for name in module.NAMES:
            (staging / f"{name}.zip").write_bytes(b"new")
        return "new-family"

    monkeypatch.setattr(module, "build_graph", build)
    monkeypatch.setattr(module, "probe_graph", lambda *args: action("probe"))
    monkeypatch.setattr(module, "generate", export)
    if failure:
        with pytest.raises(RuntimeError, match=failure):
            module.main()
        assert json.loads((output / "latest.json").read_text()) == previous
    else:
        module.main()
        assert calls == ["build", "probe", "export"]
        assert module.read_set(output, "21112026")["graph_family"] == "new-family"
        module.main()
        assert calls == ["build", "probe", "export"]  # no second build on same date
    assert not list(work.iterdir())
    assert not list(output.glob(".compass-offline-stage-*"))
    assert module.read_set(output, "14112026") == (previous if failure else None)


def test_build_uses_isolated_fresh_directory_and_checks_output(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(
        module.subprocess, "run", lambda *args, **kwargs: calls.append((args, kwargs))
    )
    folder = tmp_path / "offline-build-example"
    with pytest.raises(RuntimeError, match="did not produce"):
        module.build_graph(folder)
    args, kwargs = calls[0]
    assert args[0] == ["/valhalla/scripts/docker-entrypoint.sh", "build_tiles"]
    assert kwargs["env"]["path_extension"] == folder.name
    assert kwargs["env"]["serve_tiles"] == "False"
    assert kwargs["check"] is True


def test_actual_region_configuration_has_complete_italy_and_five_packages():
    regions = json.loads(Path("tools/offline-italia-regions.json").read_text())
    assert {r["id"] for r in regions} == set(module.NAMES)
    assert regions[-1]["id"] == "italia"
    assert regions[-1]["allTiles"] is True


@pytest.mark.parametrize("route_ok", [True, False])
def test_native_probe_checks_routes_and_always_stops_router(tmp_path, monkeypatch, route_ok):
    import io
    from types import SimpleNamespace

    stopped, requests = [], []
    process = SimpleNamespace(
        poll=lambda: None,
        terminate=lambda: stopped.append("terminate"),
        wait=lambda **kw: stopped.append("wait"),
    )
    monkeypatch.setattr(module.subprocess, "Popen", lambda *args, **kw: process)

    def urlopen(request, timeout):
        if isinstance(request, str):
            return io.BytesIO(b"{}")
        requests.append(json.loads(request.data))
        return io.BytesIO(json.dumps({"trip": {"status": 0 if route_ok else 1}}).encode())

    monkeypatch.setattr(module.urllib.request, "urlopen", urlopen)
    regions = json.loads(Path("tools/offline-italia-regions.json").read_text())
    if route_ok:
        module.probe_graph(tmp_path, regions)
        assert len(requests) == 6
    else:
        with pytest.raises(RuntimeError, match="route validation failed"):
            module.probe_graph(tmp_path, regions)
    assert stopped == ["terminate", "wait"]


def test_five_real_zip_exports_share_the_same_graph(tmp_path):
    import io
    import tarfile
    import zipfile

    regions = json.loads(Path("tools/offline-italia-regions.json").read_text())
    paths = {"0/003/017.gph"}
    for region in regions:
        lat, lon = region["probe"][0]
        tile = int((lat + 90) / 0.25) * 1440 + int((lon + 180) / 0.25)
        digits = f"{tile:09d}"
        paths.add(f"2/{digits[:3]}/{digits[3:6]}/{digits[6:]}.gph")
    with tarfile.open(tmp_path / "valhalla_tiles.tar", "w") as archive:
        for name in sorted(paths):
            entry = tarfile.TarInfo(name)
            entry.size = 1
            archive.addfile(entry, io.BytesIO(b"x"))
    family = module.generate(tmp_path, tmp_path / "export", regions)
    for name in module.NAMES:
        with zipfile.ZipFile(tmp_path / "export" / f"{name}.zip") as package:
            manifest = json.loads(package.read("manifest.json"))
            assert manifest["graphFamily"] == family
            assert manifest["engineVersion"] == "3.9.0"
            if name == "italia":
                assert set(package.namelist()) == paths | {"manifest.json"}


def test_cleanup_can_resume_after_partial_deletion(tmp_path):
    publish(tmp_path, "2026-11-14T10:00:00+01:00")
    latest = publish(tmp_path, "2026-11-21T10:00:00+01:00")
    (tmp_path / "italia_14112026.zip").unlink()
    module.prune(tmp_path)
    assert not list(tmp_path.glob("*_14112026.zip"))
    assert module.read_set(tmp_path, "14112026") is None
    assert module.read_set(tmp_path, "21112026") == latest


def test_broken_latest_never_deletes_previous(tmp_path):
    previous = publish(tmp_path, "2026-11-14T10:00:00+01:00")
    publish(tmp_path, "2026-11-21T10:00:00+01:00")
    (tmp_path / "italia_21112026.zip").unlink()
    with pytest.raises(ValueError):
        module.prune(tmp_path)
    assert module.read_set(tmp_path, "14112026") == previous


def test_prune_only_ignores_legacy_retention_setting_and_does_not_build(tmp_path, monkeypatch):
    publish(tmp_path, "2026-11-14T10:00:00+01:00")
    latest = publish(tmp_path, "2026-11-21T10:00:00+01:00")
    monkeypatch.setattr(module, "OUTPUT", tmp_path)
    monkeypatch.setattr(module, "WORK", tmp_path / "work")
    monkeypatch.setenv("COMPASS_OFFLINE_KEEP_SETS", "2")
    monkeypatch.setattr(module, "build_graph", lambda *args: pytest.fail("must not rebuild"))
    module.main(prune_only=True)
    assert list(tmp_path.glob("set-*.json")) == [tmp_path / "set-21112026.json"]
    assert len(list(tmp_path.glob("*_*.zip"))) == 5
    assert module.read_set(tmp_path, "21112026") == latest
