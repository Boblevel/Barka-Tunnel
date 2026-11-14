package com.barkatunnel.app.vpnc6

import android.app.Application
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AndroidTrustRootsTest {
    @Test fun exportedRootsAreParseableAndOverrideLegacyGoPaths() {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val root = factory.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers.first()
        val directory = Files.createTempDirectory("barka-trust-test").toFile()
        try {
            val builder = ProcessBuilder("unused")
            builder.environment()["SSL_CERT_FILE"] = "/old/certs"
            builder.environment()["SSL_CERT_DIR"] = "/old/dir"
            assertEquals(1, AndroidTrustRoots.write(builder, directory, listOf(root, root)))
            val bundle = java.io.File(requireNotNull(builder.environment()["SSL_CERT_FILE"]))
            val decoded = bundle.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificates(it)
            }
            assertEquals(1, decoded.size)
            assertArrayEquals(root.encoded, decoded.first().encoded)
            val empty = java.io.File(requireNotNull(builder.environment()["SSL_CERT_DIR"]))
            assertTrue(empty.isDirectory)
            assertTrue(empty.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun emptyTrustStoreFailsClosed() {
        val directory = Files.createTempDirectory("barka-empty-test").toFile()
        try {
            val builder = ProcessBuilder("unused")
            val before = builder.environment().toMap()
            try {
                AndroidTrustRoots.write(builder, directory, emptyList())
                fail("Empty trust store accepted")
            } catch (_: IllegalArgumentException) {}
            assertEquals(before, builder.environment())
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
