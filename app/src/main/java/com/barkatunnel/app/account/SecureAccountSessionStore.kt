package com.barkatunnel.app.account

import android.content.Context

class SecureAccountSessionStore(
    context: Context
) : AccountSessionStore {

    private val prefs = context.getSharedPreferences(
        "barka_account_session",
        Context.MODE_PRIVATE
    )

    override fun save(session: AccountSession) {
        prefs.edit()
            .putString(KEY_ACCOUNT_ID, session.accountId)
            .putString(KEY_TOKEN, session.token)
            .apply()
    }

    override fun get(): AccountSession? {
        val accountId = prefs.getString(KEY_ACCOUNT_ID, null)
        val token = prefs.getString(KEY_TOKEN, null)

        if (accountId.isNullOrBlank() || token.isNullOrBlank()) {
            return null
        }

        return AccountSession(
            accountId = accountId,
            token = token
        )
    }

    override fun clear() {
        prefs.edit()
            .clear()
            .apply()
    }

    companion object {
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_TOKEN = "token"
    }
}
