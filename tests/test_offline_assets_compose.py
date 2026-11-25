"""Resolve bind mounts with Compose locally; no Docker daemon or live build required."""

import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("sibling_job", [False, True])
def test_output_bind_is_in_deployment_project_even_with_legacy_override(tmp_path, sibling_job):
    docker = shutil.which("docker")
    if docker is None:
        pytest.skip("Docker Compose CLI is not installed")
    project = tmp_path / "host deployment" if sibling_job else ROOT
    args = [
        docker,
        "compose",
        "--env-file",
        str(ROOT / ".env.example"),
        "-f",
        str(ROOT / "compose.yaml"),
    ]
    if sibling_job:
        # Scheduler reads /workspace/compose.yaml but resolves binds on the Docker host.
        args.extend(["--project-directory", str(project)])
    args.extend(["--profile", "*", "config", "--format", "json"])
    result = subprocess.run(
        args,
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
        env=dict(os.environ, COMPASS_OFFLINE_ASSETS_DIR="/offline_assets"),
    )
    config = json.loads(result.stdout)
    worker = config["services"]["offline-assets"]
    assert worker["command"] == []  # do not inherit the image's build_tiles argument
    output = next(m for m in worker["volumes"] if m["target"] == "/offline_assets")
    assert output["type"] == "bind"
    assert output["source"] == str(project / "offline_assets")
    # Compose may omit the default true value from normalized JSON.
    assert output["bind"].get("create_host_path", True) is True
    api_output = next(
        m for m in config["services"]["api"]["volumes"] if m["target"] == "/offline_assets"
    )
    assert api_output["source"] == output["source"]
    assert api_output["read_only"] is True
    scheduler = config["services"]["scheduled-updates"]
    socket = next(m for m in scheduler["volumes"] if m["target"] == "/var/run/docker.sock")
    assert socket["bind"]["create_host_path"] is False
