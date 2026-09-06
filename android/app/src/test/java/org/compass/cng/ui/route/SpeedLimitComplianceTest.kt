package org.compass.cng.ui.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeedLimitComplianceTest {
    @Test
    fun unavailableInputsNeverCreateAWarning() {
        assertEquals(
            SpeedLimitComplianceStatus.UNAVAILABLE,
            speedLimitComplianceStatus(
                previous = SpeedLimitComplianceStatus.OVER_LIMIT,
                speedMetersPerSecond = null,
                speedLimitKph = 50,
            ),
        )
        assertEquals(
            SpeedLimitComplianceStatus.UNAVAILABLE,
            speedLimitComplianceStatus(
                previous = SpeedLimitComplianceStatus.WITHIN_LIMIT,
                speedMetersPerSecond = 20.0,
                speedLimitKph = null,
            ),
        )
    }

    @Test
    fun warningStartsOnlyAtTheEntryBuffer() {
        assertEquals(
            SpeedLimitComplianceStatus.WITHIN_LIMIT,
            complianceAtKph(previous = SpeedLimitComplianceStatus.WITHIN_LIMIT, speedKph = 54.9),
        )
        assertEquals(
            SpeedLimitComplianceStatus.OVER_LIMIT,
            complianceAtKph(previous = SpeedLimitComplianceStatus.WITHIN_LIMIT, speedKph = 55.0),
        )
    }

    @Test
    fun warningUsesAHysteresisBandBeforeClearing() {
        assertEquals(
            SpeedLimitComplianceStatus.OVER_LIMIT,
            complianceAtKph(previous = SpeedLimitComplianceStatus.OVER_LIMIT, speedKph = 52.1),
        )
        assertEquals(
            SpeedLimitComplianceStatus.WITHIN_LIMIT,
            complianceAtKph(previous = SpeedLimitComplianceStatus.OVER_LIMIT, speedKph = 52.0),
        )
    }

    @Test
    fun aChangedLimitIsEvaluatedAgainstTheCurrentSpeed() {
        assertEquals(
            SpeedLimitComplianceStatus.WITHIN_LIMIT,
            speedLimitComplianceStatus(
                previous = SpeedLimitComplianceStatus.OVER_LIMIT,
                speedMetersPerSecond = 80.0 / 3.6,
                speedLimitKph = 130,
            ),
        )
        assertEquals(
            SpeedLimitComplianceStatus.OVER_LIMIT,
            speedLimitComplianceStatus(
                previous = SpeedLimitComplianceStatus.WITHIN_LIMIT,
                speedMetersPerSecond = 80.0 / 3.6,
                speedLimitKph = 50,
            ),
        )
    }

    @Test
    fun displaySpeedUsesStandardKphRounding() {
        assertEquals(79, speedKphForDisplay(22.0))
        assertEquals(null, speedKphForDisplay(Double.NaN))
        assertEquals(null, speedKphForDisplay(-1.0))
    }

    @Test
    fun policyRejectsAnInvertedHysteresisBand() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeedLimitCompliancePolicy(enterBufferKph = 2.0, exitBufferKph = 2.0)
        }
    }

    private fun complianceAtKph(
        previous: SpeedLimitComplianceStatus,
        speedKph: Double,
    ): SpeedLimitComplianceStatus = speedLimitComplianceStatus(
        previous = previous,
        speedMetersPerSecond = speedKph / 3.6,
        speedLimitKph = 50,
    )
}
