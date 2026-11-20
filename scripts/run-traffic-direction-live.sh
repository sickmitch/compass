#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

cd "$repo_root"

echo "[1/2] Fetching and map-matching fresh TomTom base Flow Segment records"
bash scripts/run-traffic-matching-live.sh

echo
echo "[2/2] Decoding OpenLR and verifying provider geometry direction"
bash scripts/run-traffic-openlr-live.sh

echo
echo "READ-ONLY TOMTOM DIRECTION CHECK COMPLETED"
echo "No provider speed was written to traffic.tar."
