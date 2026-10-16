package com.nikosm.voiceassistant

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Comparator
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Seam 14's trust decisions, against a real TLS peer on loopback.
 *
 * There is no way to test a hostname or a pin without a handshake, and a handshake needs a
 * certificate — so this generates one with `keytool` (shipped in the JDK the tests already run on)
 * into a temporary directory, serves it from a loopback `SSLServerSocket`, and drives actual
 * requests through `buildPinnedClient`. Nothing here leaves the machine, and the certificate is
 * fabricated: AGENTS.md §5 keeps real ones out of the repo.
 *
 * The first test is the regression guard for a defect found on 2026-10-16, when every HTTPS call
 * the application made was failing hostname verification against certificates that plainly matched.
 * The cause was `hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())` — upstream's S1
 * line, which on Android names a self-contained verifier and on the JVM names one that only works
 * when called from inside the JDK's own HTTPS machinery. It returns false for every standalone
 * caller, so putting it back turns the first test red; that is what it is here for.
 */
class PinnedClientTlsTest {

    private lateinit var temp: Path
    private val approvals = mutableListOf<CertApprovalRequest>()

    @Before
    fun setUp() {
        temp = Files.createTempDirectory("pinned-client-test")
    }

    @After
    fun tearDown() {
        Files.walk(temp).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `a pinned host named by an iPAddress SAN is served`() {
        // The exact shape of the development gateway's certificate: the host is an IP literal and
        // the certificate carries it as an iPAddress SAN, which is the case that broke.
        val server = TlsServer(certificateWithSan("ip:127.0.0.1,dns:localhost"))

        server.use {
            val body = request(
                url = "https://127.0.0.1:${server.port}/",
                pins = mapOf("127.0.0.1" to sha256Fingerprint(server.certificate)),
            )
            assertEquals("ok", body)
        }
        assertTrue("a matching pin must not ask for approval: $approvals", approvals.isEmpty())
    }

    @Test
    fun `an unpinned host is refused and offered for approval`() {
        val server = TlsServer(certificateWithSan("ip:127.0.0.1,dns:localhost"))

        server.use {
            val failure = runCatching {
                request(url = "https://127.0.0.1:${server.port}/", pins = emptyMap())
            }.exceptionOrNull()

            // The request must not succeed, must fail in the handshake, and must have raised the
            // approval request the UI shows — that last part is the TOFU prompt working.
            assertTrue("expected a handshake failure, got $failure", failure != null)
            val raised = approvals.singleOrNull()
            assertTrue("expected one approval request, got $approvals", raised != null)
            assertEquals("127.0.0.1", raised!!.host)
            assertEquals(sha256Fingerprint(server.certificate), raised.fingerprint)
        }
    }

    @Test
    fun `a pinned host the certificate does not name is still refused`() {
        // "localhost" resolves to 127.0.0.1, so the pin matches and the handshake gets past the
        // trust manager; what fails is hostname verification, because this certificate names the
        // IP and not the name. That is the direction that must keep working: the fix for the first
        // test must not be "accept any hostname".
        val server = TlsServer(certificateWithSan("ip:127.0.0.1"))

        server.use {
            val failure = runCatching {
                request(url = "https://localhost:${server.port}/", pins = mapOf("localhost" to sha256Fingerprint(server.certificate)))
            }.exceptionOrNull()

            assertTrue("a certificate that does not name the host must be refused, got: $failure", failure != null)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun request(url: String, pins: Map<String, String>): String {
        val client: OkHttpClient = buildPinnedClient(
            trustedCerts = { pins },
            onUntrusted = { approvals.add(it) },
        )
        return client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url(url).build())
            .execute().use { it.body?.string() ?: "" }
    }

    /** A self-signed certificate for 127.0.0.1 carrying [san], generated by the JDK's `keytool`. */
    private fun certificateWithSan(san: String): TlsServer.Material {
        val keystore = temp.resolve("keystore-${san.hashCode()}.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=localhost", "-ext", "san=$san",
            "-keystore", keystore.toString(), "-storetype", "PKCS12",
            "-storepass", STOREPASS, "-keypass", STOREPASS, "-noprompt",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "keytool failed: $output"
        }

        val ks = KeyStore.getInstance("PKCS12")
        File(keystore.toString()).inputStream().use { ks.load(it, STOREPASS.toCharArray()) }
        val key = ks.getKey("test", STOREPASS.toCharArray()) as java.security.PrivateKey
        return TlsServer.Material(key, ks.getCertificate("test") as X509Certificate)
    }

    /**
     * A one-shot TLS server on loopback: it accepts a single connection, answers a fixed 200, and
     * closes. One connection is all any test needs, and a socket that does not linger keeps the
     * suite fast and the failure messages clean.
     */
    private class TlsServer(val material: Material) : AutoCloseable {

        class Material(val key: java.security.PrivateKey, val cert: X509Certificate)

        val certificate: X509Certificate get() = material.cert
        val port: Int

        private val socket: SSLServerSocket

        init {
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            ks.setKeyEntry("server", material.key, null, arrayOf(material.cert))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, null)
            val context = SSLContext.getInstance("TLS")
            context.init(kmf.keyManagers, null, null)
            socket = context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            port = socket.localPort
            Thread({
                runCatching {
                    (socket.accept() as SSLSocket).use { s ->
                        s.outputStream.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()
                        )
                        s.outputStream.flush()
                    }
                }
            }, "pinned-client-test-server").apply { isDaemon = true }.start()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val STOREPASS = "changeit"
    }
}
