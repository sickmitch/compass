package org.compass.cng

import android.app.Application
import android.util.Log
import org.compass.cng.di.AppContainer
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class CompassApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(
            BuildConfig.COMPASS_MAP_AMBIENT_CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    Log.i(MAP_CACHE_LOG_TAG, "ambient_cache_ready bytes=${BuildConfig.COMPASS_MAP_AMBIENT_CACHE_BYTES}")
                }

                override fun onError(message: String) {
                    Log.w(MAP_CACHE_LOG_TAG, "ambient_cache_configuration_failed message=$message")
                }
            },
        )
        container = AppContainer(this)
    }

    private companion object {
        const val MAP_CACHE_LOG_TAG = "CompassMapCache"
    }
}
