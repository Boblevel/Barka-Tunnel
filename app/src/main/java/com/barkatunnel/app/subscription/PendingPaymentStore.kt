package com.barkatunnel.app.subscription

import android.content.Context

object PendingPaymentStore {
    private const val PREFS = "barka_pending_payment"
    private const val KEY_REFERENCE = "reference"
    private const val KEY_PLAN_ID = "plan_id"
    private const val KEY_CONFIRMED_CODE = "confirmed_code"

    @Synchronized
    fun save(context: Context, reference: String, planId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getString(KEY_CONFIRMED_CODE, null).isNullOrBlank()) return false
        return prefs.edit()
            .putString(KEY_REFERENCE, reference)
            .putString(KEY_PLAN_ID, planId)
            .remove(KEY_CONFIRMED_CODE)
            .commit()
    }

    fun reference(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REFERENCE, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun planId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLAN_ID, null)

    fun confirmedCode(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIRMED_CODE, null)?.takeIf { it.isNotBlank() }

    @Synchronized
    fun saveConfirmedCode(context: Context, reference: String, code: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_REFERENCE, null) != reference) return false
        return prefs.edit().putString(KEY_CONFIRMED_CODE, code).commit()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
