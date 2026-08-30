package org.compass.cng.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun predictiveResponseFixture(
    rankedResponse: String,
    suggestionState: String = "suggested",
): String {
    val ranked = Json.parseToJsonElement(rankedResponse).jsonObject
    val suggested = suggestionState == "suggested"
    val notNeeded = suggestionState == "not_needed"
    return buildJsonObject {
        put("stage", "predictive_ranking")
        put("suggestion_state", suggestionState)
        put("departure_at", ranked.getValue("departure_at"))
        put("maximum_detour_minutes", ranked.getValue("maximum_detour_minutes"))
        put("base_route", ranked.getValue("base_route"))
        put("corridor", ranked.getValue("corridor"))
        put("spatial_pruning", ranked.getValue("spatial_pruning"))
        put("cost_basis", ranked.getValue("cost_basis"))
        put("network_evaluation", ranked.getValue("network_evaluation"))
        put(
            "range_basis",
            buildJsonObject {
                put("effective_cng_range_km", 300.0)
                put("estimated_remaining_cng_range_km", if (notNeeded) 300.0 else 120.0)
                put("reserve_cng_range_km", 30.0)
                put("usable_range_before_reserve_km", if (notNeeded) 270.0 else 90.0)
                put("remaining_route_distance_km", 210.925)
                put("range_shortfall_to_destination_km", if (notNeeded) 0.0 else 120.925)
                put("destination_reachable_with_reserve", notNeeded)
                put("remaining_route_origin", "request_origin")
                put("consumption_model", "caller_estimated_remaining_range")
                put("traffic_state", "not_configured")
                put("traffic_adjusted", false)
            },
        )
        put(
            "reachability_evaluation",
            buildJsonObject {
                put("detour_eligible_candidate_count", if (notNeeded) 0 else 1)
                put("reachable_before_reserve_count", if (suggested) 1 else 0)
                put("excluded_unreachable_before_reserve_count", if (notNeeded) 0 else 1)
                put("ranked_reachable_candidate_count", if (suggested) 1 else 0)
                if (suggested) {
                    put("furthest_reachable_route_fraction", 0.1035)
                } else {
                    put("furthest_reachable_route_fraction", null)
                }
                put("evaluation_skipped_destination_reachable", notNeeded)
                put("pairwise_matrix_calls", 0)
                put("pairwise_matrix_fallback_splits", 0)
                put("pairwise_matrix_location_failures", 0)
                put("itinerary_search_labels", if (suggested) 1 else 0)
            },
        )
        put("ranking_policy", ranked.getValue("ranking_policy"))
        put(
            "ranking_evaluation",
            if (suggested) {
                ranked.getValue("ranking_evaluation")
            } else {
                buildJsonObject {
                    put("detour_eligible_candidate_count", 0)
                    put("opening_open_count", 0)
                    put("opening_closed_count", 0)
                    put("opening_unknown_count", 0)
                    put("opening_valid_count", 0)
                    put("opening_missing_count", 0)
                    put("opening_invalid_count", 0)
                    put("excluded_closed_count", 0)
                    put("price_available_count", 0)
                    put("price_missing_count", 0)
                    put("ranked_candidate_count", 0)
                    put("enrichment_queries", 0)
                }
            },
        )
        put(
            "candidates",
            buildJsonArray {
                if (suggested) {
                    ranked.getValue("candidates").jsonArray.forEach { candidate ->
                        add(
                            buildJsonObject {
                                put("candidate", candidate)
                                put("estimated_remaining_range_at_arrival_km", 96.894)
                                put("reserve_margin_at_arrival_km", 66.894)
                            },
                        )
                    }
                }
            },
        )
        if (suggested) {
            val candidate = ranked.getValue("candidates").jsonArray.first().jsonObject
            put(
                "itinerary",
                buildJsonObject {
                    put(
                        "stops",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("sequence", 1)
                                    put("station_id", candidate.getValue("station_id"))
                                    put(
                                        "mimit_station_id",
                                        candidate.getValue("mimit_station_id"),
                                    )
                                    put("name", candidate.getValue("name"))
                                    put("municipality", candidate.getValue("municipality"))
                                    put("province", candidate.getValue("province"))
                                    put(
                                        "location",
                                        buildJsonObject {
                                            put("latitude", candidate.getValue("latitude"))
                                            put("longitude", candidate.getValue("longitude"))
                                        },
                                    )
                                    put("arrival_at", candidate.getValue("station_eta"))
                                    put("leg_distance_meters", 23_106.0)
                                    put("leg_duration_seconds", 1_151.0)
                                    put("available_range_at_departure_km", 120.0)
                                    put("estimated_remaining_range_at_arrival_km", 96.894)
                                    put("reserve_margin_at_arrival_km", 66.894)
                                    put("opening", candidate.getValue("opening"))
                                    put("phone", candidate.getValue("phone"))
                                    put("brand", candidate.getValue("brand"))
                                    put("operator", candidate.getValue("operator"))
                                    put(
                                        "osm_match_confidence",
                                        candidate.getValue("osm_match_confidence"),
                                    )
                                    put("price", candidate.getValue("price"))
                                },
                            )
                        },
                    )
                    put(
                        "destination_leg",
                        buildJsonObject {
                            put("distance_meters", 187_824.0)
                            put("duration_seconds", 5_688.0)
                            put("available_range_at_departure_km", 300.0)
                            put("estimated_remaining_range_at_arrival_km", 112.176)
                            put("reserve_margin_at_arrival_km", 82.176)
                            put("destination_eta", "2026-08-30T11:53:59+02:00")
                        },
                    )
                    put("total_distance_meters", 210_930.0)
                    put("total_duration_seconds", 6_839.0)
                    put("refuel_assumption", "full_effective_range_after_each_stop")
                    put("distance_model", "road_network")
                },
            )
        } else {
            put("itinerary", null)
        }
    }.toString()
}
