package com.barkatunnel.app.vpnc6

import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import com.jcraft.jsch.SocketFactory
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** SSH encapsulated in authenticated TLS, e.g. stunnel in front of sshd. */
class SshTlsEngine(private val config: VpnProfileConfig.SshTls, private val socksPort: Int,
                   private val isCancelled: () -> Boolean = { false }) : C6ProtocolEngine {
    override val socksAddress = "127.0.0.1:$socksPort"
    @Volatile private var transport: Socket? = null
    @Volatile private var stopped = false
    private fun checkActive() {
        if (stopped || isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("Connexion annulée.")
    }
    private val factory = object : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val raw = Socket(); transport = raw
            var stage = "connexion TCP au serveur SSH TLS"
            try {
                checkActive(); raw.connect(InetSocketAddress(config.host, config.port), 12000); checkActive()
                stage = "négociation TLS et validation du certificat SNI"
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, config.sni, config.port, true) as SSLSocket
                transport = tls
                tls.soTimeout = 12000
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                tls.startHandshake(); checkActive()
                return tls
            } catch (e: Exception) {
                runCatching { transport?.close() }; runCatching { raw.close() }
                if (e is InterruptedException) throw e
                throw java.io.IOException("SSL/TLS → SSH : échec à l’étape $stage (${e.javaClass.simpleName}). Vérifiez le serveur, le port et le SNI du panel.", e)
            }
        }
        override fun getInputStream(socket: Socket) = socket.getInputStream()
        override fun getOutputStream(socket: Socket) = socket.getOutputStream()
    }
    private val proxy = SshSocksProxy(config.host,config.port,config.username,config.password,socksPort,
        connectTimeoutMs=30000,isCancelled={stopped || isCancelled()},socketFactory=factory)
    override fun start() { try { checkActive(); proxy.start() } catch(e:Exception) { stop(); throw e } }
    override fun stop() { stopped=true; runCatching { transport?.close() }; proxy.stop(); transport=null }
    override fun isRunning() = !stopped && proxy.isRunning()
}
