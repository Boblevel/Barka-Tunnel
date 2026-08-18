package com.barkatunnel.app.ui.home

import android.app.AlertDialog
import android.content.Context

object HomeNetworkDialog {

    fun show(
        context: Context,
        onSelected: (NetworkOption) -> Unit
    ) {
        val options = NetworkOption.ALL
        val labels = options
            .map { it.displayName }
            .toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Choisir le réseau")
            .setItems(labels) { _, index ->
                onSelected(options[index])
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
