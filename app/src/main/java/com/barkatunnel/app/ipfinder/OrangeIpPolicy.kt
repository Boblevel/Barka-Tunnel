package com.barkatunnel.app.ipfinder

/** Orange BF: match complete IPv4 octets, never a substring or a user wildcard. */
internal object OrangeIpPolicy {
    const val PATTERNS = "10.161;10.76;10.74;10.102;10.75;10.195;10.196;10.197;10.198;10.199;10.204;10.205;10.206;10.207;10.208;10.209;10.210;10.212;10.213;10.214;10.215;10.216;10.217;10.218;10.219;10.220;10.221;10.222;10.223;10.224;10.225;10.226;10.227;10.228;10.229;10.230;10.143;10.165"
    private val prefixes = PATTERNS.split(';').toSet()

    fun matches(ip: String?): Boolean {
        val octets = ip?.split('.') ?: return false
        if (octets.size != 4 || octets.any { part ->
                part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                    (part.length > 1 && part.startsWith('0')) || part.toInt() !in 0..255
            }) return false
        return "${octets[0]}.${octets[1]}" in prefixes
    }
}
