package com.nikosm.voiceassistant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The on-demand health checks, against a loopback server (PLAN.md §10/M12).
 *
 * The cases worth pinning are the distinctions the page draws: Ollama's version is read, ComfyUI's
 * queue is summarised, a gateway that answers 404 or 401 is **reachable**, and a refused port is a
 * failure with a sentence — not a crash and not a false "OK".
 */
class ServerHealthTest {

    private lateinit var http: HttpServer

    @Before
    fun setUp() {
        // 127.0.0.1 explicitly, so the server and the URLs below agree on the address family.
        http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.createContext("/api/version") { respond(it, """{"version":"0.9.9"}""") }
        http.createContext("/queue") { respond(it, """{"queue_running":[1,2],"queue_pending":[{}]}""") }
        http.createContext("/sdapi/v1/options") { respond(it, "{}") }
        http.start()
    }

    @After
    fun tearDown() {
        http.stop(0)
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun base(): String = "http://127.0.0.1:${http.address.port}"

    /** The tests' own client; the app passes the service's pinned `healthClient`. */
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Test
    fun `an Ollama check reads the version`() = runBlocking {
        val result = checkHealth(HealthTarget(HealthKind.OLLAMA, "O", base(), null), client)

        assertTrue("expected OK, got $result", result is HealthResult.Ok)
        result as HealthResult.Ok
        assertTrue(result.detail, result.detail.contains("0.9.9"))
        assertTrue("latency should be recorded", result.latencyMs >= 0)
    }

    @Test
    fun `a ComfyUI queue is summarised`() = runBlocking {
        val config = ServerConfig("C", base(), backendType = ImageGenBackend.COMFYUI)

        val result = checkHealth(HealthTarget(HealthKind.IMAGE, "C", base(), config), client) as HealthResult.Ok

        assertEquals("2 running, 1 pending", result.detail)
    }

    @Test
    fun `an A1111 answer is enough`() = runBlocking {
        val config = ServerConfig("A", base(), backendType = ImageGenBackend.A1111)

        val result = checkHealth(HealthTarget(HealthKind.IMAGE, "A", base(), config), client) as HealthResult.Ok

        assertEquals("HTTP 200", result.detail)
    }

    @Test
    fun `a gateway that answers 404 is still reachable`() = runBlocking {
        val result = checkHealth(HealthTarget(HealthKind.GATEWAY, "G", base(), null), client)

        assertTrue("a 404 is an answer, so the host is up: $result", result is HealthResult.Ok)
        assertEquals("HTTP 404", (result as HealthResult.Ok).detail)
    }

    @Test
    fun `a closed port is a failure with a sentence`() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }

        val result = checkHealth(HealthTarget(HealthKind.OLLAMA, "O", "http://127.0.0.1:$freePort", null), client)

        assertTrue(result is HealthResult.Failed)
        assertFalse((result as HealthResult.Failed).reason.isBlank())
    }

    @Test
    fun `targets come from the three lists and duplicates are collapsed within a kind`() {
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        manager.saveOllamaBases(
            listOf(ServerConfig("O", "http://o:11434"), ServerConfig("O again", "http://o:11434/")),
        )
        manager.saveServerBases(listOf(ServerConfig("G", "http://g:8880")))
        manager.saveImageGenBases(
            listOf(ServerConfig("C", "http://c:8188", backendType = ImageGenBackend.COMFYUI)),
        )

        val targets = healthTargetsFor(manager)

        assertEquals(3, targets.size)
        assertEquals(listOf(HealthKind.OLLAMA, HealthKind.GATEWAY, HealthKind.IMAGE), targets.map { it.kind })
        assertEquals("the first of two entries with the same URL wins", "O", targets.first().name)
    }
}
