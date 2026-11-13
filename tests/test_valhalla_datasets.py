import fcntl
import importlib.util
import io
import json
import subprocess
import sys
import tarfile

import pytest


@pytest.fixture
def datasets(tmp_path, monkeypatch):
    spec = importlib.util.spec_from_file_location("datasets", "tools/valhalla-datasets.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    monkeypatch.setattr(module, "ROOT", tmp_path)
    return module


def make_graph(module, generation="legacy", *, ready=True):
    folder = module.generation_path(generation)
    folder.mkdir(parents=True, exist_ok=True)
    with tarfile.open(folder / "valhalla_tiles.tar", "w") as archive:
        tile = tarfile.TarInfo("2/000/000.gph")
        tile.size = 4
        archive.addfile(tile, io.BytesIO(b"tile"))
    (folder / "valhalla.json").write_text(json.dumps({
        "mjolnir": {"tile_extract": str(folder / "valhalla_tiles.tar")},
    }))
    if ready:
        module.mark_ready(generation)
    return folder


def test_legacy_adoption_is_idempotent_without_copying_tiles(datasets):
    folder = make_graph(datasets, ready=False)
    archive = folder / "valhalla_tiles.tar"
    before = archive.stat()
    state = datasets.recover(require_active=True)
    assert state == {"schema_version": 1, "active": "legacy", "previous": None}
    assert datasets.recover(require_active=True) == state
    assert archive.stat().st_ino == before.st_ino
    assert archive.stat().st_mtime_ns == before.st_mtime_ns
    assert json.loads((folder / "valhalla.json").read_text())["service_limits"][
        "allow_hard_exclusions"
    ] is True


def test_empty_store_never_builds_implicitly(datasets, monkeypatch):
    monkeypatch.setattr(datasets.subprocess, "run", lambda *a, **kw: pytest.fail("unexpected"))
    assert datasets.recover()["active"] is None
    with pytest.raises(RuntimeError, match="No prepared graph"):
        datasets.recover(require_active=True)
    with pytest.raises(RuntimeError, match="No active graph"):
        datasets.serve()


@pytest.mark.parametrize("generation", ["../bad", "map-../../bad", "/tmp/foo", "map-x/y", ""])
def test_generation_cannot_escape_store(datasets, generation):
    with pytest.raises(ValueError):
        datasets.generation_path(generation)


def test_reject_symlink_generation_and_archive(datasets, tmp_path):
    (tmp_path / "datasets").symlink_to(tmp_path, target_is_directory=True)
    with pytest.raises(ValueError):
        datasets.generation_path("map-one")
    (tmp_path / "datasets").unlink()
    folder = make_graph(datasets, "map-one")
    archive = folder / "valhalla_tiles.tar"
    archive.rename(folder / "real.tar")
    archive.symlink_to(folder / "real.tar")
    with pytest.raises(ValueError, match="symlinks"):
        datasets.validate_graph("map-one")


def test_build_is_isolated_and_preserves_active_graph(datasets, monkeypatch):
    old = make_graph(datasets)
    datasets.recover()
    before = (old / "valhalla_tiles.tar").read_bytes()
    calls = []

    def upstream(command, *, env, check):
        calls.append(command)
        assert env["path_extension"] == "datasets/map-new"
        assert env["serve_tiles"] == "False"
        assert env["build_tar"] == "True"
        assert check
        make_graph(datasets, "map-new", ready=False)

    monkeypatch.setattr(datasets.subprocess, "run", upstream)
    datasets.build("map-new")
    assert calls == [["/valhalla/scripts/docker-entrypoint.sh", "build_tiles"]]
    assert datasets.read_state()["active"] == "legacy"
    assert (old / "valhalla_tiles.tar").read_bytes() == before
    datasets.validate_graph("map-new")
    with pytest.raises(FileExistsError):
        datasets.build("map-new")
    with pytest.raises(ValueError):
        datasets.build("legacy")


def test_failed_builder_never_marks_graph_ready(datasets, monkeypatch):
    def fail(*args, **kwargs):
        raise subprocess.CalledProcessError(1, args[0])
    monkeypatch.setattr(datasets.subprocess, "run", fail)
    with pytest.raises(subprocess.CalledProcessError):
        datasets.build("map-broken")
    assert not (datasets.generation_path("map-broken") / datasets.READY).exists()
    with pytest.raises(FileNotFoundError):
        datasets.activate("map-broken")


def test_builder_lock_rejects_concurrent_job(datasets, monkeypatch):
    with datasets.lock(".dataset-build.lock"), pytest.raises(RuntimeError, match="operation"):
        datasets.build("map-other")


def test_activation_confirmation_and_rollback_are_persistent(datasets):
    make_graph(datasets)
    datasets.recover()
    make_graph(datasets, "map-one")
    datasets.activate("map-one")
    assert datasets.read_state()["pending"]["active"] == "legacy"
    datasets.finish("map-one")
    assert datasets.recover() == {"schema_version": 1, "active": "map-one", "previous": "legacy"}
    datasets.activate("legacy")
    datasets.finish("legacy")
    assert datasets.read_state()["previous"] == "map-one"


def test_interrupted_activation_recovers_last_confirmed_graph(datasets):
    make_graph(datasets)
    state = datasets.recover()
    make_graph(datasets, "map-one")
    datasets.activate("map-one")
    with pytest.raises(RuntimeError, match="Unconfirmed"):
        datasets.activate("legacy")
    assert datasets.recover(require_active=True) == state


def test_failed_first_activation_can_retry_bootstrap(datasets):
    make_graph(datasets, "map-one")
    datasets.activate("map-one")
    assert datasets.finish("map-one", abort=True)["active"] is None
    make_graph(datasets, "map-two")
    datasets.activate("map-two")
    datasets.finish("map-two")
    assert datasets.recover(require_active=True)["active"] == "map-two"


def test_confirmation_cannot_commit_another_transaction(datasets):
    make_graph(datasets, "map-one")
    datasets.activate("map-one")
    with pytest.raises(RuntimeError, match="no longer matches"):
        datasets.finish("map-other")
    assert "pending" in datasets.read_state()


def test_archive_corruption_is_rejected(datasets):
    folder = make_graph(datasets, "map-one")
    (folder / "valhalla_tiles.tar").write_bytes(b"broken")
    with pytest.raises(ValueError, match="changed"):
        datasets.validate_graph("map-one")
    with pytest.raises(tarfile.ReadError):
        datasets.mark_ready("map-one")


def test_no_tiles_or_foreign_config_cannot_be_adopted(datasets):
    folder = make_graph(datasets)
    with tarfile.open(folder / "valhalla_tiles.tar", "w"):
        pass
    with pytest.raises(ValueError, match="No routing tiles"):
        datasets.recover()
    (folder / "valhalla.json").write_text('{"mjolnir":{"tile_extract":"/another/archive"}}')
    with pytest.raises(ValueError, match="outside"):
        datasets.recover()


def test_prune_retains_active_previous_legacy_and_live_reader(datasets):
    make_graph(datasets)
    datasets.recover()
    for name in ("map-live", "map-older", "map-one", "map-two"):
        make_graph(datasets, name)
        datasets.activate(name)
        datasets.finish(name)
    live = datasets.generation_path("map-live")
    with (live / datasets.LEASE).open("rb") as lease:
        fcntl.flock(lease, fcntl.LOCK_SH)
        datasets.prune()
        assert live.exists()
        datasets.discard("map-live")
        assert live.exists()
    datasets.prune()
    assert not live.exists()
    assert not datasets.generation_path("map-older").exists()
    assert datasets.generation_path("map-one").exists()
    assert datasets.generation_path("map-two").exists()
    assert (datasets.ROOT / "valhalla_tiles.tar").exists()
    for protected in ("legacy", "map-one", "map-two"):
        with pytest.raises(RuntimeError):
            datasets.discard(protected)


def test_pending_activation_blocks_pruning(datasets):
    make_graph(datasets, "map-one")
    datasets.activate("map-one")
    with pytest.raises(RuntimeError):
        datasets.prune()
    with pytest.raises(RuntimeError):
        datasets.discard("map-unused")


def test_serve_execs_native_service_with_resolved_immutable_config(datasets, monkeypatch):
    folder = make_graph(datasets, "map-one")
    datasets.activate("map-one")
    datasets.finish("map-one")
    calls = []
    monkeypatch.setattr(datasets.os, "execvp", lambda *args: calls.append(args))
    monkeypatch.setenv("server_threads", "3")
    datasets.serve()
    assert calls == [("valhalla_service", ["valhalla_service", str(folder / "valhalla.json"), "3"])]


def test_lease_survives_native_exec(datasets):
    # Exercise the real serve wrapper across exec, substituting a tiny process for Valhalla.
    folder = make_graph(datasets, "map-one")
    datasets.activate("map-one")
    datasets.finish("map-one")
    script = """
import importlib.util, os, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('datasets', sys.argv[1])
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.ROOT = Path(sys.argv[2])
def native(*args):
    os.execv(sys.executable, [sys.executable, '-c',
        'import sys; print("ready", flush=True); sys.stdin.read()'])
m.os.execvp = native
m.serve()
"""
    child = subprocess.Popen([sys.executable, "-c", script, datasets.__file__, str(datasets.ROOT)],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        assert "Serving graph generation" in child.stdout.readline()
        assert child.stdout.readline().strip() == "ready"
        with (folder / datasets.LEASE).open("rb") as stream, pytest.raises(BlockingIOError):
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    finally:
        child.communicate(timeout=5)


@pytest.mark.parametrize("source_generation", ["legacy", "map-old"])
def test_import_copies_confirmed_graph_and_relocates_paths(datasets, tmp_path, source_generation):
    source = tmp_path / "old-volume"
    source.mkdir()
    origin = source if source_generation == "legacy" else source / "datasets" / source_generation
    origin.mkdir(parents=True, exist_ok=True)
    with tarfile.open(origin / "valhalla_tiles.tar", "w") as archive:
        tile = tarfile.TarInfo("2/000/000.gph")
        tile.size = 4
        archive.addfile(tile, io.BytesIO(b"tile"))
    suffix = f"/datasets/{source_generation}" if source_generation != "legacy" else ""
    prefix = "/custom_files" + suffix
    config = {"mjolnir": {"tile_extract": prefix + "/valhalla_tiles.tar",
                          "admin": prefix + "/admins.sqlite"},
              "loki": {"logging": {"file_name": prefix + "/service.log"}}}
    (origin / "valhalla.json").write_text(json.dumps(config))
    (origin / "admins.sqlite").write_bytes(b"supporting database")
    (origin / "italy-latest.osm.pbf").write_bytes(b"large source not needed for serving")
    if source_generation != "legacy":
        (source / datasets.MANIFEST).write_text(json.dumps({
            "schema_version": 1, "active": "map-unconfirmed", "previous": source_generation,
            "pending": {"schema_version": 1, "active": source_generation, "previous": None},
        }))
    before = {str(p.relative_to(source)): p.read_bytes() for p in source.rglob("*") if p.is_file()}
    destination = tmp_path / "new-volume"
    destination.mkdir()
    datasets.ROOT = destination
    datasets.import_graph("map-imported", source)
    folder = datasets.generation_path("map-imported")
    imported = json.loads(datasets.validate_graph("map-imported").read_text())
    assert imported["mjolnir"]["admin"] == str(folder / "admins.sqlite")
    assert imported["loki"]["logging"]["file_name"] == str(folder / "service.log")
    assert (folder / "admins.sqlite").read_bytes() == b"supporting database"
    assert not (folder / "italy-latest.osm.pbf").exists()
    assert datasets.read_state()["active"] is None  # A candidate probe is still required.
    assert before == {str(p.relative_to(source)): p.read_bytes()
                      for p in source.rglob("*") if p.is_file()}
    datasets.activate("map-imported")
    datasets.finish("map-imported")
    with pytest.raises(RuntimeError, match="already initialized"):
        datasets.import_graph("map-another", source)


def test_import_rejects_same_store_and_invalid_source(datasets, tmp_path):
    with pytest.raises(ValueError, match="separate source"):
        datasets.import_graph("map-copy", tmp_path)
    source = tmp_path / "source"
    source.mkdir()
    (source / "valhalla.json").write_text('{"mjolnir":{"tile_extract":"/other/tiles.tar"}}')
    with pytest.raises(ValueError, match="outside"):
        datasets.import_graph("map-copy", source)
    assert not datasets.generation_path("map-copy").exists()
    assert datasets.read_state()["active"] is None
