package com.barkatunnel.app.tools

import com.barkatunnel.app.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded measurements through VPN SOCKS. No direct route or automatic retry. */
class VpnSpeedTest(port: Int, private val vpnAlive: () -> Boolean) : AutoCloseable {
    private val cancelled = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private fun checkActive() { if (cancelled.get()) throw java.io.InterruptedIOException("cancelled"); check(vpnAlive()) { "VPN_DISCONNECTED" } }
    private fun rate(bytes: Long, start: Long) = bytes * 8.0 * 1000 / (System.nanoTime() - start).coerceAtLeast(1)
    fun run(update: (Int, Double, String) -> Unit) {
        checkActive(); update(R.string.tools_latency, 0.0, "…")
        val latency = (1..3).map {
            checkActive(); val start = System.nanoTime()
            val request = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=0&nonce=${System.nanoTime()}")
                .header("Cache-Control", "no-store").build()
            client.newCall(request).execute().use { check(it.isSuccessful) { "HTTP_${it.code}" } }
            (System.nanoTime() - start) / 1_000_000
        }.sorted()[1]
        checkActive()
        val request = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=2097152&nonce=${System.nanoTime()}")
            .header("Cache-Control", "no-store").header("Accept-Encoding", "identity").build()
        var received = 0L; var download = 0.0
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP_${response.code}" }
            val stream = requireNotNull(response.body).byteStream()
            val buffer = ByteArray(32_768); val start = System.nanoTime(); var last = 0L
            while (received < 2_097_152 && System.nanoTime() - start < 15_000_000_000L) {
                checkActive()
                val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), 2_097_152 - received).toInt())
                if (count < 0) break
                received += count; download = rate(received, start)
                if (System.nanoTime() - last >= 150_000_000) {
                    update(R.string.tools_download, download, "$latency ms"); last = System.nanoTime()
                }
            }
            check(received > 0L) { "EMPTY_RESPONSE" }
        }
        checkActive(); val uploadStart = System.nanoTime()
        val uploadBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = 262_144L
            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(32_768).also { java.security.SecureRandom().nextBytes(it) }
                var sent = 0L; var last = 0L
                while (sent < contentLength()) {
                    checkActive(); sink.write(chunk); sink.flush(); sent += chunk.size
                    if (System.nanoTime() - last >= 150_000_000) {
                        update(R.string.tools_upload, rate(sent, uploadStart), "$latency ms"); last = System.nanoTime()
                    }
                }
            }
        }
        client.newCall(Request.Builder().url("https://speed.cloudflare.com/__up").post(uploadBody).build())
            .execute().use { check(it.isSuccessful) { "HTTP_${it.code}" } }
        checkActive()
        update(R.string.tools_complete, download, String.format(Locale.getDefault(),
            "↓ %.2f Mbps  •  ↑ %.2f Mbps\nHTTPS : %d ms", download, rate(262_144, uploadStart), latency))
    }
    override fun close() {
        cancelled.set(true); client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
