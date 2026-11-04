package org.compass.cng.enforcement

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.compass.cng.data.api.ApiClientException
import org.compass.cng.domain.geometry.Polyline6Encoder
import org.compass.cng.domain.model.*
import org.compass.cng.navigation.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApproachCoordinatorTest {
    private val shape = listOf(Coordinate(45.0, 9.0), Coordinate(45.0, 9.004))
    private val encoded = Polyline6Encoder.encode(shape)
    private val time = 1_000_000L
    private fun fix(i: Int) = NavigationLocation(Coordinate(45.0, 9.0 + i * .0002),
        5.0, 20.0, 90.0, time + i * 2_000)
    private fun pack() = EnforcementPackage(1, "graph", "2026-11-04T10:00:00Z",
        45.0, 9.0, 3000, "control_location", listOf(EnforcementControl(
            "osm:relation:1", "osm:node:2", "1", "fixed", 45.0, 9.004, null, 50.0,
            "2026-11-04T10:00:00Z", "matched", "fixture",
            EnforcementPath("10", true, "1", "2", listOf("99"), encoded, 300.0), false)),
        false, "© OpenStreetMap contributors — ODbL 1.0")
    private fun response() = ApproachResult("approach", "directed_approach", "graph",
        "2026-11-04T10:00:00Z", ApproachControl("osm:relation:1", "osm:node:2", "fixed",
            "10", true, 50.0, 280.0, encoded, 45.0, 9.004))
    private class MemoryCache : EnforcementCache {
        override fun read(): SavedEnforcementPackage? = null
        override fun write(value: SavedEnforcementPackage) = Unit
        override fun clear() = Unit
    }
    private fun active() = NavigationState(phase = NavigationPhase.NAVIGATING,
        route = RoutePreview(shape.first(), shape.last(), 315.0, 30.0, shape,
            emptyList(), "valhalla").toNavigationRoute(),
        navigationPosition = NavigationPosition(shape.first(), 0, 20.0, 90.0, 5.0, time))

    @Test fun freeFollowAndActiveNavigationShareCadenceAndOneSpeechPerPassage() = runTest {
        for (navigation in listOf(NavigationState(), active())) {
            var clock = time
            var calls = 0
            val repo = EnforcementRepository({ "owner" }, { pack() }, MemoryCache(), { clock })
            repo.update(fix(0))
            val coordinator = ApproachCoordinator(repo, { calls++; response() }, { "owner" },
                { navigation }, this, { clock }, StandardTestDispatcher(testScheduler))
            for (i in 0..2) { clock = fix(i).timestampEpochMillis; coordinator.update(fix(i)); runCurrent() }
            assertEquals(1, calls)
            assertNotNull(coordinator.notice.value)
            assertNotNull(coordinator.claimSpeech(true))
            repeat(10) { coordinator.update(fix(2)); runCurrent() }
            assertEquals(1, calls)
            assertNull(coordinator.claimSpeech(true))
            for (i in 3..6) { clock = fix(i).timestampEpochMillis; coordinator.update(fix(i)); runCurrent() }
            assertEquals(2, calls)
            assertNull(coordinator.claimSpeech(true))
        }
    }

    @Test fun responseFromPreviousServerOrNavigationModeIsDiscarded() = runTest {
        for (changeOwner in listOf(true, false)) {
            var clock = time
            var owner = "first"
            var nav = NavigationState()
            val pending = CompletableDeferred<ApproachResult>()
            val repo = EnforcementRepository({ owner }, { pack() }, MemoryCache(), { clock })
            repo.update(fix(0))
            val coordinator = ApproachCoordinator(repo, { pending.await() }, { owner }, { nav },
                this, { clock }, StandardTestDispatcher(testScheduler))
            for (i in 0..2) { clock = fix(i).timestampEpochMillis; coordinator.update(fix(i)); runCurrent() }
            if (changeOwner) owner = "second" else nav = active()
            pending.complete(response()); runCurrent()
            assertNull(coordinator.notice.value)
            assertNull(coordinator.claimSpeech(true))
        }
    }

    @Test fun networkFailureRetainsVerifiedCorridorButAuthenticationFailureClearsIt() = runTest {
        for (failure in listOf(ApiClientException.Network(IOException("offline")),
            ApiClientException.Http(401, "unauthorized"))) {
            var clock = time
            var calls = 0
            val repo = EnforcementRepository({ "owner" }, { pack() }, MemoryCache(), { clock })
            repo.update(fix(0))
            val coordinator = ApproachCoordinator(repo, {
                calls++; if (calls == 1) response() else throw failure
            }, { "owner" }, { NavigationState() }, this, { clock }, StandardTestDispatcher(testScheduler))
            for (i in 0..6) { clock = fix(i).timestampEpochMillis; coordinator.update(fix(i)); runCurrent() }
            assertEquals(2, calls)
            assertEquals(failure is ApiClientException.Network, coordinator.notice.value != null)
        }
    }

    @Test fun unknownRoadDataStationaryAndNonDrivingNavigationNeverRequestApproach() = runTest {
        for (mode in listOf("unknown", "stationary", "walking", "rerouting")) {
            var clock = time
            val data = if (mode == "unknown") pack().copy(controls = pack().controls.map {
                it.copy(associationState = "unmatched", directedPath = null)
            }) else pack()
            val nav = when (mode) {
                "walking" -> active().let { it.copy(route = it.route!!.copy(travelMode = RouteTravelMode.WALKING)) }
                "rerouting" -> active().copy(phase = NavigationPhase.REROUTING)
                else -> NavigationState()
            }
            val repo = EnforcementRepository({ "owner" }, { data }, MemoryCache(), { clock })
            repo.update(fix(0))
            val coordinator = ApproachCoordinator(repo, { error("Unexpected approach call") }, { "owner" },
                { nav }, this, { clock }, StandardTestDispatcher(testScheduler))
            for (i in 0..6) {
                clock = fix(i).timestampEpochMillis
                coordinator.update(if (mode == "stationary") fix(0).copy(timestampEpochMillis = clock) else fix(i))
                runCurrent()
            }
            assertNull(coordinator.notice.value)
        }
    }
}
