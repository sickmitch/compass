#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
status_path="/tmp/compass-traffic-writer-valhalla-status.json"
apply_path="/tmp/compass-traffic-writer-apply.json"
state_path="/tmp/compass-traffic-writer-state.json"
inspect_set_path="/tmp/compass-traffic-writer-inspect-set.json"
clear_path="/tmp/compass-traffic-writer-clear.json"
cleared_state_path="/tmp/compass-traffic-writer-cleared-state.json"
inspect_reset_path="/tmp/compass-traffic-writer-inspect-reset.json"
cleanup_path="/tmp/compass-traffic-writer-emergency-clear.json"

cd "$repo_root"

echo "[1/11] Checking the running Valhalla tileset identity"
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

echo "[2/11] Building the pinned traffic updater and native writer"
docker compose --profile traffic build traffic-updater

echo "[3/11] Checking configuration without displaying credentials"
if ! docker compose --profile traffic run --rm --no-deps traffic-updater \
sh -c 'test -n "$TOMTOM_API_KEY"'; then
    echo "ERROR: TOMTOM_API_KEY is missing. Configure the rotated key and rerun." >&2
    exit 1
fi

export TRAFFIC_ENABLED=true
export TRAFFIC_PROVIDER=tomtom
export TRAFFIC_VALHALLA_OVERLAY_ENABLED=true
export TOMTOM_TRAFFIC_API_MODE=flow_segment
export TOMTOM_FLOW_SEGMENT_OPENLR=true
export TOMTOM_FLOW_SEGMENT_POINTS="${TOMTOM_FLOW_SEGMENT_POINTS:-45.321004,9.376063}"

run_writer() {
    docker compose --profile traffic run --rm --no-deps --user 0:0 \
    traffic-updater "$@"
}

cleanup_needed=true
cleanup() {
    if "$cleanup_needed"; then
        echo "Emergency cleanup: resetting every Compass-managed traffic edge" >&2
        run_writer compass-traffic clear-managed >"$cleanup_path" || {
            echo "CRITICAL: emergency traffic cleanup failed; return $cleanup_path and logs." >&2
        }
    fi
    docker compose --profile routing restart valhalla >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "[4/11] Clearing any prior Compass-managed test state"
run_writer compass-traffic clear-managed >"$clear_path"

echo "[5/11] Applying one TomTom probe as one native batch transaction"
run_writer compass-traffic apply-once --limit 1 >"$apply_path"

echo "[6/11] Reading the persisted tileset-bound managed-edge state"
run_writer sh -c 'cat "$TRAFFIC_STATE_PATH"' >"$state_path"
selected_graph_id="$(python3 - "$state_path" <<'PY'
import json
import sys

state = json.load(open(sys.argv[1], encoding="utf-8"))
print(state["edges"][0]["graph_id"])
PY
)"
echo "selected_graph_id=$selected_graph_id"

echo "[7/11] Inspecting the TrafficSpeed written to traffic.tar"
run_writer compass-valhalla-traffic-tool inspect \
--traffic-tar /custom_files/traffic.tar \
--graph-id "$selected_graph_id" >"$inspect_set_path"

echo "[8/11] Resetting every managed edge to Valhalla UNKNOWN"
run_writer compass-traffic clear-managed >"$clear_path"
cleanup_needed=false

echo "[9/11] Verifying the cleared state and reset TrafficSpeed"
run_writer sh -c 'cat "$TRAFFIC_STATE_PATH"' >"$cleared_state_path"
run_writer compass-valhalla-traffic-tool inspect \
--traffic-tar /custom_files/traffic.tar \
--graph-id "$selected_graph_id" >"$inspect_reset_path"

echo "[10/11] Validating transaction, persistence and cleanup invariants"
python3 scripts/validate-traffic-writer-live.py \
--apply "$apply_path" \
--state "$state_path" \
--inspect-set "$inspect_set_path" \
--clear "$clear_path" \
--cleared-state "$cleared_state_path" \
--inspect-reset "$inspect_reset_path"

echo "[11/11] Restarting Valhalla on the clean UNKNOWN overlay"
docker compose --profile routing restart valhalla
trap - EXIT INT TERM

echo
echo "CONTROLLED TRAFFIC WRITER CHECK COMPLETED"
echo "All test-managed edges were reset to UNKNOWN and durable state is empty."
