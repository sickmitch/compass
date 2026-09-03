package org.compass.cng.data.navigation

import android.content.Context
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.GasolineFallback
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.navigation.CachedNavigationRoute
import org.compass.cng.navigation.NavigationFuelPlan
import org.compass.cng.navigation.NavigationFuelStop
import org.compass.cng.navigation.NavigationLeg
import org.compass.cng.navigation.NavigationRoute
import org.compass.cng.navigation.NavigationRouteStore

class SharedPreferencesNavigationRouteStore internal constructor(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codec: NavigationRouteDocumentCodec = NavigationRouteDocumentCodec(),
) : NavigationRouteStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): CachedNavigationRoute? = codec.decode(preferences.getString(ROUTE_KEY, null))

    override fun save(route: NavigationRoute, navigationWasActive: Boolean) {
        preferences.edit().putString(
            ROUTE_KEY,
            codec.encode(
                CachedNavigationRoute(
                    route = route,
                    cachedAtEpochMillis = clock(),
                    navigationWasActive = navigationWasActive,
                ),
            ),
        ).apply()
    }

    override fun clear() {
        preferences.edit().remove(ROUTE_KEY).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "compass_navigation_cache"
        const val ROUTE_KEY = "active_route_v1"
    }
}

internal class NavigationRouteDocumentCodec(
    private val json: Json = Json { ignoreUnknownKeys = false; explicitNulls = true },
) {
    fun encode(value: CachedNavigationRoute): String = json.encodeToString(
        StoredNavigationRouteDocument.fromDomain(value),
    )

    fun decode(value: String?): CachedNavigationRoute? {
        if (value.isNullOrBlank()) return null
        return try {
            json.decodeFromString<StoredNavigationRouteDocument>(value).toDomain()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}

@Serializable
private data class StoredNavigationRouteDocument(
    val schemaVersion: Int = 1,
    val cachedAtEpochMillis: Long,
    val navigationWasActive: Boolean,
    val route: StoredNavigationRoute,
) {
    init {
        require(schemaVersion == 1) { "unsupported navigation cache schema" }
        require(cachedAtEpochMillis >= 0) { "invalid navigation cache timestamp" }
    }

    fun toDomain() = CachedNavigationRoute(
        route = route.toDomain(),
        cachedAtEpochMillis = cachedAtEpochMillis,
        navigationWasActive = navigationWasActive,
    )

    companion object {
        fun fromDomain(value: CachedNavigationRoute) = StoredNavigationRouteDocument(
            cachedAtEpochMillis = value.cachedAtEpochMillis,
            navigationWasActive = value.navigationWasActive,
            route = StoredNavigationRoute.fromDomain(value.route),
        )
    }
}

@Serializable
private data class StoredCoordinate(val latitude: Double, val longitude: Double) {
    fun toDomain() = Coordinate(latitude, longitude)

    companion object {
        fun fromDomain(value: Coordinate) = StoredCoordinate(value.latitude, value.longitude)
    }
}

@Serializable
private data class StoredManeuver(
    val type: Int,
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val streetNames: List<String>,
    val verbalTransitionAlertInstruction: String?,
    val verbalPreTransitionInstruction: String?,
    val verbalPostTransitionInstruction: String?,
    val bearingBefore: Int?,
    val bearingAfter: Int?,
    val travelMode: String?,
    val travelType: String?,
) {
    fun toDomain() = Maneuver(
        type, instruction, distanceMeters, durationSeconds, beginShapeIndex, endShapeIndex,
        streetNames, verbalTransitionAlertInstruction, verbalPreTransitionInstruction,
        verbalPostTransitionInstruction, bearingBefore, bearingAfter, travelMode, travelType,
    )

    companion object {
        fun fromDomain(value: Maneuver) = StoredManeuver(
            value.type, value.instruction, value.distanceMeters, value.durationSeconds,
            value.beginShapeIndex, value.endShapeIndex, value.streetNames,
            value.verbalTransitionAlertInstruction, value.verbalPreTransitionInstruction,
            value.verbalPostTransitionInstruction, value.bearingBefore, value.bearingAfter,
            value.travelMode, value.travelType,
        )
    }
}

@Serializable
private data class StoredNavigationTiming(
    val routeId: String,
    val drivingDurationSeconds: Double,
    val remainingDrivingDurationSeconds: Double,
    val refuelingStopCount: Int,
    val dwellSecondsPerRefuelingStop: Int,
    val totalRefuelingDwellSeconds: Double,
    val totalTripDurationSeconds: Double,
    val departureAt: String?,
    val drivingArrivalAt: String?,
    val tripArrivalAt: String?,
    val trafficDelaySeconds: Double?,
    val trafficDelayState: String,
) {
    fun toDomain() = NavigationTiming(
        routeId, drivingDurationSeconds, remainingDrivingDurationSeconds, refuelingStopCount,
        dwellSecondsPerRefuelingStop, totalRefuelingDwellSeconds, totalTripDurationSeconds,
        departureAt?.let(OffsetDateTime::parse), drivingArrivalAt?.let(OffsetDateTime::parse),
        tripArrivalAt?.let(OffsetDateTime::parse), trafficDelaySeconds, trafficDelayState,
    )

    companion object {
        fun fromDomain(value: NavigationTiming) = StoredNavigationTiming(
            value.routeId, value.drivingDurationSeconds, value.remainingDrivingDurationSeconds,
            value.refuelingStopCount, value.dwellSecondsPerRefuelingStop,
            value.totalRefuelingDwellSeconds, value.totalTripDurationSeconds,
            value.departureAt?.toString(), value.drivingArrivalAt?.toString(),
            value.tripArrivalAt?.toString(), value.trafficDelaySeconds, value.trafficDelayState,
        )
    }
}

@Serializable
private data class StoredNavigationLeg(
    val sequence: Int,
    val origin: StoredCoordinate,
    val destination: StoredCoordinate,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<StoredCoordinate>,
    val maneuvers: List<StoredManeuver>,
    val shapeIndexOffset: Int,
    val availableRangeAtDepartureKm: Double?,
    val estimatedRemainingRangeAtArrivalKm: Double?,
    val reserveMarginAtArrivalKm: Double?,
) {
    fun toDomain() = NavigationLeg(
        sequence, origin.toDomain(), destination.toDomain(), distanceMeters, durationSeconds,
        geometry.map(StoredCoordinate::toDomain), maneuvers.map(StoredManeuver::toDomain),
        shapeIndexOffset, availableRangeAtDepartureKm, estimatedRemainingRangeAtArrivalKm,
        reserveMarginAtArrivalKm,
    )

    companion object {
        fun fromDomain(value: NavigationLeg) = StoredNavigationLeg(
            value.sequence, StoredCoordinate.fromDomain(value.origin),
            StoredCoordinate.fromDomain(value.destination), value.distanceMeters,
            value.durationSeconds, value.geometry.map(StoredCoordinate::fromDomain),
            value.maneuvers.map(StoredManeuver::fromDomain), value.shapeIndexOffset,
            value.availableRangeAtDepartureKm, value.estimatedRemainingRangeAtArrivalKm,
            value.reserveMarginAtArrivalKm,
        )
    }
}

@Serializable
private data class StoredFuelStop(
    val sequence: Int,
    val mimitStationId: String,
    val name: String?,
    val municipality: String?,
    val province: String?,
    val location: StoredCoordinate,
    val expectedArrivalAt: String?,
    val dwellTimeSeconds: Int,
) {
    fun toDomain() = NavigationFuelStop(
        sequence, mimitStationId, name, municipality, province, location.toDomain(),
        expectedArrivalAt?.let(OffsetDateTime::parse), dwellTimeSeconds,
    )

    companion object {
        fun fromDomain(value: NavigationFuelStop) = StoredFuelStop(
            value.sequence, value.mimitStationId, value.name, value.municipality, value.province,
            StoredCoordinate.fromDomain(value.location), value.expectedArrivalAt?.toString(),
            value.dwellTimeSeconds,
        )
    }
}

@Serializable
private data class StoredFuelPlan(
    val effectiveCngRangeKm: Double,
    val initialRemainingCngRangeKm: Double,
    val reserveCngRangeKm: Double,
    val maximumDetourMinutes: Double?,
    val excludedMimitStationIds: Set<String>,
) {
    fun toDomain() = NavigationFuelPlan(
        effectiveCngRangeKm, initialRemainingCngRangeKm, reserveCngRangeKm,
        maximumDetourMinutes, excludedMimitStationIds,
    )

    companion object {
        fun fromDomain(value: NavigationFuelPlan) = StoredFuelPlan(
            value.effectiveCngRangeKm, value.initialRemainingCngRangeKm,
            value.reserveCngRangeKm, value.maximumDetourMinutes, value.excludedMimitStationIds,
        )
    }
}

@Serializable
private data class StoredGasolineFallback(
    val estimatedRemainingGasolineRangeKm: Double,
    val reserveGasolineRangeKm: Double,
    val usableGasolineRangeKm: Double,
    val cngRangeUsedBeforeSwitchKm: Double,
    val requiredGasolineRangeKm: Double,
    val gasolineMarginAtDestinationKm: Double,
    val strategy: String,
) {
    fun toDomain() = GasolineFallback(
        estimatedRemainingGasolineRangeKm, reserveGasolineRangeKm, usableGasolineRangeKm,
        cngRangeUsedBeforeSwitchKm, requiredGasolineRangeKm, gasolineMarginAtDestinationKm,
        strategy,
    )

    companion object {
        fun fromDomain(value: GasolineFallback) = StoredGasolineFallback(
            value.estimatedRemainingGasolineRangeKm, value.reserveGasolineRangeKm,
            value.usableGasolineRangeKm, value.cngRangeUsedBeforeSwitchKm,
            value.requiredGasolineRangeKm, value.gasolineMarginAtDestinationKm, value.strategy,
        )
    }
}

@Serializable
private data class StoredNavigationRoute(
    val routeId: String,
    val origin: StoredCoordinate,
    val destination: StoredCoordinate,
    val totalDistanceMeters: Double,
    val drivingDurationSeconds: Double,
    val totalTripDurationSeconds: Double,
    val geometry: List<StoredCoordinate>,
    val legs: List<StoredNavigationLeg>,
    val maneuvers: List<StoredManeuver>,
    val fuelStops: List<StoredFuelStop>,
    val fuelPlan: StoredFuelPlan?,
    val timing: StoredNavigationTiming,
    val provider: String,
    val gasolineFallback: StoredGasolineFallback?,
) {
    fun toDomain() = NavigationRoute(
        routeId, origin.toDomain(), destination.toDomain(), totalDistanceMeters,
        drivingDurationSeconds, totalTripDurationSeconds,
        geometry.map(StoredCoordinate::toDomain), legs.map(StoredNavigationLeg::toDomain),
        maneuvers.map(StoredManeuver::toDomain), fuelStops.map(StoredFuelStop::toDomain),
        fuelPlan?.toDomain(), timing.toDomain(), provider, gasolineFallback?.toDomain(),
    )

    companion object {
        fun fromDomain(value: NavigationRoute) = StoredNavigationRoute(
            value.routeId, StoredCoordinate.fromDomain(value.origin),
            StoredCoordinate.fromDomain(value.destination), value.totalDistanceMeters,
            value.drivingDurationSeconds, value.totalTripDurationSeconds,
            value.geometry.map(StoredCoordinate::fromDomain),
            value.legs.map(StoredNavigationLeg::fromDomain),
            value.maneuvers.map(StoredManeuver::fromDomain),
            value.fuelStops.map(StoredFuelStop::fromDomain), value.fuelPlan?.let(StoredFuelPlan::fromDomain),
            StoredNavigationTiming.fromDomain(value.timing), value.provider,
            value.gasolineFallback?.let(StoredGasolineFallback::fromDomain),
        )
    }
}
