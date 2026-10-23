package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * The fail-closed rule: a trust manager that returns without throwing tells the TLS stack the
 * chain is trusted, so a null or empty chain must throw before the pins are read and before the
 * approval callback is offered.
 *
 * The certificates are fabricated and committed as public certs only; their private keys were
 * generated into /tmp and deleted, so nothing secret is in the repository.
 */
class PinnedTrustManagerTest {

    private val certA: X509Certificate = loadCertificate("test-cert-a.pem")
    private val certB: X509Certificate = loadCertificate("test-cert-b.pem")
    private val approvals = mutableListOf<CertApprovalRequest>()
    private val pins = mutableMapOf<String, String>()
    private var pinReads = 0

    private fun manager() = PinnedTrustManager(
        trustedCerts = {
            pinReads++
            pins
        },
        onUntrusted = { approvals.add(it) }
    )

    @Test
    fun `a null chain throws the empty chain error`() {
        pins["existing.example"] = "existing-pin"
        val before = pins.toMap()

        val failure = assertThrows(SSLHandshakeException::class.java) {
            manager().verify(null, "celeste-test.invalid")
        }

        assertEquals("Empty certificate chain — connection rejected", failure.message)
        assertEquals(0, approvals.size)
        assertEquals(before, pins)
        assertEquals("the pins must not be read for an absent chain", 0, pinReads)
    }

    @Test
    fun `an empty chain throws the empty chain error`() {
        pins["existing.example"] = "existing-pin"
        val before = pins.toMap()

        val failure = assertThrows(SSLHandshakeException::class.java) {
            manager().verify(emptyArray(), "celeste-test.invalid")
        }

        assertEquals("Empty certificate chain — connection rejected", failure.message)
        assertEquals(0, approvals.size)
        assertEquals(before, pins)
        assertEquals("the pins must not be read for an absent chain", 0, pinReads)
    }

    @Test
    fun `every checkServerTrusted overload rejects a null or empty chain`() {
        pins["existing.example"] = "existing-pin"
        val before = pins.toMap()
        val manager = manager()
        val socket = StubSocket("socket.example")
        val engine = SSLContext.getDefault().createSSLEngine("engine.example", 443)

        val calls: List<() -> Unit> = listOf(
            { manager.checkServerTrusted(null as Array<X509Certificate>?, "RSA") },
            { manager.checkServerTrusted(emptyArray(), "RSA") },
            { manager.checkServerTrusted(null as Array<X509Certificate>?, "RSA", socket) },
            { manager.checkServerTrusted(emptyArray(), "RSA", socket) },
            { manager.checkServerTrusted(null as Array<X509Certificate>?, "RSA", engine) },
            { manager.checkServerTrusted(emptyArray(), "RSA", engine) }
        )

        calls.forEach { call ->
            val failure = assertThrows(SSLHandshakeException::class.java) { call() }
            assertEquals("Empty certificate chain — connection rejected", failure.message)
        }

        assertEquals(0, approvals.size)
        assertEquals(before, pins)
        assertEquals(0, pinReads)
    }

    @Test
    fun `the socket and engine overloads pass their peer host to approval`() {
        val manager = manager()
        val socket = StubSocket("socket.example")
        val engine = SSLContext.getDefault().createSSLEngine("engine.example", 443)

        assertThrows(SSLHandshakeException::class.java) {
            manager.checkServerTrusted(arrayOf(certA), "RSA", socket)
        }
        assertThrows(SSLHandshakeException::class.java) {
            manager.checkServerTrusted(arrayOf(certA), "RSA", engine)
        }

        assertEquals(listOf("socket.example", "engine.example"), approvals.map { it.host })
    }

    @Test
    fun `a known pin passes`() {
        pins["celeste-test.invalid"] = sha256Fingerprint(certA)

        manager().verify(arrayOf(certA), "celeste-test.invalid")

        assertTrue("a matching pin must not ask for approval: $approvals", approvals.isEmpty())
    }

    @Test
    fun `an unknown host is refused and offered for approval once`() {
        val failure = assertThrows(SSLHandshakeException::class.java) {
            manager().verify(arrayOf(certA), "unknown.example")
        }

        assertTrue("expected the new-cert message, got ${failure.message}", failure.message!!.contains("New self-signed cert"))
        val request = approvals.single()
        assertEquals("unknown.example", request.host)
        assertEquals(sha256Fingerprint(certA), request.fingerprint)
    }

    @Test
    fun `a changed certificate is refused without overwriting the stored pin`() {
        val stored = sha256Fingerprint(certA)
        pins["celeste-test.invalid"] = stored

        val failure = assertThrows(SSLHandshakeException::class.java) {
            manager().verify(arrayOf(certB), "celeste-test.invalid")
        }

        assertTrue("expected the changed-cert message, got ${failure.message}", failure.message!!.contains("Cert changed!"))
        approvals.single()
        assertEquals(stored, pins["celeste-test.invalid"])
    }

    private fun loadCertificate(name: String): X509Certificate =
        javaClass.getResourceAsStream("/tls/$name")!!.use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
        }

    /**
     * Supplies a host name through the socket overload without a handshake. The session comes from
     * a real SSLEngine created for [host], so `peerHost` is the value a real socket would report.
     */
    private class StubSocket(host: String) : SSLSocket() {
        private val session: SSLSession = java.lang.reflect.Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getPeerHost" -> host
                "getPeerPort" -> 443
                "isValid" -> true
                "getPacketBufferSize", "getApplicationBufferSize" -> 16709
                "getValueNames" -> emptyArray<String>()
                "toString" -> "StubSession($host)"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as SSLSession

        override fun getHandshakeSession(): SSLSession = session
        override fun getSession(): SSLSession = session
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<String>?) {}
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<String>?) {}
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun startHandshake() {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation(): Boolean = true
    }
}
