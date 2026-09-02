package org.compass.cng.di

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.compass.cng.BuildConfig
import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.data.repository.HttpRoutingRepository
import org.compass.cng.domain.RoutingRepository
import org.compass.cng.navigation.NavigationSession
import org.compass.cng.navigation.CompassNavigationRouteRecalculator
import org.compass.cng.navigation.NavigationRouteRecalculator

class AppContainer {
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
    )

    val navigationSession = NavigationSession()
    val navigationRouteRecalculator: NavigationRouteRecalculator =
        CompassNavigationRouteRecalculator(routingRepository)

    private companion object {
        const val COMPASS_API_LOG_TAG = "CompassApi"
    }
}
