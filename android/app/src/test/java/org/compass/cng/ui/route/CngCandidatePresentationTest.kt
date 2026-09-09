package org.compass.cng.ui.route

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.OpeningAtEta
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.OpeningValidation
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.RankedCngStation
import org.compass.cng.domain.model.RankingBreakdown
import org.junit.Assert.assertEquals
import org.junit.Test

class CngCandidatePresentationTest {
    @Test
    fun detourDurationIsTheAbsolutePrimarySelectionOrder() {
        val backendFirst = station("backend-first", detourMinutes = 10.0, backendRank = 1, price = 1.4)
        val fastest = station("fastest", detourMinutes = 0.1, backendRank = 9, price = 1.9)
        val middle = station("middle", detourMinutes = 1.2, backendRank = 2, price = null)

        val ordered = orderCngCandidatesForSelection(listOf(backendFirst, fastest, middle))

        assertEquals(listOf("fastest", "middle", "backend-first"), ordered.map { it.mimitStationId })
    }

    @Test
    fun equalDetoursKeepBackendRankAsDeterministicTieBreaker() {
        val second = station("second", detourMinutes = 0.5, backendRank = 2, price = null)
        val first = station("first", detourMinutes = 0.5, backendRank = 1, price = null)

        assertEquals(
            listOf("first", "second"),
            orderCngCandidatesForSelection(listOf(second, first)).map { it.mimitStationId },
        )
    }

    @Test
    fun displayedPricesUseDenseCheapestSecondAndRemainingTiers() {
        val candidates = listOf(
            station("cheap-a", 0.1, 1, 1.5001),
            station("cheap-b", 0.2, 2, 1.5004),
            station("second", 0.3, 3, 1.6),
            station("third", 0.4, 4, 1.7),
            station("fourth", 0.5, 5, 1.8),
            station("missing", 0.6, 6, null),
        )

        val tiers = rankVisibleCngPrices(candidates)

        assertEquals(CandidatePriceTier.CHEAPEST, tiers["cheap-a"])
        assertEquals(CandidatePriceTier.CHEAPEST, tiers["cheap-b"])
        assertEquals(CandidatePriceTier.SECOND_CHEAPEST, tiers["second"])
        assertEquals(CandidatePriceTier.OTHER, tiers["third"])
        assertEquals(CandidatePriceTier.OTHER, tiers["fourth"])
        assertEquals(null, tiers["missing"])
    }

    private fun station(
        id: String,
        detourMinutes: Double,
        backendRank: Int,
        price: Double?,
    ): RankedCngStation = RankedCngStation(
        stationId = backendRank.toLong(),
        mimitStationId = id,
        name = id,
        municipality = "Verona",
        province = "VR",
        location = Coordinate(45.0, 11.0),
        distanceFromPreviousWaypointMeters = 1_000.0,
        detourMinutes = detourMinutes,
        stationEta = NOW,
        destinationEta = NOW.plusHours(1),
        opening = OpeningAtEta(
            state = OpeningState.OPEN,
            validation = OpeningValidation.VALID,
            openingHours = "24/7",
            source = "test",
            sourceConfidence = 1.0,
            evaluatedAt = NOW,
            timezone = "Europe/Rome",
            nextChangeAt = null,
            warnings = emptyList(),
        ),
        phone = null,
        brand = null,
        operator = null,
        osmMatchConfidence = null,
        price = price?.let {
            CngPrice(
                unitPrice = it,
                currency = "EUR",
                unit = "kg",
                serviceMode = "self",
                observedAt = NOW,
                ingestedAt = NOW,
                sourceName = "test",
                ageSeconds = 0.0,
                freshness = PriceFreshness.FRESH,
            )
        },
        ranking = RankingBreakdown(
            rank = backendRank,
            totalScore = 0.5,
            detourScore = 0.5,
            openingScore = 1.0,
            priceScore = 0.5,
            priceFreshnessScore = 1.0,
        ),
    )

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.of(2026, 9, 8, 20, 0, 0, 0, ZoneOffset.UTC)
    }
}
