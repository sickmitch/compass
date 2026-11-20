#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
result_path="/tmp/compass-traffic-matching-live.json"
status_path="/tmp/compass-traffic-matching-valhalla-status.json"
validator="$repo_root/scripts/validate-traffic-matching-live.py"

cd "$repo_root"

echo "[1/8] Checking the running Valhalla tileset identity"
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

echo "[2/8] Building the updater with Compass and the pinned native Valhalla decoder"
docker compose --profile traffic build traffic-updater

echo "[3/8] Checking that the TomTom key is available to Compose"
if ! docker compose --profile traffic run --rm --no-deps traffic-updater \
sh -c 'test -n "$TOMTOM_API_KEY"'; then
    echo "ERROR: TOMTOM_API_KEY is missing. Put it in .env or export it, then rerun." >&2
    exit 1
fi
echo "TomTom key is configured (value not displayed)."

echo "[4/8] Preparing TomTom base Flow Segment probes"
export TRAFFIC_ENABLED=true
export TRAFFIC_PROVIDER=tomtom
export TRAFFIC_VALHALLA_OVERLAY_ENABLED=false
export TOMTOM_TRAFFIC_API_MODE=flow_segment
export TOMTOM_FLOW_SEGMENT_OPENLR=true
export TOMTOM_FLOW_SEGMENT_POINTS="${TOMTOM_FLOW_SEGMENT_POINTS:-45.321004,9.376063;45.141970,9.634009;44.961684,9.905687}"

echo "[5/8] Recording traffic.tar checksum before the read-only diagnostic"
traffic_hash_before="$(
    docker compose --profile routing exec -T valhalla \
    sha256sum /custom_files/traffic.tar | awk '{print $1}'
)"

echo "[6/8] Fetching and matching normalized provider records"
docker compose --profile traffic run --rm --no-deps traffic-updater \
compass-traffic match-once --limit 10 >"$result_path"

echo "[7/8] Validating GraphIds, OpenLR direction, confidence and tileset binding"
python3 "$validator" --response "$result_path"

echo "[8/8] Proving that the read-only diagnostic did not modify traffic.tar"
traffic_hash_after="$(
    docker compose --profile routing exec -T valhalla \
    sha256sum /custom_files/traffic.tar | awk '{print $1}'
)"
test "$traffic_hash_before" = "$traffic_hash_after"
echo "traffic_tar_sha256=$traffic_hash_after (unchanged)"

echo
echo "READ-ONLY TRAFFIC MATCHING CHECK COMPLETED"
echo "Full result: $result_path"
echo "No provider speed was written to traffic.tar."
