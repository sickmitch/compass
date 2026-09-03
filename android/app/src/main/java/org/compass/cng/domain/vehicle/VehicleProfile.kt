package org.compass.cng.domain.vehicle

data class VehicleProfile(
    val id: String,
    val name: String,
    val effectiveCngRangeKm: Double,
    val cngReserveKm: Double,
    val effectiveGasolineRangeKm: Double,
    val gasolineReserveKm: Double,
) {
    init {
        require(id.isNotBlank()) { "vehicle profile id must not be blank" }
        require(name.isNotBlank() && name.length <= 60) {
            "vehicle profile name must contain 1 to 60 characters"
        }
        require(effectiveCngRangeKm > 0 && effectiveCngRangeKm <= 2_000) {
            "effective CNG range must be between 0 and 2,000 km"
        }
        require(cngReserveKm >= 0 && cngReserveKm < effectiveCngRangeKm) {
            "CNG reserve must be below effective CNG range"
        }
        require(effectiveGasolineRangeKm > 0 && effectiveGasolineRangeKm <= 2_000) {
            "effective gasoline range must be between 0 and 2,000 km"
        }
        require(gasolineReserveKm >= 0 && gasolineReserveKm < effectiveGasolineRangeKm) {
            "gasoline reserve must be below effective gasoline range"
        }
    }
}

data class VehicleProfiles(
    val profiles: List<VehicleProfile> = emptyList(),
    val selectedProfileId: String? = null,
) {
    init {
        require(profiles.map(VehicleProfile::id).distinct().size == profiles.size) {
            "vehicle profile ids must be unique"
        }
        require(selectedProfileId == null || profiles.any { it.id == selectedProfileId }) {
            "selected vehicle profile must exist"
        }
    }

    val selectedProfile: VehicleProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId }
}

interface VehicleProfileRepository {
    fun load(): VehicleProfiles

    fun save(profile: VehicleProfile): VehicleProfiles

    fun select(profileId: String): VehicleProfiles

    fun delete(profileId: String): VehicleProfiles
}

class InMemoryVehicleProfileRepository(
    initial: VehicleProfiles = VehicleProfiles(),
) : VehicleProfileRepository {
    private var value = initial

    override fun load(): VehicleProfiles = value

    override fun save(profile: VehicleProfile): VehicleProfiles {
        val profiles = value.profiles.filterNot { it.id == profile.id } + profile
        value = VehicleProfiles(
            profiles = profiles.sortedBy { it.name.lowercase() },
            selectedProfileId = value.selectedProfileId ?: profile.id,
        )
        return value
    }

    override fun select(profileId: String): VehicleProfiles {
        require(value.profiles.any { it.id == profileId })
        value = value.copy(selectedProfileId = profileId)
        return value
    }

    override fun delete(profileId: String): VehicleProfiles {
        val profiles = value.profiles.filterNot { it.id == profileId }
        value = VehicleProfiles(
            profiles = profiles,
            selectedProfileId = if (value.selectedProfileId == profileId) {
                profiles.firstOrNull()?.id
            } else {
                value.selectedProfileId
            },
        )
        return value
    }
}
