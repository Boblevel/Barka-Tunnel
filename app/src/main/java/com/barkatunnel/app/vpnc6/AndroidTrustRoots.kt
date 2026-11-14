package com.barkatunnel.app.vpnc6

import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Pass Android's current trust anchors to the separate Go process, including
 * Conscrypt updates. Never import a certificate obtained from the VPN peer. */
internal object AndroidTrustRoots {
    fun prepare(builder: ProcessBuilder, directory: File): Int {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val roots = factory.trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
        return write(builder, directory, roots)
    }

    internal fun write(builder: ProcessBuilder, directory: File, roots: List<X509Certificate>): Int {
        require(roots.isNotEmpty()) { "Aucun certificat de confiance Android disponible." }
        check(directory.mkdirs() || directory.isDirectory) { "Magasin TLS Android inaccessible." }
        val bundle = File(directory, "roots.pem")
        val certificates = roots.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }.distinct()
        bundle.writeText(certificates.joinToString("") {
            "-----BEGIN CERTIFICATE-----\n" + it.chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"
        }, Charsets.US_ASCII)
        val empty = File(directory, "empty")
        check(empty.mkdirs() || empty.isDirectory) { "Répertoire TLS inaccessible." }
        // Avoid supplementing Android's selected roots with Go's legacy dirs.
        builder.environment()["SSL_CERT_FILE"] = bundle.absolutePath
        builder.environment()["SSL_CERT_DIR"] = empty.absolutePath
        return certificates.size
    }
}
