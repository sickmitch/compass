package org.compass.cng.ui.route

import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.SelectedCngStop
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteFormattingTest {
    @Test
    fun addedDurationBelowOneMinuteIsNotPresentedAsZero() {
        assertEquals("<1 min", formatAddedDuration(1.0))
        assertEquals("<1 min", formatAddedDuration(59.9))
        assertEquals("+1 min", formatAddedDuration(60.0))
        assertEquals("0 min", formatAddedDuration(0.0))
    }

    @Test
    fun mixedStopSummaryCountsFuelAndOrdinaryStopsWithoutDuplication() {
        assertEquals(
            "3 tappe totali · 2 rifornimenti CNG · 1 tappa del viaggio",
            formatTripStopSummary(totalStopCount = 3, cngStopCount = 2, ordinaryStopCount = 1),
        )
        assertEquals(
            "1 tappa totale · 1 tappa del viaggio",
            formatTripStopSummary(totalStopCount = 1, cngStopCount = 0, ordinaryStopCount = 1),
        )
    }

    @Test
    fun summaryKeepsOnlyUserStopsInTheOrdinarySection() {
        val fuel = PlannedIntermediateStop(
            id = "fuel",
            location = Coordinate(45.0, 10.0),
            locationMethod = RouteLocationMethod.SEARCH,
            privateDisplayName = "Stazione CNG",
            cngStop = SelectedCngStop(
                mimitStationId = "123",
                name = "Stazione CNG",
                municipality = "Verona",
                province = "VR",
                location = Coordinate(45.0, 10.0),
            ),
        )
        val modena = PlannedIntermediateStop(
            id = "modena",
            location = Coordinate(44.6471, 10.9252),
            locationMethod = RouteLocationMethod.SEARCH,
            privateDisplayName = "Modena",
        )

        assertEquals(listOf(modena), ordinaryStopsForSummary(listOf(fuel, modena)))
    }

    @Test
    fun intermediateStopActionsUseCompactLabelsAndKeepCngModeSeparate() {
        val routeRows = intermediateStopActionRows(IntermediateStopsMode.ROUTE)
        val planningRows = intermediateStopActionRows(IntermediateStopsMode.CNG_PLAN)

        assertEquals(listOf(2, 2), routeRows.map { it.size })
        assertEquals(listOf(2, 1), planningRows.map { it.size })
        assertEquals(
            listOf("Preferiti", "Cerca", "Mappa", "Sosta CNG"),
            routeRows.flatten().map(IntermediateStopAddAction::label),
        )
        assertEquals(
            listOf("Preferiti", "Cerca", "Mappa"),
            planningRows.flatten().map(IntermediateStopAddAction::label),
        )
    }
}
