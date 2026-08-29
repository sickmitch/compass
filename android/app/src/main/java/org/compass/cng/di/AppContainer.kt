package org.compass.cng.di

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.compass.cng.BuildConfig
import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.data.repository.HttpRoutingRepository
import org.compass.cng.domain.RoutingRepository

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
        ),
    )
}
