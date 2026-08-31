package com.barkatunnel.app.vpnc6

interface C6ProtocolEngine {
    val socksAddress: String
    fun start()
    fun isRunning(): Boolean
    fun stop()
}
