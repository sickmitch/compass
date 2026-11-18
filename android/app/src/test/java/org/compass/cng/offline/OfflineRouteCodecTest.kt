package org.compass.cng.offline

import kotlinx.serialization.json.*
import org.compass.cng.domain.RouteOriginDirection
import org.compass.cng.domain.geometry.Polyline6Encoder
import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.*
import org.junit.Test

class OfflineRouteCodecTest {
    private val points = listOf(Coordinate(45.0, 11.0), Coordinate(45.01, 11.01))
    private fun response(index: Int = 1, highway: Boolean = false): String = buildJsonObject {
        put("trip", buildJsonObject {
            put("status", 0); put("units", "kilometers")
            put("legs", buildJsonArray { add(buildJsonObject {
                put("shape", Polyline6Encoder.encode(points))
                put("summary", buildJsonObject { put("length", 1.2); put("time", 120.0); put("has_highway", highway) })
                put("maneuvers", buildJsonArray { add(buildJsonObject {
                    put("type", 1); put("instruction", "Guida verso nord"); put("length", 1.2); put("time", 120.0)
                    put("begin_shape_index", 0); put("end_shape_index", index)
                    put("verbal_pre_transition_instruction", "Guida verso nord")
                }) })
            }) })
        })
    }.toString()
    @Test fun requestKeepsHighwayExclusionDirectionAndNominalSpeeds() {
        val request = Json.parseToJsonElement(OfflineRouteCodec.request(points, false, RouteOriginDirection(90.0, 45))).jsonObject
        val auto = request.getValue("costing_options").jsonObject.getValue("auto").jsonObject
        assertTrue(auto.getValue("exclude_highways").jsonPrimitive.boolean)
        assertEquals(listOf("constrained", "freeflow"), auto.getValue("speed_types").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("it-IT", request.getValue("language").jsonPrimitive.content)
        assertFalse(request.containsKey("date_time"))
        assertEquals(90.0, request.getValue("locations").jsonArray[0].jsonObject.getValue("heading").jsonPrimitive.double, 0.0)
    }
    @Test fun decoderConvertsUnitsAndKeepsItalianVoiceInstructions() {
        val route = OfflineRouteCodec.decode(response(), points, false).single()
        assertEquals(1200.0, route.distanceMeters, 0.0)
        assertEquals(120.0, route.durationSeconds, 0.0)
        assertEquals(points, route.geometry)
        assertEquals("Guida verso nord", route.maneuvers.single().verbalPreTransitionInstruction)
        assertEquals(OFFLINE_PROVIDER, route.provider)
        assertTrue(route.navigation.routeId.startsWith("offline_"))
    }
    @Test fun rejectsBrokenGeometryAndViolatedHighwayExclusion() {
        for (json in listOf(response(index = 20), response(highway = true))) {
            try { OfflineRouteCodec.decode(json, points, false); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
}
