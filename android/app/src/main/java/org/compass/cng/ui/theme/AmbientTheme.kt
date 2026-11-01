package org.compass.cng.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Neutral light retains the last theme; a new threshold must persist before switching. */
internal fun ambientThemeTarget(lux: Float): Boolean? = when {
    !lux.isFinite() || lux < 0f -> null
    lux <= 10f -> true
    lux >= 50f -> false
    else -> null
}

@Composable
internal fun ambientDarkTheme(systemDarkTheme: Boolean): Boolean {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var sensorDark by remember { mutableStateOf<Boolean?>(null) }
    var target by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(target) {
        val pending = target ?: return@LaunchedEffect
        // Ignore brief shadows/headlights without requiring repeated sensor events:
        // light sensors may report only when illumination changes.
        delay(2_000)
        sensorDark = pending
    }

    DisposableEffect(context, lifecycle) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        var registered = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                target = event.values.firstOrNull()?.let(::ambientThemeTarget)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun updateRegistration() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (!registered && manager != null && sensor != null) {
                    registered = manager.registerListener(
                        listener, sensor, SensorManager.SENSOR_DELAY_NORMAL,
                    )
                    if (!registered) sensorDark = null
                }
            } else {
                manager?.unregisterListener(listener)
                registered = false
                target = null
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateRegistration() }
        lifecycle.addObserver(observer)
        updateRegistration()
        onDispose {
            lifecycle.removeObserver(observer)
            manager?.unregisterListener(listener)
        }
    }
    return sensorDark ?: systemDarkTheme
}
