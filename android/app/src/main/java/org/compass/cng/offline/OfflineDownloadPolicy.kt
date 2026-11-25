package org.compass.cng.offline

/** Applied to new downloads and updates alike; Wi-Fi never prompts, even if metered. */
fun needsOfflineMobileConfirmation(wifi: Boolean, metered: Boolean, bytes: Long, mobile: Boolean = false): Boolean =
    !wifi && (mobile || metered) && bytes >= 100L * 1024 * 1024

data class OfflineMobileConfirmation(val owner: String, val bytes: Long, val newArea: String? = null)
