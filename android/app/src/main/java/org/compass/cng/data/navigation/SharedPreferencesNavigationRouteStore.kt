package org.compass.cng.data.navigation

import android.content.Context
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.CngPrice
import org.compass.cng.domain.model.GasolineFallback
import org.compass.cng.domain.model.Maneuver
import org.compass.cng.domain.model.ManeuverSign
import org.compass.cng.domain.model.ManeuverSignElement
import org.compass.cng.domain.model.NavigationTiming
import org.compass.cng.domain.model.OpeningAtEta
import org.compass.cng.domain.model.OpeningState
import org.compass.cng.domain.model.OpeningValidation
import org.compass.cng.domain.model.PriceFreshness
import org.compass.cng.domain.model.RouteSpeedLimit
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
    val sign: StoredManeuverSign? = null,
    val roundaboutExitCount: Int? = null,
) {
    fun toDomain() = Maneuver(
        type = type,
        instruction = instruction,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        beginShapeIndex = beginShapeIndex,
        endShapeIndex = endShapeIndex,
        streetNames = streetNames,
        verbalTransitionAlertInstruction = verbalTransitionAlertInstruction,
        verbalPreTransitionInstruction = verbalPreTransitionInstruction,
        verbalPostTransitionInstruction = verbalPostTransitionInstruction,
        bearingBefore = bearingBefore,
        bearingAfter = bearingAfter,
        travelMode = travelMode,
        travelType = travelType,
        sign = sign?.toDomain(),
        roundaboutExitCount = roundaboutExitCount,
    )

    companion object {
        fun fromDomain(value: Maneuver) = StoredManeuver(
            value.type, value.instruction, value.distanceMeters, value.durationSeconds,
            value.beginShapeIndex, value.endShapeIndex, value.streetNames,
            value.verbalTransitionAlertInstruction, value.verbalPreTransitionInstruction,
            value.verbalPostTransitionInstruction, value.bearingBefore, value.bearingAfter,
            value.travelMode, value.travelType, value.sign?.let(StoredManeuverSign::fromDomain),
            value.roundaboutExitCount,
        )
    }
}

@Serializable
private data class StoredManeuverSignElement(
    val text: String,
    val consecutiveCount: Int? = null,
) {
    fun toDomain() = ManeuverSignElement(text, consecutiveCount)

    companion object {
        fun fromDomain(value: ManeuverSignElement) = StoredManeuverSignElement(
            value.text,
            value.consecutiveCount,
        )
    }
}

@Serializable
private data class StoredManeuverSign(
    val exitNumberElements: List<StoredManeuverSignElement> = emptyList(),
    val exitBranchElements: List<StoredManeuverSignElement> = emptyList(),
    val exitTowardElements: List<StoredManeuverSignElement> = emptyList(),
    val exitNameElements: List<StoredManeuverSignElement> = emptyList(),
) {
    fun toDomain() = ManeuverSign(
        exitNumberElements.map(StoredManeuverSignElement::toDomain),
        exitBranchElements.map(StoredManeuverSignElement::toDomain),
        exitTowardElements.map(StoredManeuverSignElement::toDomain),
        exitNameElements.map(StoredManeuverSignElement::toDomain),
    )

    companion object {
        fun fromDomain(value: ManeuverSign) = StoredManeuverSign(
            value.exitNumberElements.map(StoredManeuverSignElement::fromDomain),
            value.exitBranchElements.map(StoredManeuverSignElement::fromDomain),
            value.exitTowardElements.map(StoredManeuverSignElement::fromDomain),
            value.exitNameElements.map(StoredManeuverSignElement::fromDomain),
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
    val trafficState: String = "not_configured",
    val trafficAware: Boolean = false,
    val trafficObservedAt: String? = null,
) {
    fun toDomain() = NavigationTiming(
        routeId, drivingDurationSeconds, remainingDrivingDurationSeconds, refuelingStopCount,
        dwellSecondsPerRefuelingStop, totalRefuelingDwellSeconds, totalTripDurationSeconds,
        departureAt?.let(OffsetDateTime::parse), drivingArrivalAt?.let(OffsetDateTime::parse),
        tripArrivalAt?.let(OffsetDateTime::parse), trafficDelaySeconds, trafficDelayState,
        trafficState, trafficAware, trafficObservedAt?.let(OffsetDateTime::parse),
    )

    companion object {
        fun fromDomain(value: NavigationTiming) = StoredNavigationTiming(
            value.routeId, value.drivingDurationSeconds, value.remainingDrivingDurationSeconds,
            value.refuelingStopCount, value.dwellSecondsPerRefuelingStop,
            value.totalRefuelingDwellSeconds, value.totalTripDurationSeconds,
            value.departureAt?.toString(), value.drivingArrivalAt?.toString(),
            value.tripArrivalAt?.toString(), value.trafficDelaySeconds, value.trafficDelayState,
            value.trafficState, value.trafficAware, value.trafficObservedAt?.toString(),
        )
    }
}

@Serializable
private data class StoredRouteSpeedLimit(
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val speedLimitKph: Int,
) {
    fun toDomain() = RouteSpeedLimit(beginShapeIndex, endShapeIndex, speedLimitKph)

    companion object {
        fun fromDomain(value: RouteSpeedLimit) = StoredRouteSpeedLimit(
            value.beginShapeIndex,
            value.endShapeIndex,
            value.speedLimitKph,
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
    val speedLimits: List<StoredRouteSpeedLimit> = emptyList(),
    val speedLimitSource: String? = null,
) {
    fun toDomain() = NavigationLeg(
        sequence, origin.toDomain(), destination.toDomain(), distanceMeters, durationSeconds,
        geometry.map(StoredCoordinate::toDomain), maneuvers.map(StoredManeuver::toDomain),
        shapeIndexOffset, availableRangeAtDepartureKm, estimatedRemainingRangeAtArrivalKm,
        reserveMarginAtArrivalKm, speedLimits.map(StoredRouteSpeedLimit::toDomain),
        speedLimitSource,
    )

    companion object {
        fun fromDomain(value: NavigationLeg) = StoredNavigationLeg(
            value.sequence, StoredCoordinate.fromDomain(value.origin),
            StoredCoordinate.fromDomain(value.destination), value.distanceMeters,
            value.durationSeconds, value.geometry.map(StoredCoordinate::fromDomain),
            value.maneuvers.map(StoredManeuver::fromDomain), value.shapeIndexOffset,
            value.availableRangeAtDepartureKm, value.estimatedRemainingRangeAtArrivalKm,
            value.reserveMarginAtArrivalKm,
            value.speedLimits.map(StoredRouteSpeedLimit::fromDomain), value.speedLimitSource,
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
    val opening: StoredOpeningAtEta? = null,
    val phone: String? = null,
    val brand: String? = null,
    val operator: String? = null,
    val price: StoredCngPrice? = null,
) {
    fun toDomain() = NavigationFuelStop(
        sequence, mimitStationId, name, municipality, province, location.toDomain(),
        expectedArrivalAt?.let(OffsetDateTime::parse), dwellTimeSeconds, opening?.toDomain(),
        phone, brand, operator, price?.toDomain(),
    )

    companion object {
        fun fromDomain(value: NavigationFuelStop) = StoredFuelStop(
            value.sequence, value.mimitStationId, value.name, value.municipality, value.province,
            StoredCoordinate.fromDomain(value.location), value.expectedArrivalAt?.toString(),
            value.dwellTimeSeconds, value.opening?.let(StoredOpeningAtEta::fromDomain),
            value.phone, value.brand, value.operator, value.price?.let(StoredCngPrice::fromDomain),
        )
    }
}

@Serializable
private data class StoredOpeningAtEta(
    val state: String,
    val validation: String,
    val openingHours: String?,
    val source: String?,
    val sourceConfidence: Double?,
    val evaluatedAt: String,
    val timezone: String,
    val nextChangeAt: String?,
    val warnings: List<String>,
) {
    fun toDomain() = OpeningAtEta(
        OpeningState.valueOf(state), OpeningValidation.valueOf(validation), openingHours, source,
        sourceConfidence, OffsetDateTime.parse(evaluatedAt), timezone,
        nextChangeAt?.let(OffsetDateTime::parse), warnings,
    )

    companion object {
        fun fromDomain(value: OpeningAtEta) = StoredOpeningAtEta(
            value.state.name, value.validation.name, value.openingHours, value.source,
            value.sourceConfidence, value.evaluatedAt.toString(), value.timezone,
            value.nextChangeAt?.toString(), value.warnings,
        )
    }
}

@Serializable
private data class StoredCngPrice(
    val unitPrice: Double,
    val currency: String,
    val unit: String,
    val serviceMode: String,
    val observedAt: String,
    val ingestedAt: String,
    val sourceName: String,
    val ageSeconds: Double?,
    val freshness: String,
) {
    fun toDomain() = CngPrice(
        unitPrice, currency, unit, serviceMode, OffsetDateTime.parse(observedAt),
        OffsetDateTime.parse(ingestedAt), sourceName, ageSeconds,
        PriceFreshness.valueOf(freshness),
    )

    companion object {
        fun fromDomain(value: CngPrice) = StoredCngPrice(
            value.unitPrice, value.currency, value.unit, value.serviceMode,
            value.observedAt.toString(), value.ingestedAt.toString(), value.sourceName,
            value.ageSeconds, value.freshness.name,
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
    val speedLimits: List<StoredRouteSpeedLimit> = emptyList(),
    val speedLimitSource: String? = null,
) {
    fun toDomain() = NavigationRoute(
        routeId, origin.toDomain(), destination.toDomain(), totalDistanceMeters,
        drivingDurationSeconds, totalTripDurationSeconds,
        geometry.map(StoredCoordinate::toDomain), legs.map(StoredNavigationLeg::toDomain),
        maneuvers.map(StoredManeuver::toDomain), fuelStops.map(StoredFuelStop::toDomain),
        fuelPlan?.toDomain(), timing.toDomain(), provider, gasolineFallback?.toDomain(),
        speedLimits.map(StoredRouteSpeedLimit::toDomain), speedLimitSource,
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
            value.speedLimits.map(StoredRouteSpeedLimit::fromDomain), value.speedLimitSource,
        )
    }
}
