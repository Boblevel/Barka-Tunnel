package com.barkatunnel.app.vpnprofile

object ConnectPayload {
    fun expand(raw: String, host: String, port: Int): ByteArray {
        require(raw.length <= 4096 && host.isNotBlank() && host.none { it.isWhitespace() || it == '/' } && port in 1..65535)
        val lines = raw.replace("[crlf]", "\r\n").split("\r\n")
        require(lines.size >= 3 && lines.first() == "CONNECT [host]:[port] HTTP/1.1" && lines.takeLast(2) == listOf("", ""))
        val headers = lines.drop(1).dropLast(2)
        require(headers.size <= 12)
        val names = mutableSetOf<String>()
        headers.forEach {
            val name=it.substringBefore(':'); val value=it.substringAfter(':', "").trimStart(' ')
            require(':' in it && Regex("[A-Za-z0-9-]{1,40}").matches(name) && names.add(name.lowercase()))
            require(value.length <= 200 && value.all { c -> c.code in 32..126 })
        }
        val authority=if (':' in host && !host.startsWith('[')) "[$host]:$port" else "$host:$port"
        return ("CONNECT $authority HTTP/1.1\r\n"+headers.joinToString("") { "$it\r\n" }+"\r\n").toByteArray(Charsets.ISO_8859_1)
    }
}
