package com.barkatunnel.app.ui.home

import android.content.Context
import com.barkatunnel.app.BarkaApplication

object HomeControllerFactory {

    fun create(
        context: Context
    ): HomeControllerResult {

        val app = context.applicationContext as BarkaApplication

        val runtime = HomeRuntimeFactory.create(
            context = context,
            container = app.container
        ) ?: return HomeControllerResult.LoginRequired

        return HomeControllerResult.State(
            HomeController(runtime).currentState()
        )
    }

    fun createController(
        context: Context
    ): HomeController? {

        val app = context.applicationContext as BarkaApplication

        val runtime = HomeRuntimeFactory.create(
            context = context,
            container = app.container
        ) ?: return null

        return HomeController(runtime)
    }
}
