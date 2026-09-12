package com.barkatunnel.app.subscription

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.barkatunnel.app.R

object PaymentConfirmationDialog {
    fun show(
        activity: Activity,
        code: String,
        offer: String,
        onCopy: () -> Unit,
        onActivate: () -> Unit
    ): AlertDialog {
        val content = activity.layoutInflater.inflate(R.layout.dialog_payment_confirmed, null)
        content.findViewById<TextView>(R.id.paymentConfirmedOffer).text = offer
        content.findViewById<TextView>(R.id.paymentConfirmedCode).text = code
        val dialog = AlertDialog.Builder(activity).setView(content).create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        fun bind(id: Int, action: () -> Unit) {
            content.findViewById<View>(id).setOnClickListener {
                try {
                    action()
                    dialog.dismiss()
                } catch (_: Exception) {
                    Toast.makeText(activity, R.string.payment_action_retry, Toast.LENGTH_LONG).show()
                }
            }
        }
        bind(R.id.paymentConfirmCopy, onCopy)
        bind(R.id.paymentConfirmActivate, onActivate)
        dialog.show()
        val density = activity.resources.displayMetrics.density
        val width = minOf(activity.resources.displayMetrics.widthPixels - (48 * density).toInt(), (560 * density).toInt())
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
