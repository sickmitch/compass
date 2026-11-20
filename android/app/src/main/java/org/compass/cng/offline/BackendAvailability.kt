package org.compass.cng.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Transport availability shared by planning and CNG controls, not a data freshness claim. */
class BackendAvailability {
    private var network = true
    private var server = true
    private val mutable = MutableStateFlow(true)
    val available = mutable.asStateFlow()

    @Synchronized fun networkChanged(connected: Boolean) {
        if (!connected) server = false
        network = connected
        mutable.value = network && server
    }

    @Synchronized fun serverResponded(ready: Boolean) {
        server = network && ready
        mutable.value = network && server
    }
}
