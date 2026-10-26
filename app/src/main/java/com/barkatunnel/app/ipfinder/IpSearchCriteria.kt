package com.barkatunnel.app.ipfinder

/** Immutable criteria: switching the selected VPN profile cannot change an ongoing search. */
data class IpSearchCriteria(val orange: Boolean, val raw: String) {
    private val patterns = raw.split(Regex("[\\n,;]+")) .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun matches(ip: String): Boolean = if (orange) OrangeIpPolicy.matches(ip) else patterns.isEmpty() || patterns.any {
        when {
            it.startsWith("=") -> it.drop(1).trim().let { value -> value.isNotEmpty() && ip == value }
            it.startsWith("^") && it.endsWith("$") -> it.drop(1).dropLast(1).trim().let { value -> value.isNotEmpty() && ip == value }
            it.startsWith("^") -> it.drop(1).trim().let { value -> value.isNotEmpty() && ip.startsWith(value) }
            it.endsWith("$") -> it.dropLast(1).trim().let { value -> value.isNotEmpty() && ip.endsWith(value) }
            else -> ip.contains(it)
        }
    }
}
