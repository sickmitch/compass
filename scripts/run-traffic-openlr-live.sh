#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
matching_path="${TRAFFIC_MATCH_RESULT_PATH:-/tmp/compass-traffic-matching-live.json}"
references_path="/tmp/compass-traffic-openlr-references.txt"
validator="$repo_root/scripts/validate-traffic-openlr-live.py"

cd "$repo_root"

echo "[1/4] Checking the prior read-only TomTom matching result"
if [[ ! -s "$matching_path" ]]; then
    echo "ERROR: $matching_path is missing or empty." >&2
    echo "Run scripts/run-traffic-matching-live.sh first." >&2
    exit 1
fi

python3 - "$matching_path" >"$references_path" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
for result in payload.get("results", []):
    reference = result.get("provider_segment_id")
    if isinstance(reference, str) and reference:
        print(reference)
PY

if [[ ! -s "$references_path" ]]; then
    echo "ERROR: no TomTom OpenLR references were found in $matching_path." >&2
    exit 1
fi

echo "[2/4] Building the native helper against the pinned Valhalla image"
docker compose --profile traffic-tools build valhalla-traffic-tool

echo "[3/4] Decoding each OpenLR with Valhalla 3.8.3 definitions"
mapfile -t references <"$references_path"
decoded_paths=()
index=0
for reference in "${references[@]}"; do
    index=$((index + 1))
    decoded_path="/tmp/compass-traffic-openlr-decoded-${index}.json"
    docker compose --profile traffic-tools run --rm --no-deps valhalla-traffic-tool \
    decode-openlr --reference "$reference" >"$decoded_path" </dev/null
    decoded_paths+=("$decoded_path")
    echo "Decoded reference $index to $decoded_path"
done

echo "[4/4] Validating native round trips, ordered LRPs and direction semantics"
validator_args=(--matching "$matching_path")
for decoded_path in "${decoded_paths[@]}"; do
    validator_args+=(--decoded "$decoded_path")
done
python3 "$validator" "${validator_args[@]}"

echo
echo "READ-ONLY OPENLR DECODING CHECK COMPLETED"
echo "No provider speed was written to traffic.tar."
