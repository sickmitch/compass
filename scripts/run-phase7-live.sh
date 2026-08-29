#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

api_url="${COMPASS_API_URL:-http://127.0.0.1:8000}"
phase7_started_at="$(date --iso-8601=seconds)"
phase7_step="preparing request files"

trap 'status=$?; printf "\nPhase 7 live gate FAILED during: %s (exit %s)\n" "$phase7_step" "$status" >&2; exit "$status"' ERR

cat > /tmp/compass-phase7-base-route-request.json <<'JSON'
{
  "origin": {
    "latitude": 45.4642,
    "longitude": 9.1900
  },
  "destination": {
    "latitude": 44.4949,
    "longitude": 11.3426
  }
}
JSON

cat > /tmp/compass-phase7-ranked-request.json <<'JSON'
{
  "origin": {
    "latitude": 45.4642,
    "longitude": 9.1900
  },
  "destination": {
    "latitude": 44.4949,
    "longitude": 11.3426
  },
  "effective_cng_range_km": 300,
  "maximum_detour_minutes": 10,
  "departure_at": "2026-08-30T10:00:00+02:00"
}
JSON

cat > /tmp/compass-phase7-selected-route-request.json <<'JSON'
{
  "origin": {
    "latitude": 45.4642,
    "longitude": 9.1900
  },
  "destination": {
    "latitude": 44.4949,
    "longitude": 11.3426
  },
  "mimit_station_id": "43690"
}
JSON

cat > /tmp/compass-phase7-invalid-request.json <<'JSON'
{
  "origin": {
    "latitude": 45.4642,
    "longitude": 9.1900
  },
  "destination": {
    "latitude": 44.4949,
    "longitude": 11.3426
  },
  "mimit_station_id": "not-a-number"
}
JSON

phase7_step="[1/9] readiness"
printf '\n%s\n' "$phase7_step"
curl --fail-with-body --silent --show-error \
  --output /tmp/compass-phase7-health.json \
  "$api_url/health/ready"

phase7_step="[2/9] base route"
printf '%s\n' "$phase7_step"
curl --fail-with-body --silent --show-error \
  --header 'Content-Type: application/json' \
  --data-binary @/tmp/compass-phase7-base-route-request.json \
  --output /tmp/compass-phase7-base-route.json \
  "$api_url/api/v1/routes"

phase7_step="[3/9] ranked candidates"
printf '%s\n' "$phase7_step"
curl --fail-with-body --silent --show-error \
  --header 'Content-Type: application/json' \
  --data-binary @/tmp/compass-phase7-ranked-request.json \
  --output /tmp/compass-phase7-ranked.json \
  "$api_url/api/v1/cng/ranked-candidates"

phase7_step="[4/9] station detail"
printf '%s\n' "$phase7_step"
curl --fail-with-body --silent --show-error \
  --get \
  --data-urlencode 'arrival_at=2026-08-30T10:19:11+02:00' \
  --output /tmp/compass-phase7-station.json \
  "$api_url/api/v1/cng/stations/43690"

phase7_step="[5/9] route through selected station"
printf '%s\n' "$phase7_step"
curl --fail-with-body --silent --show-error \
  --header 'Content-Type: application/json' \
  --data-binary @/tmp/compass-phase7-selected-route-request.json \
  --output /tmp/compass-phase7-selected-route.json \
  "$api_url/api/v1/routes/with-cng-stop"

phase7_step="[6/9] data freshness and OpenAPI"
printf '%s\n' "$phase7_step"
curl --fail-with-body --silent --show-error \
  --output /tmp/compass-phase7-freshness.json \
  "$api_url/api/v1/data-freshness"
curl --fail-with-body --silent --show-error \
  --output /tmp/compass-phase7-openapi.json \
  "$api_url/openapi.json"

phase7_step="[7/9] expected 404 and 422"
printf '%s\n' "$phase7_step"
phase7_not_found_http="$(
  curl --silent --show-error \
    --output /tmp/compass-phase7-not-found.json \
    --write-out '%{http_code}' \
    "$api_url/api/v1/cng/stations/99999999999999999999999999999999"
)"
phase7_invalid_http="$(
  curl --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary @/tmp/compass-phase7-invalid-request.json \
    --output /tmp/compass-phase7-invalid.json \
    --write-out '%{http_code}' \
    "$api_url/api/v1/routes/with-cng-stop"
)"
if [[ "$phase7_not_found_http" != "404" ]]; then
  printf 'Expected station-detail HTTP 404, received %s\n' "$phase7_not_found_http" >&2
  exit 1
fi
if [[ "$phase7_invalid_http" != "422" ]]; then
  printf 'Expected invalid-request HTTP 422, received %s\n' "$phase7_invalid_http" >&2
  exit 1
fi
printf 'not_found_http=%s invalid_http=%s\n' \
  "$phase7_not_found_http" \
  "$phase7_invalid_http"

phase7_step="[8/9] contract verifier"
printf '%s\n' "$phase7_step"
python3 scripts/validate-phase7-live.py \
  --health /tmp/compass-phase7-health.json \
  --base-route /tmp/compass-phase7-base-route.json \
  --ranked /tmp/compass-phase7-ranked.json \
  --station /tmp/compass-phase7-station.json \
  --selected-route /tmp/compass-phase7-selected-route.json \
  --freshness /tmp/compass-phase7-freshness.json \
  --not-found /tmp/compass-phase7-not-found.json \
  --invalid /tmp/compass-phase7-invalid.json \
  --openapi /tmp/compass-phase7-openapi.json

phase7_step="[9/9] bounded runtime evidence"
printf '\n%s\n' "$phase7_step"
docker compose --profile routing logs --no-color \
  --since="$phase7_started_at" valhalla \
  | grep -E 'POST /route|POST /sources_to_targets' || true
docker compose --profile routing logs --no-color \
  --since="$phase7_started_at" api \
  | grep -E 'GET /api/v1/cng/stations|POST /api/v1/routes|POST /api/v1/cng/ranked-candidates|GET /api/v1/data-freshness|GET /health/ready' || true
docker compose exec -T api alembic current
docker compose exec -T api python -c \
  'from importlib.metadata import metadata; print(metadata("compass-cng")["License-Expression"])'

phase7_step="complete"
printf '\nPhase 7 live gate completed successfully.\n'
