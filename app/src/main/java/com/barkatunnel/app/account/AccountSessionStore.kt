package com.barkatunnel.app.account

interface AccountSessionStore {

    fun save(session: AccountSession)

    fun get(): AccountSession?

    fun clear()
}
