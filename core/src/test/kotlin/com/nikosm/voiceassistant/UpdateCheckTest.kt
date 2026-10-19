package com.nikosm.voiceassistant

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual update check: the version comparison, the answer's parser, and the three HTTP
 * outcomes the About window distinguishes — all against a loopback server, so no test touches
 * GitHub.
 */
class UpdateCheckTest {

    @Test
    fun `versions compare numerically, with a leading v ignored`() {
        assertTrue(isNewerVersion("0.3.0", "0.2.0"))
        assertTrue(isNewerVersion("v0.3.0", "0.2.0"))
        assertTrue("numeric, not lexicographic", isNewerVersion("0.10.0", "0.2.0"))
        assertTrue("a missing component is zero", isNewerVersion("0.2.1", "0.2"))
        assertFalse(isNewerVersion("0.2.0", "0.2.0"))
        assertFalse(isNewerVersion("v0.2.0", "0.2.0"))
        assertFalse(isNewerVersion("0.1.0", "0.2.0"))
        assertFalse("an unparseable tag cannot claim an update", isNewerVersion("latest", "0.2.0"))
    }

    @Test
    fun `an answer names an available or current release`() {
        assertEquals(
            UpdateCheckResult.Available("v0.3.0"),
            parseLatestRelease("""{"tag_name":"v0.3.0"}""", "0.2.0"),
        )
        assertEquals(
            UpdateCheckResult.UpToDate("0.2.0"),
            parseLatestRelease("""{"tag_name":"0.2.0"}""", "0.2.0"),
        )
    }

    @Test
    fun `an answer without a tag is a failure, not an update`() {
        assertTrue(parseLatestRelease("""{"name":"no tag"}""", "0.2.0") is UpdateCheckResult.Failed)
        assertTrue(parseLatestRelease("not json", "0.2.0") is UpdateCheckResult.Failed)
    }

    @Test
    fun `a loopback 200 is parsed`() = runBlocking {
        withServer(200, """{"tag_name":"v0.3.0"}""") { url ->
            assertEquals(UpdateCheckResult.Available("v0.3.0"), checkLatestRelease(url, "0.2.0"))
        }
    }

    @Test
    fun `404 is no release and 403 is the rate limit`() = runBlocking {
        withServer(404, "{}") { url ->
            assertEquals(UpdateCheckResult.NoRelease, checkLatestRelease(url, "0.2.0"))
        }
        withServer(403, "{}") { url ->
            assertEquals(UpdateCheckResult.RateLimited, checkLatestRelease(url, "0.2.0"))
        }
    }

    @Test
    fun `another status is a failure with the code`() = runBlocking {
        withServer(500, "{}") { url ->
            assertTrue(checkLatestRelease(url, "0.2.0") is UpdateCheckResult.Failed)
        }
    }

    private suspend fun withServer(code: Int, body: String, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/latest") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/latest")
        } finally {
            server.stop(0)
        }
    }
}
