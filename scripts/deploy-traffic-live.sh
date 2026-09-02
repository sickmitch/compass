#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
api_base_url="${TRAFFIC_PRODUCTION_API_BASE_URL:-http://127.0.0.1:8000}"
status_path=/tmp/compass-traffic-production-status.json
config_path=/tmp/compass-traffic-production-config.json
preflight_config_path=/tmp/compass-traffic-production-config-preflight.json
clear_path=/tmp/compass-traffic-production-clear.json
reinitialize_path=/tmp/compass-traffic-production-reinitialize.json
extract_rebuild_path=/tmp/compass-traffic-production-extract-rebuild.json
ledger_clear_path=/tmp/compass-traffic-production-ledger-clear.json
state_path=/tmp/compass-traffic-production-state.json
ledger_path=/tmp/compass-traffic-production-ledger.json
health_path=/tmp/compass-traffic-production-health.json
first_route_path=/tmp/compass-traffic-production-route-first.json
second_route_path=/tmp/compass-traffic-production-route-second.json
updater_logs_path=/tmp/compass-traffic-production-updater.log
valhalla_logs_path=/tmp/compass-traffic-production-valhalla.log
rollback_path=/tmp/compass-traffic-production-rollback.json
activation_started=false
activation_accepted=false
valhalla_stopped=false

cd "$repo_root"
export TRAFFIC_REFRESH_MODE=on_demand

run_writer() {
    docker compose --profile traffic run --rm --no-deps --user 0:0 \
    traffic-updater "$@"
}

rollback_failed_activation() {
    local exit_status=$?
    if [[ "$valhalla_stopped" == "true" ]]; then
        echo "Restarting Valhalla after an interrupted traffic-extract rebuild." >&2
        docker compose --profile routing up -d --no-deps valhalla >/dev/null 2>&1 || true
        valhalla_stopped=false
    fi
    if [[ "$exit_status" -ne 0 && "$activation_started" == "true" && "$activation_accepted" != "true" ]]; then
        echo >&2
        echo "Activation failed: stopping traffic-updater and resetting managed edges." >&2
        docker compose --profile traffic stop traffic-updater >/dev/null 2>&1 || true
        if ! run_writer compass-traffic clear-managed >"$rollback_path" 2>&1; then
            echo "CRITICAL: automatic traffic rollback failed." >&2
            echo "Return $rollback_path and $updater_logs_path." >&2
        else
            echo "Rollback result: $rollback_path" >&2
            echo "The API remains available using Valhalla fallback speeds." >&2
        fi
    fi
    return "$exit_status"
}
trap rollback_failed_activation EXIT INT TERM

echo "[1/10] Checking Valhalla, traffic.tar and the running tileset identity"
docker compose --profile routing exec -T valhalla \
curl --fail --silent --show-error http://127.0.0.1:8002/status >"$status_path"
docker compose --profile routing exec -T valhalla \
test -s /custom_files/traffic.tar
running_tileset_identity="$(python3 - "$status_path" <<'PY'
import json
import sys

status = json.load(open(sys.argv[1], encoding="utf-8"))
print(f"valhalla-{status['version']}:{int(status['tileset_last_modified'])}")
PY
)"
echo "running_tileset_identity=$running_tileset_identity"
configured_tileset_identity="${TRAFFIC_VALHALLA_TILESET_VERSION:-}"
if [[ -n "$configured_tileset_identity" && "$configured_tileset_identity" != "$running_tileset_identity" ]]; then
    echo "Configured tileset identity is stale: $configured_tileset_identity"
    echo "Binding this activation to the running Valhalla identity."
fi
export TRAFFIC_VALHALLA_TILESET_VERSION="$running_tileset_identity"

echo "[2/10] Stopping the legacy polling updater before migration"
docker compose --profile traffic stop traffic-updater >/dev/null 2>&1 || true

echo "[3/10] Building the on-demand updater and traffic-aware API"
docker compose --profile traffic build traffic-updater api

echo "[4/10] Validating configuration and clearing old managed traffic safely"
run_writer compass-traffic config-check >"$preflight_config_path"
python3 - "$preflight_config_path" "$running_tileset_identity" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1], encoding="utf-8"))
assert config["configured_tileset_identity"] == sys.argv[2]
assert config["mapping_version"] == "valhalla-openlr-geometry-v1"
assert config["refresh_mode"] == "on_demand"
assert config["route_refresh_min_interval_seconds"] == 300
PY
state_tileset_identity="$(python3 - "$preflight_config_path" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1], encoding="utf-8"))
print(config["state_tileset_identity"])
PY
)"
state_identity_matches="$(python3 - "$preflight_config_path" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1], encoding="utf-8"))
print("true" if config["state_identity_matches_configured"] else "false")
PY
)"
if [[ "$state_identity_matches" == "true" ]]; then
    run_writer compass-traffic clear-managed >"$clear_path"
else
    echo "Persisted traffic state belongs to $state_tileset_identity."
    echo "Rebuilding traffic.tar before discarding stale GraphIds."
    docker compose --profile routing stop valhalla
    valhalla_stopped=true
    docker compose --profile traffic-build run --rm --no-deps \
    valhalla-traffic-extract >"$extract_rebuild_path"
    run_writer compass-traffic reinitialize-state-after-traffic-rebuild \
    --previous-tileset "$state_tileset_identity" >"$reinitialize_path"
    docker compose --profile routing up -d --no-deps valhalla
    valhalla_stopped=false
    for _attempt in $(seq 1 60); do
        if docker compose --profile routing exec -T valhalla \
            curl --fail --silent http://127.0.0.1:8002/status >/dev/null 2>&1; then
            break
        fi
        sleep 2
    done
    docker compose --profile routing exec -T valhalla \
    curl --fail --silent --show-error http://127.0.0.1:8002/status >/dev/null
fi
run_writer compass-traffic clear-route-refreshes >"$ledger_clear_path"
run_writer compass-traffic config-check >"$config_path"
python3 - "$config_path" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1], encoding="utf-8"))
assert config["state_identity_matches_configured"] is True
assert config["managed_edge_count"] == 0
PY

echo "[5/10] Starting the private on-demand updater and recreating the API"
activation_started=true
activation_started_at="$(date --iso-8601=seconds)"
docker compose --profile traffic up -d --no-deps --force-recreate \
traffic-updater api
for _attempt in $(seq 1 60); do
    if docker compose --profile traffic exec -T traffic-updater \
        python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8003/health/live', timeout=2)" \
        >/dev/null 2>&1 \
        && curl --fail --silent --show-error "${api_base_url}/health/live" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
docker compose --profile traffic exec -T traffic-updater \
python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8003/health/live', timeout=2)" \
>/dev/null
curl --fail --silent --show-error "${api_base_url}/health/live" >/dev/null

echo "[6/10] Requesting Milan to Bologna to trigger one route-scoped refresh"
curl --fail-with-body --silent --show-error \
--header 'Content-Type: application/json' \
--data-binary @- \
"${api_base_url}/api/v1/routes" >"$first_route_path" <<'JSON'
{
"origin":{"latitude":45.4642,"longitude":9.1900},
"destination":{"latitude":44.4949,"longitude":11.3426}
}
JSON

echo "[7/10] Repeating the same route immediately to prove the five-minute skip"
curl --fail-with-body --silent --show-error \
--header 'Content-Type: application/json' \
--data-binary @- \
"${api_base_url}/api/v1/routes" >"$second_route_path" <<'JSON'
{
"origin":{"latitude":45.4642,"longitude":9.1900},
"destination":{"latitude":44.4949,"longitude":11.3426}
}
JSON

echo "[8/10] Capturing health, tileset-bound state and private updater evidence"
curl --fail --silent --show-error \
"${api_base_url}/api/v1/traffic/health" >"$health_path"
run_writer sh -c 'cat "$TRAFFIC_STATE_PATH"' >"$state_path"
run_writer sh -c 'cat "$TRAFFIC_REFRESH_LEDGER_PATH"' >"$ledger_path"
docker compose --profile traffic logs --no-color \
--since "$activation_started_at" traffic-updater >"$updater_logs_path" 2>&1
docker compose --profile routing logs --no-color \
--since "$activation_started_at" valhalla >"$valhalla_logs_path" 2>&1

echo "[9/10] Validating on-demand update, deduplication and fallback invariants"
python3 scripts/validate-traffic-production-live.py \
--running-tileset-identity "$running_tileset_identity" \
--config "$config_path" \
--state "$state_path" \
--ledger "$ledger_path" \
--health "$health_path" \
--first-route "$first_route_path" \
--second-route "$second_route_path" \
--updater-logs "$updater_logs_path" \
--valhalla-logs "$valhalla_logs_path"

echo "[10/10] Confirming the internal updater, API and Valhalla remain healthy"
docker compose --profile traffic ps api traffic-updater valhalla
activation_accepted=true
trap - EXIT INT TERM

echo
echo "ON-DEMAND TRAFFIC ACTIVATION COMPLETED"
echo "TomTom was called for the first route and skipped for the immediate repeat."
echo "No periodic provider polling remains; the local expiry sweep stays active."
