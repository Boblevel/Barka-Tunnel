package com.barkatunnel.app.access

interface AccessProvider {
    fun getAccessState(): AccessState
}
