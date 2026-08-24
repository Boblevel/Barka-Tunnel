package com.barkatunnel.app.ui.home

import android.app.AlertDialog
import android.content.Context
import com.barkatunnel.app.R

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
            .setTitle(R.string.choose_network_home)
            .setItems(labels) { _, index ->
                onSelected(options[index])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
