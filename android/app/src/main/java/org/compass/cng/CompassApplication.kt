package org.compass.cng

import android.app.Application
import org.compass.cng.di.AppContainer
import org.maplibre.android.MapLibre

class CompassApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
    }
}
