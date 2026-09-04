package org.compass.cng.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get

class NavigationMapStylePolicyTest {
    @Test
    fun italianNamesApplyToPlacesRoadsAndPoisButNotRoadShields() {
        assertTrue(shouldPreferItalianLabels("country-label", "place"))
        assertTrue(shouldPreferItalianLabels("road-name-primary", "transportation_name"))
        assertTrue(shouldPreferItalianLabels("poi-label", "poi"))
        assertFalse(shouldPreferItalianLabels("highway-shield", "transportation_name"))
        assertFalse(shouldPreferItalianLabels("building", "building"))
    }

    @Test
    fun italianExpressionUsesLocalAndNeutralFallbacks() {
        val expression = italianMapLabelExpression().toString()

        assertTrue(expression.contains("name:it"))
        assertTrue(expression.contains("name"))
        assertTrue(expression.contains("name_en"))
    }

    @Test
    fun navigationPoiPolicyRecognizesOnlyPoiSourceLayers() {
        assertTrue(isMapPoiLayer("poi"))
        assertFalse(isMapPoiLayer("transportation_name"))
        assertFalse(isMapPoiLayer(null))
    }

    @Test
    fun navigationPoiFilterKeepsDrivingInfrastructureAndOriginalRankFilter() {
        val expression = navigationPoiFilter(eq(get("rank"), 7)).toString()

        assertTrue(expression.contains("rank"))
        assertTrue(expression.contains("fuel"))
        assertTrue(expression.contains("charging_station"))
        assertTrue(expression.contains("toll_booth"))
        assertTrue(expression.contains("border_control"))
        assertTrue(expression.contains("traffic_signals"))
        assertFalse(expression.contains("library"))
        assertFalse(expression.contains("bus_stop"))
    }
}
