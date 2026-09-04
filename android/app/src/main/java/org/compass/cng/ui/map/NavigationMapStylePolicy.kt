package org.compass.cng.ui.map

import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.any
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get

private val LOCALIZABLE_SOURCE_LAYERS = setOf(
    "aerodrome_label",
    "mountain_peak",
    "park",
    "place",
    "poi",
    "transportation_name",
    "water_name",
)

internal fun shouldPreferItalianLabels(layerId: String, sourceLayer: String?): Boolean =
    sourceLayer in LOCALIZABLE_SOURCE_LAYERS &&
        !layerId.contains("shield", ignoreCase = true) &&
        !layerId.contains("ref", ignoreCase = true)

internal fun italianMapLabelExpression(): Expression = coalesce(
    get("name:it"),
    get("name_it"),
    get("name"),
    get("name:latin"),
    get("name_en"),
)

internal fun isMapPoiLayer(sourceLayer: String?): Boolean = sourceLayer == "poi"

internal fun navigationPoiFilter(existingFilter: Expression?): Expression {
    val relevantPoi = any(
        eq(get("class"), "fuel"),
        eq(get("subclass"), "fuel"),
        eq(get("subclass"), "charging_station"),
        eq(get("subclass"), "toll_booth"),
        eq(get("subclass"), "border_control"),
        eq(get("subclass"), "traffic_signals"),
    )
    return existingFilter?.let { all(it, relevantPoi) } ?: relevantPoi
}
