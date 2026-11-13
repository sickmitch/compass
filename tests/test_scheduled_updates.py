import fcntl
import importlib.util
import subprocess
from datetime import datetime
from types import SimpleNamespace

import pytest

spec = importlib.util.spec_from_file_location("scheduled_update", "scripts/scheduled-update.py")
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_osm_failure_still_publishes_official_prices(monkeypatch):
    calls = []

    def compose(*args, **kwargs):
        calls.append(args[-1])
        if args[-1] == "osm":
            raise subprocess.CalledProcessError(1, args)

    monkeypatch.setattr(module, "compose", compose)
    module.update_cng()
    assert calls == ["mimit", "osm", "normalize"]


def test_mimit_failure_does_not_normalize(monkeypatch):
    calls = []

    def compose(*args, **kwargs):
        calls.append(args[-1])
        raise subprocess.CalledProcessError(1, args)

    monkeypatch.setattr(module, "compose", compose)
    with pytest.raises(subprocess.CalledProcessError):
        module.update_cng()
    assert calls == ["mimit"]


@pytest.mark.parametrize("import_fails", [False, True])
def test_enforcement_always_associates_all_even_after_download_failure(monkeypatch, import_fails):
    calls = []

    def compose(*args):
        calls.append(args)
        if args[-1] == "enforcement" and import_fails:
            raise subprocess.CalledProcessError(1, args)

    monkeypatch.setattr(module, "compose", compose)
    if import_fails:
        with pytest.raises(subprocess.CalledProcessError):
            module.update_enforcement()
    else:
        module.update_enforcement()
    assert calls == [
        ("run", "--rm", "--no-deps", "etl", "enforcement"),
        ("run", "--rm", "--no-deps", "etl", "enforcement-associate", "--all", "--limit", "500"),
    ]


@pytest.mark.parametrize("failed_job", [None, "map", "enforcement"])
def test_nightly_order_and_independent_success_markers(tmp_path, monkeypatch, failed_job):
    monkeypatch.setattr(module, "STATE", tmp_path)
    calls = []

    def action(name):
        calls.append(name)
        if name == failed_job:
            raise RuntimeError(name)

    monkeypatch.setattr(module, "update_map", lambda: action("map"))
    monkeypatch.setattr(module, "update_enforcement", lambda: action("enforcement"))
    if failed_job:
        with pytest.raises(RuntimeError, match=failed_job):
            module.execute("map")
    else:
        module.execute("map")
    assert calls == ["map", "enforcement"]
    for job in calls:
        assert (tmp_path / f"{job}.last-success").exists() == (job != failed_job)


@pytest.mark.parametrize("job", ["map", "enforcement"])
def test_map_and_enforcement_share_lock_but_cng_is_independent(tmp_path, monkeypatch, job):
    monkeypatch.setattr(module, "STATE", tmp_path)
    calls = []
    monkeypatch.setattr(module, "update_map", lambda: calls.append("map"))
    monkeypatch.setattr(module, "update_enforcement", lambda: calls.append("enforcement"))
    monkeypatch.setattr(module, "update_cng", lambda: calls.append("cng"))
    with (tmp_path / "routing-maintenance.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        module.execute(job)
        module.execute("cng")
    assert calls == ["cng"]
    module.execute("enforcement")
    assert calls == ["cng", "enforcement"]


@pytest.mark.parametrize(
    "fail_at", ["build", "probe", "activation", "confirm_response", "cleanup", None],
)
def test_map_update_keeps_volume_and_env_and_restores_graph(tmp_path, monkeypatch, fail_at):
    monkeypatch.setattr(module, "ROOT", tmp_path)
    env = tmp_path / ".env"
    original = "VALHALLA_VOLUME_NAME=existing\nAPI_AUTH_PASSWORD=example\n"
    env.write_text(original)
    state = {"schema_version": 1, "active": "legacy", "previous": None}
    actions, calls = [], []

    def dataset(action, generation=None):
        nonlocal state
        actions.append(action)
        if action == "activate":
            state = {"schema_version": 1, "active": generation, "previous": state["active"],
                     "pending": state}
        elif action == "abort":
            state = state["pending"]
        elif action == "confirm":
            state.pop("pending")
            if fail_at == "confirm_response":
                raise RuntimeError("lost response after commit")
        return dict(state)

    def compose(*args, **kwargs):
        calls.append(args)
        if args[0] == "ps":
            return "running"
        if "valhalla-tiles" in args and fail_at == "build":
            raise RuntimeError("build failed")
        return ""

    def run(*args, **kwargs):
        if args[1] == "rm" and fail_at == "cleanup":
            raise RuntimeError("cleanup failed")
        return '{"trip":{"status":1}}' if fail_at == "probe" else '{"trip":{"status":0}}'

    health_calls = []

    def healthy(container):
        health_calls.append(container)
        if container == "running" and fail_at == "activation" and len(health_calls) == 2:
            raise TimeoutError("activation failed")

    monkeypatch.setattr(module, "compose", compose)
    monkeypatch.setattr(module, "dataset", dataset)
    monkeypatch.setattr(module, "run", run)
    monkeypatch.setattr(module, "wait_healthy", healthy)
    if fail_at:
        with pytest.raises((RuntimeError, TimeoutError)):
            module.update_map()
    else:
        module.update_map()
    assert env.read_text() == original
    assert not any("volume" in call for call in calls)
    assert not any("VALHALLA_VOLUME_NAME" in str(call) for call in calls)
    if fail_at in {"build", "probe", "activation"}:
        assert state["active"] == "legacy"
        assert "discard" in actions
        if fail_at == "activation":
            assert "abort" in actions
            assert health_calls[-1] == "running"
    else:
        assert state["active"].startswith("map-")
        assert state["previous"] == "legacy"
        assert "pending" not in state
        assert "discard" not in actions
        if fail_at is None:
            assert actions[-1] == "prune"
    if fail_at in {"build", "probe"}:
        assert not any(call[0] == "up" for call in calls)


def test_rollback_probes_previous_graph_and_reassociates_controls(monkeypatch):
    state = {"active": "map-now", "previous": "map-before"}
    monkeypatch.setattr(module, "dataset", lambda action: state)
    activated, commands = [], []
    monkeypatch.setattr(module, "activate_graph", lambda *args: activated.append(args))
    monkeypatch.setattr(module, "compose", lambda *args: commands.append(args))
    module.rollback_map()
    assert activated == [("map-before", state)]
    assert commands == [
        ("run", "--rm", "--no-deps", "etl", "enforcement-associate", "--all", "--limit", "500"),
    ]


def test_dataset_commands_capture_only_the_manager_output(monkeypatch):
    calls = []
    def compose(*args, **kwargs):
        calls.append((args, kwargs))
        return '{"active":"map-ready"}'
    monkeypatch.setattr(module, "compose", compose)
    assert module.dataset("activate", "map-ready") == {"active": "map-ready"}
    assert calls == [(("run", "--rm", "--no-deps", "-T", "valhalla-config", "activate",
                      "--generation", "map-ready"), {"capture": True})]


@pytest.mark.parametrize(
    ("instant", "jobs"),
    [
        ("2026-10-28T02:00:00+00:00", ["map"]),
        ("2026-07-28T01:00:00+00:00", ["map"]),
        ("2026-10-28T06:00:00+00:00", ["cng"]),
        ("2026-10-28T19:00:00+00:00", ["cng"]),
        ("2026-10-28T20:00:00+00:00", []),
        ("2026-10-28T05:00:00+00:00", []),
        ("2026-10-28T06:01:00+00:00", []),
        ("2026-03-29T01:00:00+00:00", ["map"]),
        ("2026-10-25T02:00:00+00:00", ["map"]),
    ],
)
def test_schedule_uses_rome_hours_including_dst(instant, jobs):
    assert [job for job, slot in module.due_jobs(datetime.fromisoformat(instant))] == jobs


def test_all_fourteen_cng_hours_are_scheduled():
    for hour in range(7, 21):
        instant = datetime.fromisoformat(f"2026-10-28T{hour:02d}:00:00+01:00")
        assert module.due_jobs(instant) == [("cng", f"2026-10-28T{hour:02d}")]


def test_scheduler_restart_does_not_repeat_attempt_in_same_slot(tmp_path, monkeypatch):
    monkeypatch.setattr(module, "STATE", tmp_path)
    monkeypatch.setattr(module, "initialize", lambda: None)
    monkeypatch.setattr(
        module,
        "datetime",
        SimpleNamespace(now=lambda tz: datetime.fromisoformat("2026-10-28T06:00:00+00:00")),
    )
    calls = []
    monkeypatch.setattr(module, "execute", calls.append)

    def stop_loop(seconds):
        raise RuntimeError("end loop for test")

    monkeypatch.setattr(module.time, "sleep", stop_loop)
    for _ in range(2):
        with pytest.raises(RuntimeError, match="end loop"):
            module.serve()
    assert calls == ["cng"]
    assert (tmp_path / "heartbeat").exists()


def test_sibling_compose_uses_host_bind_paths_and_container_config(monkeypatch, tmp_path):
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setenv("COMPASS_HOST_REPOSITORY", "/host/compass")
    calls = []
    monkeypatch.setattr(module, "run", lambda *args, **kw: calls.append(args))
    module.compose("ps", "-q", "valhalla")
    args = calls[0]
    assert args[args.index("--project-directory") + 1] == "/host/compass"
    assert args[args.index("-f") + 1] == str(tmp_path / "compose.yaml")
    assert args[args.index("--env-file") + 1] == str(tmp_path / ".env")


def test_docker_socket_directory_is_rejected(tmp_path, monkeypatch):
    monkeypatch.setattr(module, "Path", lambda path: tmp_path)
    with pytest.raises(RuntimeError, match="not a Unix socket"):
        module.check_docker_connection()


def test_missing_docker_socket_has_actionable_error(tmp_path, monkeypatch):
    monkeypatch.setattr(module, "Path", lambda path: tmp_path / "missing")
    with pytest.raises(RuntimeError, match="COMPASS_DOCKER_SOCKET"):
        module.check_docker_connection()


def test_startup_failure_stays_unhealthy_without_running_jobs(tmp_path, monkeypatch):
    monkeypatch.setattr(module, "STATE", tmp_path)
    (tmp_path / "heartbeat").write_text("old")

    def unavailable():
        raise RuntimeError("daemon unavailable")

    def stop_retry(seconds):
        assert seconds == 30
        raise InterruptedError("stop test")

    monkeypatch.setattr(module, "initialize", unavailable)
    monkeypatch.setattr(module.time, "sleep", stop_retry)
    with pytest.raises(InterruptedError):
        module.serve()
    assert not (tmp_path / "heartbeat").exists()
    assert not list(tmp_path.glob("*.last-attempt-slot"))


@pytest.mark.parametrize("failure", [None, "copy", "probe", "confirm"])
def test_import_probes_copy_without_restarting_existing_router(monkeypatch, failure):
    state = {"schema_version": 1, "active": None, "previous": None}
    calls = []

    def dataset(action, generation=None):
        nonlocal state
        calls.append((action, generation))
        if action == "activate":
            state = {"schema_version": 1, "active": generation, "previous": None, "pending": state}
        elif action == "confirm":
            if failure == "confirm":
                raise RuntimeError("confirm failed")
            state.pop("pending")
        elif action == "abort":
            state = state["pending"]
        return dict(state)

    def compose(*args, **kwargs):
        calls.append(args)
        if args[0] == "ps":
            return "old-router"
        if "import" in args and failure == "copy":
            raise RuntimeError("copy failed")
        return ""

    def run(*args, **kwargs):
        calls.append(args)
        if args[1] == "inspect":
            return '[{"Type":"volume","Name":"old-dated-volume","Destination":"/custom_files"}]'
        return ""

    def probe(candidate):
        assert candidate.startswith("compass-map-check-")
        if failure == "probe":
            raise RuntimeError("probe failed")

    monkeypatch.setattr(module, "dataset", dataset)
    monkeypatch.setattr(module, "compose", compose)
    monkeypatch.setattr(module, "run", run)
    monkeypatch.setattr(module, "wait_healthy", lambda _: None)
    monkeypatch.setattr(module, "probe_route", probe)
    if failure:
        with pytest.raises(RuntimeError):
            module.import_map()
        assert state["active"] is None
        assert any(call[0] == "discard" for call in calls)
    else:
        module.import_map()
        assert state["active"].startswith("map-import-")
        assert "pending" not in state
    assert not any(call[0] in {"up", "stop"} for call in calls)
    inspection = calls.index(("docker", "volume", "inspect", "old-dated-volume"))
    copy = next(i for i, call in enumerate(calls) if "import" in call)
    assert inspection < copy
    assert "old-dated-volume:/legacy:ro" in calls[copy]


def test_import_existing_store_is_idempotent(monkeypatch):
    monkeypatch.setattr(module, "dataset", lambda _: {"active": "map-existing"})
    monkeypatch.setattr(module, "compose", lambda *a, **kw: pytest.fail("must not recreate"))
    module.import_map()


@pytest.mark.parametrize("source", ["compass_valhalla_datasets", "bad:/host", "does-not-exist"])
def test_import_invalid_or_missing_source_never_starts_copy(monkeypatch, source):
    monkeypatch.setattr(module, "dataset", lambda _: {"active": None})
    monkeypatch.setattr(module, "compose", lambda *a, **kw: pytest.fail("must not copy"))

    def missing(*args, **kwargs):
        assert args == ("docker", "volume", "inspect", source)
        raise subprocess.CalledProcessError(1, args)

    monkeypatch.setattr(module, "run", missing)
    with pytest.raises((ValueError, subprocess.CalledProcessError)):
        module.import_map(source)
