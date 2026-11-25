package com.campusmeal.android.app

import android.app.Application

class CampusMealApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this).also { it.installTelemetry(this) }
    }
}
