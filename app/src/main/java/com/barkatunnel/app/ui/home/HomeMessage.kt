package com.barkatunnel.app.ui.home

import android.content.Context
import android.widget.Toast

object HomeMessage {

    fun show(
        context: Context,
        text: String
    ) {
        Toast.makeText(
            context,
            text,
            Toast.LENGTH_LONG
        ).show()
    }
}
