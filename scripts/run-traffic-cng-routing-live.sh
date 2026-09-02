#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gate_port="${TRAFFIC_CNG_GATE_PORT:-18081}"
api_base_url="http://127.0.0.1:${gate_port}"
api_container_id=""
api_ready=false
valhalla_started_at=""
status_path=/tmp/compass-traffic-cng-status.json
api_health_path=/tmp/compass-traffic-cng-api-health.json
api_logs_path=/tmp/compass-traffic-cng-api.log
openapi_path=/tmp/compass-traffic-cng-openapi.json
base_path=/tmp/compass-traffic-cng-base.json
ranked_path=/tmp/compass-traffic-cng-ranked.json
selected_path=/tmp/compass-traffic-cng-selected.json
predictive_path=/tmp/compass-traffic-cng-predictive.json
valhalla_logs_path=/tmp/compass-traffic-cng-valhalla.log

cd "$repo_root"

cleanup() {
    local exit_status=$?
    if [[ -n "$api_container_id" ]]; then
        if [[ "$exit_status" -ne 0 ]]; then
            capture_api_logs
            if [[ -n "$valhalla_started_at" ]]; then
                docker compose --profile routing logs --no-color \
                    --since "$valhalla_started_at" valhalla \
                    >"$valhalla_logs_path" 2>&1 || true
            fi
            echo >&2
            echo "ERROR: traffic-aware CNG gate failed." >&2
            echo "Isolated API log: $api_logs_path" >&2
            echo "Valhalla log: $valhalla_logs_path" >&2
            for response_path in \
                "$base_path" "$ranked_path" "$selected_path" "$predictive_path"; do
                if [[ -s "$response_path" ]]; then
                    echo "Failed/last response in $response_path:" >&2
                    cat "$response_path" >&2
                    echo >&2
                fi
            done
        fi
        docker rm --force "$api_container_id" >/dev/null 2>&1 || true
    fi
    return "$exit_status"
}
trap cleanup EXIT INT TERM

capture_api_logs() {
    docker logs "$api_container_id" >"$api_logs_path" 2>&1 || true
}

report_api_start_failure() {
    capture_api_logs
    echo >&2
    echo "ERROR: the isolated API did not become ready." >&2
    echo "Container diagnostics were saved to $api_logs_path" >&2
    if [[ -s "$api_health_path" ]]; then
        echo "Last health response:" >&2
        cat "$api_health_path" >&2
        echo >&2
    fi
    echo "Isolated API logs:" >&2
    cat "$api_logs_path" >&2
    exit 1
}

echo "[1/10] Checking Valhalla and recording the read-only overlay checksum"
docker compose --profile routing exec -T valhalla \
curl --fail --silent --show-error http://127.0.0.1:8002/status >"$status_path"
traffic_tar_sha_before="$(
    docker compose --profile routing exec -T valhalla \
    sha256sum /custom_files/traffic.tar | awk '{print $1}'
)"
traffic_tileset_identity="$(python3 - "$status_path" <<'PY'
import json
import sys

status = json.load(open(sys.argv[1], encoding="utf-8"))
print(f"valhalla-{status['version']}:{int(status['tileset_last_modified'])}")
PY
)"
echo "tileset_identity=$traffic_tileset_identity"

echo "[2/10] Building the API containing the scheduled-departure contract"
docker compose build api

echo "[3/10] Starting an isolated traffic-aware API on 127.0.0.1:${gate_port}"
export TRAFFIC_ENABLED=true
export TRAFFIC_PROVIDER=tomtom
export TRAFFIC_VALHALLA_OVERLAY_ENABLED=true
export TRAFFIC_VALHALLA_TILESET_VERSION="$traffic_tileset_identity"
api_container_id="$(
    docker compose --profile traffic run --no-deps -d \
    -p "127.0.0.1:${gate_port}:8000" \
    api uvicorn compass.api.main:app --host 0.0.0.0 --port 8000
)"
: >"$api_health_path"
: >"$api_logs_path"
for _attempt in $(seq 1 45); do
    if curl --fail --silent --show-error \
        "${api_base_url}/health/ready" >"$api_health_path" 2>/dev/null; then
        api_ready=true
        break
    fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "$api_container_id" 2>/dev/null || true)" != "true" ]]; then
        report_api_start_failure
    fi
    sleep 2
done
if [[ "$api_ready" != "true" ]]; then
    curl --silent --show-error \
        "${api_base_url}/health/ready" >"$api_health_path" 2>/dev/null || true
    report_api_start_failure
fi

departure_at="$(date --iso-8601=seconds)"
valhalla_started_at="$departure_at"
echo "departure_at=$departure_at"

echo "[4/10] Checking OpenAPI and a scheduled base route"
curl --fail --silent --show-error "${api_base_url}/openapi.json" >"$openapi_path"
curl --fail-with-body --silent --show-error \
--header 'Content-Type: application/json' \
--data-binary @- \
"${api_base_url}/api/v1/routes" >"$base_path" <<JSON
{
"origin":{"latitude":45.4642,"longitude":9.1900},
"destination":{"latitude":44.4949,"longitude":11.3426},
"departure_at":"${departure_at}"
}
JSON

echo "[5/10] Calculating traffic-aware CNG eligibility and ranking"
curl --fail-with-body --silent --show-error \
--header 'Content-Type: application/json' \
--data-binary @- \
"${api_base_url}/api/v1/cng/ranked-candidates" >"$ranked_path" <<JSON
{
"origin":{"latitude":45.4642,"longitude":9.1900},
"destination":{"latitude":44.4949,"longitude":11.3426},
"effective_cng_range_km":300,
"maximum_detour_minutes":240,
"departure_at":"${departure_at}"
}
JSON
station_id="$(python3 - "$ranked_path" <<'PY'
import json
import sys

value = json.load(open(sys.argv[1], encoding="utf-8"))
print(value["candidates"][0]["mimit_station_id"])
PY
)"
echo "selected_mimit_station_id=$station_id"

echo "[6/10] Recomputing the selected-stop route at the same departure"
curl --fail-with-body --silent --show-error \
--header 'Content-Type: application/json' \
--data-binary @- \
"${api_base_url}/api/v1/routes/with-cng-stop" >"$selected_path" <<JSON
{
"origin":{"latitude":45.4642,"longitude":9.1900},
"destination":{"latitude":44.4949,"longitude":11.3426},
"mimit_station_id":"${station_id}",
"departure_at":"${departure_at}"
}
JSON

echo "[7/10] Exercising the multi-stop predictive traffic boundary"
curl --fail-with-body --silent --show-error \
--header 'Content-Type: application/json' \
--data-binary @- \
"${api_base_url}/api/v1/cng/predictive-candidates" >"$predictive_path" <<JSON
{
"origin":{"latitude":45.4642,"longitude":9.1900},
"destination":{"latitude":44.4949,"longitude":11.3426},
"effective_cng_range_km":100,
"estimated_remaining_cng_range_km":65,
"reserve_cng_range_km":30,
"maximum_detour_minutes":10,
"departure_at":"${departure_at}"
}
JSON

echo "[8/10] Capturing Valhalla route/matrix evidence"
docker compose --profile routing logs --no-color \
--since "$valhalla_started_at" valhalla >"$valhalla_logs_path"

echo "[9/10] Validating CNG traffic propagation and no double-counting contract"
python3 scripts/validate-traffic-cng-routing-live.py \
--departure-at "$departure_at" \
--openapi "$openapi_path" \
--base "$base_path" \
--ranked "$ranked_path" \
--selected "$selected_path" \
--predictive "$predictive_path" \
--valhalla-logs "$valhalla_logs_path"

echo "[10/10] Proving this gate did not modify traffic.tar"
traffic_tar_sha_after="$(
    docker compose --profile routing exec -T valhalla \
    sha256sum /custom_files/traffic.tar | awk '{print $1}'
)"
test "$traffic_tar_sha_before" = "$traffic_tar_sha_after"
echo "traffic_tar_sha256=$traffic_tar_sha_after (unchanged)"

docker rm --force "$api_container_id" >/dev/null
api_container_id=""
trap - EXIT INT TERM

echo
echo "TRAFFIC-AWARE CNG ROUTING CHECK COMPLETED"
echo "The isolated API is stopped and traffic.tar was not modified."
