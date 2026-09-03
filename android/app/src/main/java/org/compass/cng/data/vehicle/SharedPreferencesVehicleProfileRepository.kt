package org.compass.cng.data.vehicle

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.compass.cng.domain.vehicle.VehicleProfile
import org.compass.cng.domain.vehicle.VehicleProfileRepository
import org.compass.cng.domain.vehicle.VehicleProfiles

class SharedPreferencesVehicleProfileRepository internal constructor(
    context: Context,
    private val codec: VehicleProfileDocumentCodec = VehicleProfileDocumentCodec(),
) : VehicleProfileRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): VehicleProfiles = codec.decode(preferences.getString(DOCUMENT_KEY, null))

    override fun save(profile: VehicleProfile): VehicleProfiles {
        val current = load()
        val updatedProfiles = current.profiles
            .filterNot { it.id == profile.id }
            .plus(profile)
            .sortedBy { it.name.lowercase() }
        return persist(
            VehicleProfiles(
                profiles = updatedProfiles,
                selectedProfileId = current.selectedProfileId ?: profile.id,
            ),
        )
    }

    override fun select(profileId: String): VehicleProfiles {
        val current = load()
        require(current.profiles.any { it.id == profileId }) { "vehicle profile does not exist" }
        return persist(current.copy(selectedProfileId = profileId))
    }

    override fun delete(profileId: String): VehicleProfiles {
        val current = load()
        val updatedProfiles = current.profiles.filterNot { it.id == profileId }
        return persist(
            VehicleProfiles(
                profiles = updatedProfiles,
                selectedProfileId = if (current.selectedProfileId == profileId) {
                    updatedProfiles.firstOrNull()?.id
                } else {
                    current.selectedProfileId
                },
            ),
        )
    }

    private fun persist(document: VehicleProfiles): VehicleProfiles {
        check(preferences.edit().putString(DOCUMENT_KEY, codec.encode(document)).commit()) {
            "vehicle profiles could not be persisted"
        }
        return document
    }

    private companion object {
        const val PREFERENCES_NAME = "compass_vehicle_profiles"
        const val DOCUMENT_KEY = "vehicle_profiles_v1"
    }
}

internal class VehicleProfileDocumentCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    },
) {
    fun encode(profiles: VehicleProfiles): String = json.encodeToString(
        StoredVehicleProfiles(
            profiles = profiles.profiles.map(StoredVehicleProfile::fromDomain),
            selectedProfileId = profiles.selectedProfileId,
        ),
    )

    fun decode(value: String?): VehicleProfiles {
        if (value.isNullOrBlank()) return VehicleProfiles()
        return try {
            val stored = json.decodeFromString<StoredVehicleProfiles>(value)
            VehicleProfiles(
                profiles = stored.profiles.map(StoredVehicleProfile::toDomain),
                selectedProfileId = stored.selectedProfileId,
            )
        } catch (_: SerializationException) {
            VehicleProfiles()
        } catch (_: IllegalArgumentException) {
            VehicleProfiles()
        }
    }
}

@Serializable
private data class StoredVehicleProfiles(
    val schemaVersion: Int = 1,
    val profiles: List<StoredVehicleProfile>,
    val selectedProfileId: String?,
) {
    init {
        require(schemaVersion == 1) { "unsupported vehicle profile schema" }
    }
}

@Serializable
private data class StoredVehicleProfile(
    val id: String,
    val name: String,
    val effectiveCngRangeKm: Double,
    val cngReserveKm: Double,
    val effectiveGasolineRangeKm: Double,
    val gasolineReserveKm: Double,
) {
    fun toDomain(): VehicleProfile = VehicleProfile(
        id = id,
        name = name,
        effectiveCngRangeKm = effectiveCngRangeKm,
        cngReserveKm = cngReserveKm,
        effectiveGasolineRangeKm = effectiveGasolineRangeKm,
        gasolineReserveKm = gasolineReserveKm,
    )

    companion object {
        fun fromDomain(profile: VehicleProfile): StoredVehicleProfile = StoredVehicleProfile(
            id = profile.id,
            name = profile.name,
            effectiveCngRangeKm = profile.effectiveCngRangeKm,
            cngReserveKm = profile.cngReserveKm,
            effectiveGasolineRangeKm = profile.effectiveGasolineRangeKm,
            gasolineReserveKm = profile.gasolineReserveKm,
        )
    }
}
