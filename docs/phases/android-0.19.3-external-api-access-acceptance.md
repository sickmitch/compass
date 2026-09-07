# Android 0.19.3 external API access acceptance

Status: repository implementation complete; operator deployment/device gate pending.

## Scope and invariants

- Compose can keep the API on loopback for same-host ingress or publish it on an explicitly selected
  interface through `API_BIND_ADDRESS`; the operator owns routing, DNS and TLS.
- `API_AUTH_ENABLED=true` protects every `/api/v1` router with constant-time HTTP Basic credential
  comparison. Invalid credentials return `401 invalid_credentials`.
- `/health/live` and `/health/ready` remain public for infrastructure monitoring.
- Android exposes `Server` even when the initial route request fails.
- The app prefers HTTPS. HTTP requires an explicit cleartext warning acknowledgement.
- Endpoint, username and encrypted password persist across process/device restart. The password is
  encrypted with Android Keystore, Android backup is disabled, and no credential is logged.
- The API client reads the persisted profile for every call, including foreground-service reroutes;
  no APK rebuild or app restart is required after saving.

## Server deployment gate

From the server repository root, place strong values only in the uncommitted `.env`:

```dotenv
API_AUTH_ENABLED=true
API_AUTH_USERNAME=compass-mobile
API_AUTH_PASSWORD=<strong-generated-password>
API_BIND_ADDRESS=127.0.0.1
```

Use `0.0.0.0` instead of loopback only if the operator's ingress cannot reach the loopback-published
port. Then run:

```bash
docker compose build api
docker compose up -d api
docker compose ps api
curl --fail --silent --show-error http://127.0.0.1:8000/health/live
curl --silent --output /tmp/compass-auth-missing.json \
  --write-out '%{http_code}\n' http://127.0.0.1:8000/api/v1/auth/check
read -r -p 'Compass API username: ' COMPASS_CHECK_USER
read -r -s -p 'Compass API password: ' COMPASS_CHECK_PASSWORD
printf '\n'
curl --fail --silent --show-error \
  --user "$COMPASS_CHECK_USER:$COMPASS_CHECK_PASSWORD" \
  http://127.0.0.1:8000/api/v1/auth/check
unset COMPASS_CHECK_USER COMPASS_CHECK_PASSWORD
```

Expected: API healthy; unauthenticated check prints `401`; authenticated response reports
`authenticated=true` and `auth_enabled=true` without echoing either credential.

## Android device gate

On the workstation with the authorized Android device, run from the repository root:

```bash
export JAVA_HOME=/home/mike/toolchains/jdk17
export ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk
bash scripts/install-android-0.19.3.sh
```

Set `COMPASS_ADB_SERIAL` first only when more than one authorized device is connected. The installer
builds, installs with `adb install -r`, preserves any existing server profile, cold-launches Compass
and performs no UIAutomator/screenshot inspection.

In Compass tap `Server`, enter the operator-provided HTTPS URL,
username and password, then tap `Salva e connetti`. Confirm the route preview reloads. Force-stop and
reopen the app and confirm it reconnects without re-entering the profile. Disconnect USB/ADB, switch
to mobile data, calculate a route and trigger a safe off-route reroute. The replacement must commit
and its ten-second duration-difference notice must appear.

Do not return the `.env`, username, password, Authorization header or reverse-proxy private keys.
Return `docker compose ps api`, the two status/result bodies with secrets omitted, and the operator's
device confirmation. On failure also return bounded API logs:

```bash
docker compose logs --no-color --tail=150 api
```

Do not close Android 0.19.3 until this gate and the off-route road gate pass or are explicitly waived.

## Operator reachability update

On 2026-09-07 the operator confirmed that the Compass endpoint became reachable from the external
network after correcting the operator-managed Pangolin target. No reverse-proxy configuration or
credential was added to this repository. This confirms public reachability only; the Android
authenticated-route and disconnected-device reroute checks remain governed by the device gate
above.
