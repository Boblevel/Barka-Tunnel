package com.barkatunnel.app.account

class InMemoryAccountSessionStore : AccountSessionStore {

    private var session: AccountSession? = null

    override fun save(session: AccountSession) {
        this.session = session
    }

    override fun get(): AccountSession? {
        return session
    }

    override fun clear() {
        session = null
    }
}
