import importlib.util
import subprocess
from datetime import datetime
from types import SimpleNamespace

import pytest

spec = importlib.util.spec_from_file_location("scheduled_update", "scripts/scheduled-update.py")
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_volume_selection_preserves_other_settings_and_mode(tmp_path):
    path = tmp_path / ".env"
    path.write_text("API_AUTH_PASSWORD=example\nVALHALLA_VOLUME_NAME=old\n# comment\n")
    path.chmod(0o600)
    module.select_volume(path, "new")
    assert path.read_text() == "API_AUTH_PASSWORD=example\n# comment\nVALHALLA_VOLUME_NAME=new\n"
    assert path.stat().st_mode & 0o777 == 0o600


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


@pytest.mark.parametrize("fail_at", ["build", "probe", "activation", None])
def test_map_failure_preserves_or_restores_serving_volume(tmp_path, monkeypatch, fail_at):
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(module, "STATE", tmp_path / ".scheduled-updates")
    (tmp_path / ".env").write_text("VALHALLA_VOLUME_NAME=existing\n")
    (tmp_path / ".scheduled-updates").mkdir()
    activations = []

    def compose(*args, env=None):
        if args[0] == "ps":
            return "running"
        if "valhalla-tiles" in args and fail_at == "build":
            raise RuntimeError("build failed")
        if args[0] == "up":
            activations.append(env["VALHALLA_VOLUME_NAME"])
        return ""

    def run(*args, **kwargs):
        if args[1] == "inspect":
            return '[{"Destination":"/custom_files","Type":"volume","Name":"existing"}]'
        if fail_at == "probe":
            raise RuntimeError("probe failed")
        return '{"trip":{"status":0}}'

    health_calls = []

    def healthy(container):
        health_calls.append(container)
        if container == "running" and fail_at == "activation" and len(health_calls) == 2:
            raise TimeoutError("activation failed")

    monkeypatch.setattr(module, "compose", compose)
    monkeypatch.setattr(module, "run", run)
    monkeypatch.setattr(module, "wait_healthy", healthy)
    monkeypatch.setattr(module.subprocess, "run", lambda *a, **kw: None)
    if fail_at:
        with pytest.raises((RuntimeError, TimeoutError)):
            module.update_map()
        assert (tmp_path / ".env").read_text() == "VALHALLA_VOLUME_NAME=existing\n"
        if fail_at == "activation":
            assert activations[-1] == "existing"
        else:
            assert not activations
    else:
        module.update_map()
        assert activations[0].startswith("existing-scheduled-")
        assert (tmp_path / ".scheduled-updates/previous-volume").read_text() == "existing\n"


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
