package com.borealroutes.app

import android.app.Application
import org.maplibre.android.MapLibre

class BorealApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
