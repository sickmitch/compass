package org.compass.cng.data.api

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.compass.cng.domain.model.Coordinate

class CompassApiClient(
    baseUrl: String,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private val routeUrl = baseUrl.toHttpUrl().resolve("api/v1/routes")
        ?: error("COMPASS_API_BASE_URL cannot resolve api/v1/routes")

    suspend fun getRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): ApiRoute = withContext(Dispatchers.IO) {
        val payload = RouteRequestDto(
            origin = origin.toDto(),
            destination = destination.toDto(),
            costing = "auto",
            language = "it-IT",
        )
        val request = Request.Builder()
            .url(routeUrl)
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val error = runCatching { json.decodeFromString<ErrorResponseDto>(body) }
                        .getOrNull()
                    throw ApiClientException.Http(
                        statusCode = response.code,
                        code = error?.code ?: "http_${response.code}",
                    )
                }

                val decoded = try {
                    json.decodeFromString<RouteResponseDto>(body)
                } catch (error: SerializationException) {
                    throw ApiClientException.InvalidResponse(error)
                }
                decoded.toApiRoute()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiClientException) {
            throw error
        } catch (error: IOException) {
            throw ApiClientException.Network(error)
        } catch (error: IllegalArgumentException) {
            throw ApiClientException.InvalidResponse(error)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

sealed class ApiClientException(cause: Throwable? = null) : Exception(cause) {
    class Network(cause: Throwable) : ApiClientException(cause)
    class InvalidResponse(cause: Throwable) : ApiClientException(cause)
    class Http(
        val statusCode: Int,
        val code: String,
    ) : ApiClientException()
}

data class ApiRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String,
    val maneuvers: List<ApiManeuver>,
    val provider: String,
)

data class ApiManeuver(
    val type: Int,
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val streetNames: List<String>,
    val travelMode: String?,
    val travelType: String?,
)

@Serializable
private data class CoordinateDto(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
private data class RouteRequestDto(
    val origin: CoordinateDto,
    val destination: CoordinateDto,
    val costing: String,
    val language: String,
)

@Serializable
private data class RouteGeometryDto(
    val format: String,
    @SerialName("encoded_polyline") val encodedPolyline: String,
)

@Serializable
private data class ManeuverDto(
    val type: Int,
    val instruction: String,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("begin_shape_index") val beginShapeIndex: Int,
    @SerialName("end_shape_index") val endShapeIndex: Int,
    @SerialName("street_names") val streetNames: List<String>,
    @SerialName("verbal_transition_alert_instruction")
    val verbalTransitionAlertInstruction: String?,
    @SerialName("verbal_pre_transition_instruction")
    val verbalPreTransitionInstruction: String?,
    @SerialName("verbal_post_transition_instruction")
    val verbalPostTransitionInstruction: String?,
    @SerialName("bearing_before") val bearingBefore: Int?,
    @SerialName("bearing_after") val bearingAfter: Int?,
    @SerialName("travel_mode") val travelMode: String?,
    @SerialName("travel_type") val travelType: String?,
)

@Serializable
private data class RouteResponseDto(
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val geometry: RouteGeometryDto,
    val maneuvers: List<ManeuverDto>,
    val provider: String,
)

@Serializable
private data class ErrorResponseDto(
    val code: String,
    val message: String,
)

private fun Coordinate.toDto(): CoordinateDto = CoordinateDto(
    latitude = latitude,
    longitude = longitude,
)

private fun RouteResponseDto.toApiRoute(): ApiRoute {
    require(geometry.format == "polyline6") { "unsupported route geometry format" }
    require(distanceMeters >= 0 && durationSeconds >= 0) { "negative route cost" }
    return ApiRoute(
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        encodedPolyline = geometry.encodedPolyline,
        maneuvers = maneuvers.map { maneuver ->
            ApiManeuver(
                type = maneuver.type,
                instruction = maneuver.instruction,
                distanceMeters = maneuver.distanceMeters,
                durationSeconds = maneuver.durationSeconds,
                beginShapeIndex = maneuver.beginShapeIndex,
                endShapeIndex = maneuver.endShapeIndex,
                streetNames = maneuver.streetNames,
                travelMode = maneuver.travelMode,
                travelType = maneuver.travelType,
            )
        },
        provider = provider,
    )
}
