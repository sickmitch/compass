package org.compass.cng.offline

import kotlinx.serialization.json.*
import org.compass.cng.domain.RouteOriginDirection
import org.compass.cng.domain.geometry.Polyline6Decoder
import org.compass.cng.domain.model.*
import java.util.UUID

const val OFFLINE_PROVIDER = "valhalla_offline"

interface OfflineRouter {
    val available: Boolean
    suspend fun route(
        points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection? = null,
    ): List<RoutePreview>
}

/** Valhalla JSON is adapted to the same domain models used by the HTTP repository. */
object OfflineRouteCodec {
    fun request(points: List<Coordinate>, allowHighways: Boolean, direction: RouteOriginDirection?): String {
        require(points.size in 2..10)
        return buildJsonObject {
            put("locations", buildJsonArray {
                points.forEachIndexed { index, point -> add(buildJsonObject {
                    put("lat", point.latitude); put("lon", point.longitude); put("type", "break")
                    if (index == 0 && direction != null) {
                        put("heading", direction.headingDegrees)
                        put("heading_tolerance", direction.headingToleranceDegrees)
                    }
                }) }
            })
            put("costing", "auto"); put("units", "kilometers"); put("language", "it-IT")
            put("shape_format", "polyline6")
            put("costing_options", buildJsonObject { put("auto", buildJsonObject {
                put("speed_types", buildJsonArray { add("constrained"); add("freeflow") })
                if (!allowHighways) { put("exclude_highways", true); put("use_highways", 0.0) }
            }) })
        }.toString()
    }

    fun decode(response: String, points: List<Coordinate>, allowHighways: Boolean): List<RoutePreview> {
        val trip = Json.parseToJsonElement(response).jsonObject.getValue("trip").jsonObject
        require(trip.getValue("status").jsonPrimitive.int == 0) { "Percorso offline non disponibile" }
        require(trip.getValue("units").jsonPrimitive.content == "kilometers")
        val legs = trip.getValue("legs").jsonArray
        require(legs.size == points.size - 1)
        return legs.mapIndexed { index, element ->
            val leg = element.jsonObject
            val summary = leg.getValue("summary").jsonObject
            val geometry = Polyline6Decoder.decode(leg.getValue("shape").jsonPrimitive.content)
            require(geometry.size >= 2)
            val maneuvers = leg.getValue("maneuvers").jsonArray.map { raw ->
                val m = raw.jsonObject
                val begin = m.getValue("begin_shape_index").jsonPrimitive.int
                val end = m.getValue("end_shape_index").jsonPrimitive.int
                require(begin in geometry.indices && end in begin..geometry.lastIndex)
                fun elements(key: String) = m["sign"]?.jsonObject?.get(key)?.jsonArray?.map {
                    ManeuverSignElement(it.jsonObject.getValue("text").jsonPrimitive.content,
                        it.jsonObject["consecutive_count"]?.jsonPrimitive?.intOrNull)
                }.orEmpty()
                Maneuver(
                    type = m.getValue("type").jsonPrimitive.int,
                    instruction = m.getValue("instruction").jsonPrimitive.content,
                    distanceMeters = m.number("length") * 1000,
                    durationSeconds = m.number("time"), beginShapeIndex = begin, endShapeIndex = end,
                    streetNames = m["street_names"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    verbalTransitionAlertInstruction = m.text("verbal_transition_alert_instruction"),
                    verbalPreTransitionInstruction = m.text("verbal_pre_transition_instruction"),
                    verbalPostTransitionInstruction = m.text("verbal_post_transition_instruction"),
                    bearingBefore = m["bearing_before"]?.jsonPrimitive?.intOrNull,
                    bearingAfter = m["bearing_after"]?.jsonPrimitive?.intOrNull,
                    travelMode = m.text("travel_mode"), travelType = m.text("travel_type"),
                    sign = m["sign"]?.takeIf { it is JsonObject }?.let {
                        ManeuverSign(elements("exit_number_elements"), elements("exit_branch_elements"),
                            elements("exit_toward_elements"), elements("exit_name_elements"))
                    },
                    roundaboutExitCount = m["roundabout_exit_count"]?.jsonPrimitive?.intOrNull,
                )
            }
            require(maneuvers.isNotEmpty())
            val duration = summary.number("time")
            val usesHighways = summary["has_highway"]?.jsonPrimitive?.booleanOrNull ?: false
            require(allowHighways || !usesHighways) { "Esclusione autostrade non rispettata" }
            RoutePreview(points[index], points[index + 1], summary.number("length") * 1000,
                duration, geometry, maneuvers, OFFLINE_PROVIDER,
                navigation = NavigationTiming.legacy(duration).copy(routeId = "offline_${UUID.randomUUID()}"),
                usesHighways = usesHighways, allowsHighways = allowHighways)
        }
    }

    private fun JsonObject.number(key: String): Double = getValue(key).jsonPrimitive.double.also {
        require(it.isFinite() && it >= 0)
    }
    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
}
