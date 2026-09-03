package org.compass.cng.di

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.compass.cng.BuildConfig
import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.data.repository.HttpRoutingRepository
import org.compass.cng.data.navigation.SharedPreferencesNavigationRouteStore
import org.compass.cng.data.search.SharedPreferencesPlaceSearchCache
import org.compass.cng.data.vehicle.SharedPreferencesVehicleProfileRepository
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.navigation.NavigationSession
import org.compass.cng.navigation.CompassNavigationRouteRecalculator
import org.compass.cng.navigation.NavigationRouteRecalculator

class AppContainer(context: Context) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    val routingRepository: RoutingRepository = HttpRoutingRepository(
        CompassApiClient(
            baseUrl = BuildConfig.COMPASS_API_BASE_URL,
            httpClient = httpClient,
            json = json,
            eventLogger = { event -> Log.i(COMPASS_API_LOG_TAG, event) },
        ),
        placeSearchCache = SharedPreferencesPlaceSearchCache(context),
        eventLogger = { event -> Log.i(COMPASS_API_LOG_TAG, event) },
    )
    val vehicleProfileRepository = SharedPreferencesVehicleProfileRepository(context)

    val navigationSession = NavigationSession(
        routeStore = SharedPreferencesNavigationRouteStore(context),
        eventLogger = { event -> Log.i(COMPASS_NAVIGATION_LOG_TAG, event) },
    )
    val navigationRouteRecalculator: NavigationRouteRecalculator =
        CompassNavigationRouteRecalculator(routingRepository)

    private companion object {
        const val COMPASS_API_LOG_TAG = "CompassApi"
        const val COMPASS_NAVIGATION_LOG_TAG = "CompassNavigation"
    }
}
