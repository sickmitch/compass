package org.compass.cng.data.vehicle

import org.compass.cng.domain.vehicle.VehicleProfile
import org.compass.cng.domain.vehicle.VehicleProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleProfileDocumentCodecTest {
    private val codec = VehicleProfileDocumentCodec()

    @Test
    fun roundTripsProfilesAndSelectedVehicle() {
        val profiles = VehicleProfiles(
            profiles = listOf(
                VehicleProfile("panda", "Panda", 240.0, 25.0, 520.0, 50.0),
                VehicleProfile("doblo", "Doblò", 320.0, 35.0, 600.0, 60.0),
            ),
            selectedProfileId = "doblo",
        )

        assertEquals(profiles, codec.decode(codec.encode(profiles)))
    }

    @Test
    fun rejectsCorruptOrUnsupportedDocumentsWithoutInventingProfiles() {
        assertEquals(VehicleProfiles(), codec.decode("not-json"))
        assertEquals(
            VehicleProfiles(),
            codec.decode("""{"schemaVersion":2,"profiles":[],"selectedProfileId":null}"""),
        )
    }
}
