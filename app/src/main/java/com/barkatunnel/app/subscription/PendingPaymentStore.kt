package com.barkatunnel.app.subscription

import android.content.Context

object PendingPaymentStore {
    private const val PREFS = "barka_pending_payment"
    private const val KEY_REFERENCE = "reference"
    private const val KEY_PLAN_ID = "plan_id"

    fun save(context: Context, reference: String, planId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REFERENCE, reference)
            .putString(KEY_PLAN_ID, planId)
            .apply()
    }

    fun reference(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REFERENCE, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
