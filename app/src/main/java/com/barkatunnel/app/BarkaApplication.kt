package com.barkatunnel.app

import android.app.Application
import com.barkatunnel.app.core.AppContainer

class BarkaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(this)
    }
}
