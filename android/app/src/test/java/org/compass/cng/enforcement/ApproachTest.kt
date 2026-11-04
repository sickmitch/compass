package org.compass.cng.enforcement

import org.compass.cng.domain.geometry.Polyline6Encoder
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.navigation.*
import org.junit.Assert.*
import org.junit.Test

class ApproachTest {
    private val start = 1_000_000L
    private fun fix(step: Int, lat: Double = 45.0, bearing: Double = 90.0,
        accuracy: Double = 5.0, speed: Double = 15.0) = NavigationLocation(
        Coordinate(lat, 9.0 + step * .0002), accuracy, speed, bearing, start + step * 2_000)
    private fun result() = ApproachResult("approach", "directed_approach", "graph", "2026-11-04T10:00:00Z",
        ApproachControl("osm:relation:1", "osm:node:2", "fixed", "10", true, 90.0, 150.0,
            Polyline6Encoder.encode(listOf(Coordinate(45.0, 9.0004), Coordinate(45.0, 9.0024))),
            45.0, 9.0024))
    private fun ready(): ApproachEngine = ApproachEngine().apply {
        for (i in 0..2) update(fix(i), fix(i).timestampEpochMillis, "follow")
        accept(result(), generation, "graph", fix(2).timestampEpochMillis)
        update(fix(3), fix(3).timestampEpochMillis, "follow")
    }

    @Test fun movingApproachShowsDistanceAndSpeaksOnlyOnceAcrossRefreshes() {
        val engine = ready()
        assertTrue(engine.notice!!.distanceMeters in 130.0..145.0)
        assertTrue(engine.claimSpeech(true)!!.text.contains("90 chilometri orari"))
        assertNull(engine.claimSpeech(true))
        engine.accept(result(), engine.generation, "graph", fix(4).timestampEpochMillis)
        engine.update(fix(4), fix(4).timestampEpochMillis, "follow")
        assertNotNull(engine.notice)
        assertNull(engine.claimSpeech(true))
    }

    @Test fun muteConsumesPassageWithoutPlayingLaterAndMissingLimitIsNotInvented() {
        val engine = ready()
        assertNull(engine.claimSpeech(false))
        assertNull(engine.claimSpeech(true))
        assertFalse(ApproachNotice(result().alert!!.copy(speedLimitKph = null), 150.0).text.contains("Limite"))
    }

    @Test fun dynamicDistanceIncreasesWithSpeedAndIsBounded() {
        assertEquals(100.0, ApproachEngine.warningDistance(2.0), 0.0)
        assertEquals(225.0, ApproachEngine.warningDistance(15.0), 0.0)
        assertEquals(600.0, ApproachEngine.warningDistance(50.0), 0.0)
    }

    @Test fun rejectsParallelOppositePoorGpsAndPassedControl() {
        for (sample in listOf(fix(4, lat = 45.0002), fix(4, bearing = 270.0),
            fix(4, accuracy = 30.0), fix(13))) {
            val engine = ready()
            engine.update(sample, sample.timestampEpochMillis, "follow")
            assertNull(engine.notice)
            assertNull(engine.claimSpeech(true))
        }
    }

    @Test fun gapsClockRollbackAndDelayedResultsCannotRestoreAnAlert() {
        val engine = ready()
        val generation = engine.generation
        engine.update(fix(3), start + 30_000, "follow")
        engine.accept(result(), generation, "graph", start + 30_000)
        assertNull(engine.notice)
        assertNull(engine.request())
        val second = ready()
        second.update(fix(0), fix(0).timestampEpochMillis, "follow")
        assertNull(second.notice)
        assertNull(second.request())
    }

    @Test fun staleGraphAndExplicitNoMatchClearPreviousCorridor() {
        val engine = ready()
        engine.accept(result(), engine.generation, "new-graph", start + 6_000)
        engine.update(fix(4), fix(4).timestampEpochMillis, "follow")
        assertNull(engine.notice)
        val other = ready()
        other.accept(result().copy(state = "uncertain", alert = null), other.generation, "graph", start + 6_000)
        other.update(fix(4), fix(4).timestampEpochMillis, "follow")
        assertNull(other.notice)
    }

    @Test fun activeRouteMustContainTheApproachInCorrectDirection() {
        for (route in listOf(
            listOf(Coordinate(45.0002, 9.0), Coordinate(45.0002, 9.005)),
            listOf(Coordinate(45.0, 9.005), Coordinate(45.0, 9.0)),
        )) {
            val engine = ready()
            engine.accept(result(), engine.generation, "graph", start + 6_000, route)
            engine.update(fix(4), fix(4).timestampEpochMillis, "follow")
            assertNull(engine.notice)
        }
        val engine = ready()
        engine.accept(result(), engine.generation, "graph", start + 6_000,
            listOf(Coordinate(45.0, 9.0), Coordinate(45.0, 9.005)))
        engine.update(fix(4), fix(4).timestampEpochMillis, "follow")
        assertNotNull(engine.notice)
    }

    @Test fun rerouteInvalidatesApproachButNeverRearmsSamePassage() {
        val engine = ready()
        assertNotNull(engine.claimSpeech(true))
        engine.update(fix(4), fix(4).timestampEpochMillis, "new-route")
        assertNull(engine.notice)
        for (i in 5..6) engine.update(fix(i), fix(i).timestampEpochMillis, "new-route")
        engine.accept(result(), engine.generation, "graph", fix(6).timestampEpochMillis)
        engine.update(fix(7), fix(7).timestampEpochMillis, "new-route")
        assertNotNull(engine.notice)
        assertNull(engine.claimSpeech(true))
        engine.update(fix(8), fix(8).timestampEpochMillis, "new-route", allowed = false)
        assertNull(engine.notice)
    }

    @Test fun shortOfflineContinuationRequiresFreshContinuousGpsAndExpires() {
        val engine = ready()
        // No new backend result: retained corridor is enough for this short continuous approach.
        for (i in 4..8) engine.update(fix(i), fix(i).timestampEpochMillis, "follow")
        assertNotNull(engine.notice)
        val later = fix(9).copy(timestampEpochMillis = start + 80_000)
        engine.update(later, later.timestampEpochMillis, "follow")
        assertNull(engine.notice)
    }

    @Test fun stationaryFixesDoNotBuildRequest() {
        val engine = ApproachEngine()
        repeat(30) {
            val sample = fix(0).copy(timestampEpochMillis = start + it * 1000)
            engine.update(sample, sample.timestampEpochMillis, "follow")
        }
        assertNull(engine.request())
        assertNull(engine.notice)
    }
}
