package org.compass.cng.ui.route

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import org.compass.cng.domain.model.RankedCngStation

internal enum class CandidatePriceTier {
    CHEAPEST,
    SECOND_CHEAPEST,
    OTHER,
}

/**
 * Selection order is intentionally independent from the backend's multi-factor recommendation.
 * Detour duration is the absolute primary key requested by the driver; the server rank only makes
 * equal-detour ordering stable.
 */
internal fun orderCngCandidatesForSelection(
    candidates: List<RankedCngStation>,
): List<RankedCngStation> = candidates.sortedWith(
    compareBy<RankedCngStation> {
        it.detourMinutes.takeIf(Double::isFinite) ?: Double.POSITIVE_INFINITY
    }.thenBy { it.ranking.rank }
        .thenBy { it.mimitStationId },
)

/** Dense price rank per comparable currency/unit, based on the three-decimal value shown in UI. */
internal fun rankVisibleCngPrices(
    candidates: List<RankedCngStation>,
): Map<String, CandidatePriceTier> = buildMap {
    candidates
        .mapNotNull { station ->
            station.price?.unitPrice
                ?.takeIf(Double::isFinite)
                ?.let { price -> station to VisiblePriceKey.from(station, price) }
        }
        .groupBy { (_, key) -> key.comparisonGroup }
        .values
        .forEach { comparablePrices ->
            val orderedValues = comparablePrices
                .map { (_, key) -> key.displayedValue }
                .distinct()
                .sorted()
            comparablePrices.forEach { (station, key) ->
                put(
                    station.mimitStationId,
                    when (orderedValues.indexOf(key.displayedValue)) {
                        0 -> CandidatePriceTier.CHEAPEST
                        1 -> CandidatePriceTier.SECOND_CHEAPEST
                        else -> CandidatePriceTier.OTHER
                    },
                )
            }
        }
}

private data class VisiblePriceKey(
    val comparisonGroup: String,
    val displayedValue: BigDecimal,
) {
    companion object {
        fun from(station: RankedCngStation, price: Double): VisiblePriceKey {
            val details = requireNotNull(station.price)
            return VisiblePriceKey(
                comparisonGroup = "${details.currency.uppercase(Locale.ROOT)}:" +
                    details.unit.lowercase(Locale.ROOT),
                displayedValue = BigDecimal.valueOf(price).setScale(3, RoundingMode.HALF_UP),
            )
        }
    }
}
