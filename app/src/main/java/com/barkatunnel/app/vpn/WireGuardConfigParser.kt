package com.barkatunnel.app.vpn

import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

object WireGuardConfigParser {

    fun parse(configText: String): Config {
        return BufferedReader(StringReader(configText)).use {
            Config.parse(it)
        }
    }
}
