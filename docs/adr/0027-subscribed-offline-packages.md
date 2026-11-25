# ADR 0027: subscribed offline routing packages

Status: implemented; deployment and Android field acceptance pending.

## Decision

Reuse the weekly publisher's immutable ZIPs and complete-set manifest. FastAPI mounts the
project's `offline_assets` directory read-only, lists the latest complete set through the
existing authenticated API, and streams only explicitly published files. The HTTP Range
protocol and SHA-256 ETag support interrupted downloads without a separate storage service.
No public directory listing, arbitrary file paths or download URLs are accepted.

Android reuses the connection profile, OkHttp transport, private graph store, tile verification
and native route probe. Subscriptions belong to a hash of server URL and username. Selecting
an area opts into downloads and updates; turning updates off retains the installed data.
Removing an installed subscribed package cancels its subscription.

A new complete subscription set is staged and verified before one atomic catalog replacement.
The same catalog stores dataset references and subscriptions. Any failed download, failed
probe, cancellation, changed subscription or insufficient space leaves the old catalog intact.
Old packages are reclaimed after commit; abandoned staging is reclaimed after process restart.
Areas from other manually imported families remain independent, as specified in ADR 0026.

Use Android JobScheduler without a new dependency. It persists work across reboot, requires
an unmetered network and adequate battery, and starts the weekly check from Monday 06:00 in
Europe/Rome. Missed checks run when Android permits; stale publications/failures retry after
six hours. OS interruption uses backoff and Range resumption. The service cancels its coroutine
and socket when stopped. Android controls actual execution time and runtime quotas; this is
not an exact-time alarm. See the [platform scheduling contract](https://developer.android.com/reference/android/app/job/JobScheduler).

Manual downloads (including updates) ask before transfers of at least 100 MiB on a mobile or metered
non-Wi-Fi connection. Wi-Fi never shows that dialog. Approval is scoped to the current server
and announced total size; larger/new transfers ask again. A Wi-Fi-to-mobile transition without
approval pauses the transfer and offers confirmation. Automatic jobs never opt into mobile data.

## Consequences and validation

Downloaded ZIPs temporarily coexist with the installed graphs and staged new graphs. This
costs extra disk space but preserves the previous usable generation. Native installation is
serialized with offline routing, so a recalculation can wait for an ongoing import. Large
transfers can span multiple Android job executions; throughput, battery impact and complete
Italy import duration still require device measurement. Manual transfers are app-owned and
can stop if Android kills the app; the next manual or scheduled attempt resumes verified
partial downloads. No audio, GPS or location data is needed by this feature.

See the live acceptance steps in `docs/offline-routing.md`. Unit tests cover catalog and
Range/auth contracts, common-family atomic replacement, rollback, subscriptions, calendar
windows, display names, checksum rejection, resumption and mobile confirmation policy.
