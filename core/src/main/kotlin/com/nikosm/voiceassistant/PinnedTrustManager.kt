package com.nikosm.voiceassistant

import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Seam 14's TOFU trust decision, extracted from `buildPinnedClient` so it can be tested directly.
 *
 * A certificate is accepted when its SHA-256 fingerprint matches the pin stored for the host. A
 * host with no pin, or a pin that no longer matches, is offered to [onUntrusted] and the handshake
 * is refused — the caller's approval is what stores a new pin, never this class.
 */
internal class PinnedTrustManager(
    private val trustedCerts: () -> Map<String, String>,
    private val onUntrusted: (CertApprovalRequest) -> Unit,
) : X509ExtendedTrustManager() {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val host = (socket as? SSLSocket)?.handshakeSession?.peerHost ?: "unknown"
        verify(chain, host)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val host = engine?.peerHost ?: "unknown"
        verify(chain, host)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verify(chain, "unknown")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    internal fun verify(chain: Array<out X509Certificate>?, host: String) {
        // Fail closed. A trust manager that RETURNS without throwing tells the TLS stack that the
        // chain is trusted. An absent chain is not a certificate anyone can pin, so refuse it
        // before reading the pins and without offering it for approval.
        if (chain == null || chain.isEmpty()) {
            throw SSLHandshakeException("Empty certificate chain — connection rejected")
        }
        val cert = chain[0]
        val fingerprint = sha256Fingerprint(cert)
        val stored = trustedCerts()[host]

        if (stored == null || stored != fingerprint) {
            onUntrusted(CertApprovalRequest(host, fingerprint, cert))
            throw SSLHandshakeException(if (stored == null) "New self-signed cert" else "Cert changed!")
        }
    }
}
