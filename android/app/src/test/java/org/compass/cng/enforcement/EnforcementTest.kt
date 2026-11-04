package org.compass.cng.enforcement

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.compass.cng.data.api.ApiClientException
import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.server.ServerConnection
import org.compass.cng.navigation.NavigationLocation
import org.junit.Assert.*
import org.junit.Test

class EnforcementTest {
    private val time = Instant.parse("2026-11-02T20:00:00Z").toEpochMilli()
    private val json = Json { ignoreUnknownKeys = false }
    private fun control(key: String = "osm:relation:1") = EnforcementControl(
        key, "osm:node:42", "2", "fixed", 45.0, 9.0, null, 50.0,
        "2026-11-02T19:00:00Z", "matched", "fixture",
        EnforcementPath("10", true, "1", "2", listOf("9007199254740993"),
            "_sqytA_cidP?o}@", 150.0), false,
    )
    private fun pack(position: Coordinate = Coordinate(45.0, 9.0)) = EnforcementPackage(
        1, "3.8.3:123", "2026-11-02T19:30:00Z", position.latitude, position.longitude,
        3000, "control_location", listOf(control()), false,
        "© OpenStreetMap contributors — ODbL 1.0",
    )
    private fun location(at: Long = time, lon: Double = 9.0) = NavigationLocation(
        Coordinate(45.0, lon), 5.0, 10.0, 90.0, at,
    )
    private class MemoryCache(var value: SavedEnforcementPackage? = null) : EnforcementCache {
        override fun read() = value
        override fun write(value: SavedEnforcementPackage) { this.value = value }
        override fun clear() { value = null }
    }

    @Test fun oppositeDirectionsStaySeparateInCacheButUseOneMapIcon() {
        val first = control()
        val second = control("osm:relation:2").copy(directedPath = first.directedPath!!.copy(
            forward = false, fromNode = "2", toNode = "1", edgeIds = listOf("999"),
        ))
        val data = pack().copy(controls = listOf(first, second))
        val decoded = json.decodeFromString<EnforcementPackage>(json.encodeToString(data)).validated()
        assertEquals(2, decoded.controls.size)
        assertEquals("9007199254740993", decoded.controls[0].directedPath!!.edgeIds[0])
        assertFalse(decoded.controls[1].directedPath!!.forward)
        assertEquals(1, decoded.mapControls().size)
        assertEquals(50.0, decoded.mapControls()[0].speedLimitKph!!, 0.0)
        val conflict = data.copy(controls = listOf(first, second.copy(speedLimitKph = 90.0)))
        assertNull(conflict.mapControls()[0].speedLimitKph)
    }

    @Test fun rejectsUnknownSchemaStaleGeometryAndPrematureAlertEligibility() {
        for (data in listOf(
            pack().copy(schemaVersion = 2),
            pack().copy(controls = listOf(control().copy(alertEligible = true))),
            pack().copy(controls = listOf(control().copy(associationState = "stale"))),
            pack().copy(controls = listOf(control().copy(directedPath = null))),
            pack().copy(controls = listOf(control().copy(latitude = Double.NaN))),
            pack().copy(controls = listOf(control(), control())),
        )) assertThrows(IllegalArgumentException::class.java) { data.validated() }
    }

    @Test fun sharesCacheAcrossMapModesAndThrottlesMovement() = runTest {
        var clock = time
        var calls = 0
        val cache = MemoryCache()
        val repo = EnforcementRepository({ "owner" }, { calls++; pack(it) }, cache, { clock })
        repo.update(location(clock))
        repeat(30) { repo.update(location(clock)) }
        assertEquals(1, calls)
        clock += 30_000
        repo.update(location(clock, 9.02))
        assertEquals(1, calls)
        clock += 30_000
        repo.update(location(clock, 9.02))
        assertEquals(2, calls)
        assertEquals(9.02, cache.value!!.data.longitude, 0.0)
        val restored = EnforcementRepository({ "owner" }, { error("Unexpected fetch") }, cache, { clock })
        restored.update(location(clock, 9.02))
        assertTrue(restored.state.value.saved)
        assertNotNull(restored.state.value.data)
    }

    @Test fun refreshesWhileStationaryAndRetainsLastAreaOnFailure() = runTest {
        var clock = time
        var calls = 0
        val repo = EnforcementRepository({ "owner" }, {
            calls++
            if (calls > 1) error("offline") else pack(it)
        }, MemoryCache(), { clock })
        repo.update(location(clock))
        clock += EnforcementRepository.REFRESH_INTERVAL
        repo.update(location(clock))
        assertEquals(2, calls)
        assertTrue(repo.state.value.unavailable)
        assertNotNull(repo.state.value.data)
        repo.update(location(clock, 9.1))
        assertEquals(2, calls)
        assertNull(repo.state.value.data)
    }

    @Test fun expiresDiskCacheAndRejectsClockRollbackOrOtherServer() = runTest {
        for ((owner, savedAt) in listOf(
            "owner" to time - EnforcementRepository.CACHE_LIFETIME - 1,
            "owner" to time + 1,
            "other" to time,
        )) {
            val cache = MemoryCache(SavedEnforcementPackage(owner, savedAt, pack()))
            val repo = EnforcementRepository({ "owner" }, { error("offline") }, cache, { time })
            repo.update(location())
            assertNull(repo.state.value.data)
        }
    }

    @Test fun invalidGpsDoesNotFetchOrExposeAnotherArea() = runTest {
        val repo = EnforcementRepository({ "owner" }, { error("must not fetch") }, MemoryCache(), { time })
        repo.update(null)
        repo.update(location(time - 90_001))
        repo.update(location().copy(accuracyMeters = 101.0))
        assertFalse(repo.state.value.unavailable)
        assertNull(repo.state.value.data)
    }

    @Test fun changedGraphReplacesWholeSnapshot() = runTest {
        var clock = time
        val cache = MemoryCache(SavedEnforcementPackage("owner", time, pack()))
        val repo = EnforcementRepository({ "owner" }, {
            pack(it).copy(graphIdentity = "new:2", controls = emptyList())
        }, cache, { clock })
        clock += EnforcementRepository.REFRESH_INTERVAL
        repo.update(location(clock))
        assertEquals("new:2", repo.state.value.data!!.graphIdentity)
        assertTrue(repo.state.value.data!!.controls.isEmpty())
        assertEquals("new:2", cache.value!!.data.graphIdentity)
    }

    @Test fun profileChangeDuringFetchCannotPublishPreviousProfile() = runTest {
        var owner = "first"
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repo = EnforcementRepository({ owner }, {
            started.complete(Unit); finish.await(); pack(it)
        }, MemoryCache(), { time })
        val job = launch { repo.update(location()) }
        started.await()
        owner = "second"
        finish.complete(Unit)
        job.join()
        assertNull(repo.state.value.data)
    }

    @Test fun authenticationFailureClearsStoredData() = runTest {
        val cache = MemoryCache(SavedEnforcementPackage("owner", time - 900_000, pack()))
        val repo = EnforcementRepository({ "owner" }, {
            throw ApiClientException.Http(401, "unauthorized")
        }, cache, { time })
        repo.update(location())
        assertNull(repo.state.value.data)
        assertNull(cache.value)
    }

    @Test fun cancellationIsNotConvertedToAnOfflineFailure() = runTest {
        val repo = EnforcementRepository({ "owner" }, { throw CancellationException() }, MemoryCache(), { time })
        try { repo.update(location()); fail("must propagate cancellation") }
        catch (_: CancellationException) { assertFalse(repo.state.value.unavailable) }
    }

    @Test fun packageHttpUsesExistingCredentialsBoundsAndRetainsAllProofs() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(json.encodeToString(pack())))
            val connection = ServerConnection.create(server.url("/").toString(), "user", "pass", true)
            val client = CompassApiClient({ connection }, OkHttpClient(), json)
            val result = client.getEnforcementPackage(Coordinate(45.0, 9.0))
            assertEquals(pack(), result)
            val request = server.takeRequest()
            assertEquals("/api/v1/enforcement/map-package", request.requestUrl!!.encodedPath)
            assertEquals("3000", request.requestUrl!!.queryParameter("radius_meters"))
            assertNotNull(request.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("x".repeat(2_000_001)))
            try { client.getEnforcementPackage(Coordinate(45.0, 9.0)); fail("must reject oversized body") }
            catch (_: ApiClientException.InvalidResponse) { }
        } finally { server.shutdown() }
    }
}
