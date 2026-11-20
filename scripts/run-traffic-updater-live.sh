#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
status_path=/tmp/compass-traffic-updater-status.json
state_path=/tmp/compass-traffic-updater-state.json
cleared_state_path=/tmp/compass-traffic-updater-cleared-state.json
inspect_set_path=/tmp/compass-traffic-updater-inspect-set.json
inspect_reset_path=/tmp/compass-traffic-updater-inspect-reset.json
clear_path=/tmp/compass-traffic-updater-clear.json
logs_path=/tmp/compass-traffic-updater.log
fresh_health_path=/tmp/compass-traffic-updater-health-fresh.json
cleared_health_path=/tmp/compass-traffic-updater-health-cleared.json
health_gate_port="${TRAFFIC_HEALTH_GATE_PORT:-18080}"
health_api_container_id=""
cleanup_needed=true
cleanup_armed=false

cd "$repo_root"

run_writer() {
    docker compose --profile traffic run --rm --no-deps --user 0:0 \
    traffic-updater "$@"
}

cleanup() {
    if [[ -n "$health_api_container_id" ]]; then
        docker stop "$health_api_container_id" >/dev/null 2>&1 || true
    fi
    if "$cleanup_armed"; then
        docker compose --profile traffic stop traffic-updater >/dev/null 2>&1 || true
        if "$cleanup_needed"; then
            echo "Emergency cleanup: resetting every Compass-managed traffic edge" >&2
            run_writer compass-traffic clear-managed >"$clear_path" || {
                echo "CRITICAL: emergency traffic cleanup failed; return $clear_path and logs." >&2
            }
        fi
        docker compose --profile routing restart valhalla >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

echo "[1/12] Checking the running Valhalla tileset identity"
docker compose --profile routing exec -T valhalla \
curl --fail --silent --show-error http://127.0.0.1:8002/status >"$status_path"
traffic_tileset_identity="$(python3 - "$status_path" <<'PY'
import json
import sys

status = json.load(open(sys.argv[1], encoding="utf-8"))
print(f"valhalla-{status['version']}:{int(status['tileset_last_modified'])}")
PY
)"
export TRAFFIC_VALHALLA_TILESET_VERSION="$traffic_tileset_identity"
echo "tileset_identity=$traffic_tileset_identity"

echo "[2/12] Enabling one controlled TomTom probe for the periodic service"
export TRAFFIC_ENABLED=true
export TRAFFIC_PROVIDER=tomtom
export TRAFFIC_VALHALLA_OVERLAY_ENABLED=true
export TRAFFIC_REFRESH_SECONDS="${TRAFFIC_REFRESH_SECONDS:-15}"
export TRAFFIC_UPDATE_SEGMENT_LIMIT=1
export TRAFFIC_STATE_PATH=/custom_files/compass_traffic_state/state.json
export TRAFFIC_HEALTH_PATH=/custom_files/compass_traffic_state/health.json
export TOMTOM_TRAFFIC_API_MODE=flow_segment
export TOMTOM_FLOW_SEGMENT_OPENLR=true
export TOMTOM_FLOW_SEGMENT_POINTS="${TOMTOM_FLOW_SEGMENT_POINTS:-45.321004,9.376063}"
if ! docker compose --profile traffic run --rm --no-deps traffic-updater \
sh -c 'test -n "$TOMTOM_API_KEY"'; then
    echo "ERROR: TOMTOM_API_KEY is missing. Configure the rotated key and rerun." >&2
    exit 1
fi
cleanup_armed=true

echo "[3/12] Building the updater/API and clearing prior managed test state"
docker compose --profile traffic build traffic-updater api
run_writer compass-traffic clear-managed >"$clear_path"

echo "[4/12] Starting the hardened long-running traffic updater"
docker compose --profile traffic up -d --no-deps traffic-updater

echo "[5/12] Waiting up to 90 seconds for a committed periodic transaction"
state_ready=false
for _attempt in $(seq 1 45); do
    if docker compose --profile traffic exec -T traffic-updater \
    sh -c 'cat "$TRAFFIC_STATE_PATH"' >"$state_path" 2>/dev/null; then
        if python3 - "$state_path" <<'PY'
import json
import sys

value = json.load(open(sys.argv[1], encoding="utf-8"))
raise SystemExit(0 if value.get("edges") else 1)
PY
        then
            state_ready=true
            break
        fi
    fi
    sleep 2
done
if ! "$state_ready"; then
    docker compose --profile traffic logs --no-color traffic-updater >"$logs_path"
    echo "ERROR: updater did not persist a managed edge; return $logs_path." >&2
    exit 1
fi

selected_graph_id="$(python3 - "$state_path" <<'PY'
import json
import sys

value = json.load(open(sys.argv[1], encoding="utf-8"))
print(value["edges"][0]["graph_id"])
PY
)"
echo "selected_graph_id=$selected_graph_id"

echo "[6/12] Starting an isolated API instance against the read-only health volume"
health_api_container_id="$(
    docker compose --profile traffic run --rm --no-deps -d \
    -p "127.0.0.1:${health_gate_port}:8000" \
    api uvicorn compass.api.main:app --host 0.0.0.0 --port 8000
)"
health_ready=false
for _attempt in $(seq 1 30); do
    if curl --fail --silent --show-error \
    "http://127.0.0.1:${health_gate_port}/api/v1/traffic/health" \
    >"$fresh_health_path" 2>/dev/null; then
        health_ready=true
        break
    fi
    sleep 1
done
if ! "$health_ready"; then
    echo "ERROR: isolated API did not expose traffic health on port $health_gate_port." >&2
    exit 1
fi

echo "[7/12] Stopping the updater before inspecting and cleaning its transaction"
docker compose --profile traffic stop traffic-updater
docker compose --profile traffic logs --no-color traffic-updater >"$logs_path"
run_writer compass-valhalla-traffic-tool inspect \
--traffic-tar /custom_files/traffic.tar \
--graph-id "$selected_graph_id" >"$inspect_set_path"

echo "[8/12] Resetting all periodically managed edges to UNKNOWN"
run_writer compass-traffic clear-managed >"$clear_path"
cleanup_needed=false

echo "[9/12] Verifying empty durable state, UNKNOWN speed and fallback health"
run_writer sh -c 'cat "$TRAFFIC_STATE_PATH"' >"$cleared_state_path"
run_writer compass-valhalla-traffic-tool inspect \
--traffic-tar /custom_files/traffic.tar \
--graph-id "$selected_graph_id" >"$inspect_reset_path"
curl --fail --silent --show-error \
"http://127.0.0.1:${health_gate_port}/api/v1/traffic/health" \
>"$cleared_health_path"

echo "[10/12] Validating periodic update, dynamic health and cleanup invariants"
python3 scripts/validate-traffic-updater-live.py \
--state "$state_path" \
--health-fresh "$fresh_health_path" \
--inspect-set "$inspect_set_path" \
--logs "$logs_path" \
--clear "$clear_path" \
--cleared-state "$cleared_state_path" \
--health-cleared "$cleared_health_path" \
--inspect-reset "$inspect_reset_path"

echo "[11/12] Restarting Valhalla on the clean UNKNOWN overlay"
docker compose --profile routing restart valhalla
echo "[12/12] Stopping the isolated health API"
docker stop "$health_api_container_id" >/dev/null
health_api_container_id=""
trap - EXIT INT TERM

echo
echo "PERIODIC TRAFFIC UPDATER CHECK COMPLETED"
echo "Fresh/fallback health was verified; updater is stopped, edges are UNKNOWN and state is empty."
