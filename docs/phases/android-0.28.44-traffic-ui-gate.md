# Android 0.28.44 — Traffic status and ETA colour

The preview's Live/Non live label now reads traffic freshness, not whether traffic
affects routing duration. The duplicate yellow warning is removed from the trip
summary. A fresh feed without a congestion flag shows “Non c'è traffico al momento.”;
detected congestion retains the optimal-conditions ETA warning without minutes.
Unavailable/stale data retain their explicit status rather than claiming zero traffic.

During navigation, detected congestion colours the ETA badge text and the compact
summary arrival time pastel orange (#FFBE8A). The journey progress ring stays green.
The display follows each updated route's traffic flag; ETA calculations are unchanged.

This UI consumes the backend traffic_detected flag. The current backend derives it
from a live-vs-baseline duration difference exceeding one second, and returns false
also on a failed diagnostic probe. A fresh feed alone does not certify route coverage
or successful probing; this pre-existing API limitation is not changed by this UI patch.

## Device acceptance

Local validation: Gradle testDebugUnitTest and assembleDebug passed; the compact
navigation regression pytest checks passed (2 tests). APK signature verification
and git diff --check passed. The build published the versioned APK and dist/last.txt.

Install dist/compass-0.28.44.apk. With a fresh traffic response, verify the preview
says Live even though traffic_aware is false. Check both traffic_detected values:
true shows the congestion warning and orange ETA; false shows the no-traffic message
and the usual ETA colour. The summary must have no duplicate yellow warning.
Open/close the compact navigation summary and verify its arrival time matches the
badge colour. Check stale/unavailable responses do not show the no-traffic message.
Return screenshots and the navigation traffic_state/traffic_detected response fields
if the result differs. Device acceptance is performed by the operator.
