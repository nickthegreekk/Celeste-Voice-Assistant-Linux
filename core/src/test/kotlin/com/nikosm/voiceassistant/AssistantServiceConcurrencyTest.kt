package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The concurrency bookkeeping around cloud model fetches (ported from upstream's instrumented
 * test in M7).
 *
 * Two bug classes that already shipped once and were only protected by code comments
 * afterwards:
 *
 *  1. **Live-map clobbering.** A fetch used to snapshot `_serverStatus` when its coroutine
 *     started and write that snapshot back wholesale when it finished, so the last provider to
 *     finish erased every sibling's entry. The fix is a per-entry read-modify-write of the LIVE
 *     map on Main; these tests run overlapping fetches with *different* outcomes and require
 *     every entry to survive.
 *  2. **A premature loading clear.** The in-flight counter was decremented twice on the Cline
 *     path, so a fetch that was still running saw `_isLoadingModels` flip back to false and its
 *     spinner vanish mid-flight.
 *
 * The port change is the harness: no Android service binding and no real Looper. Main is a
 * `StandardTestDispatcher`, pumped explicitly — which is also what lets the health-check test
 * park "Main" structurally instead of racing it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistantServiceConcurrencyTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val mainDispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()
    private val stubs = mutableListOf<StubCloudServer>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        services.clear()
        stubs.forEach { it.close() }
        stubs.clear()
        Dispatchers.resetMain()
    }

    private fun newService(): AssistantService = AssistantService(
        appEnv = env,
        audioPlayback = SilentAudioPlayback,
        audioDevices = NoAudioDevices,
        tts = NoTextToSpeech,
        audioCapture = NoAudioCapture,
        fileDialogs = NoFileDialogs,
        settingsManager = settings,
    ).also { services += it }

    private fun stub(statusCode: Int, body: String, holdResponse: Boolean = false): StubCloudServer =
        StubCloudServer(statusCode, body, holdResponse).also { stubs.add(it) }

    /** A custom ("C") provider pointing at a stub — no API key needed on that path. */
    private fun provider(name: String, baseUrl: String): CloudApiSetting =
        CloudApiSetting(name, baseUrl, "", "C", Color(0xFF808080), isEditableUrl = true)

    /** Pump Main so the async fetch's `withContext(Main)` write can land, then check. */
    private fun waitUntil(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        mainDispatcher.scheduler.runCurrent()
        assertTrue(
            "timed out after ${timeoutMs}ms waiting for $description " +
                "(serverStatus=${services.first().serverStatus.value}, " +
                "cloudProviderModels=${services.first().fetchedCloudModels.value.keys})",
            condition()
        )
    }

    private fun pumpMainFor(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            Thread.sleep(10)
        }
        mainDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `overlapping provider fetches keep every entry and the loading flag`() {
        val service = newService()
        val slowFailure = stub(500, """{"error":"probe-a"}""", holdResponse = true)
        val fastFailure = stub(503, """{"error":"probe-b"}""")
        val success = stub(200, """{"data":[{"id":"probe-c-model"}]}""")

        val slowApi = provider("ConcurrencyProbeA", slowFailure.baseUrl)
        val fastApi = provider("ConcurrencyProbeB", fastFailure.baseUrl)
        val okApi = provider("ConcurrencyProbeC", success.baseUrl)

        service.fetchCloudModels(slowApi)
        assertTrue(
            "probe A's request never reached its stub",
            slowFailure.requestArrived.await(20, TimeUnit.SECONDS)
        )

        service.fetchCloudModels(fastApi)
        service.fetchCloudModels(okApi)

        waitUntil("probe B's failure and probe C's models to be applied") {
            service.serverStatus.value[fastApi.name] == "Fetch failed: 503" &&
                service.fetchedCloudModels.value.containsKey(okApi.name)
        }

        assertFalse(
            "probe A recorded a status while its response was still held",
            service.serverStatus.value.containsKey(slowApi.name)
        )
        assertTrue(
            "isLoadingModels was cleared while probe A's fetch was still in flight — the " +
                "in-flight count was decremented more than once",
            service.isLoadingModels.value
        )

        slowFailure.releaseResponse()
        waitUntil("probe A's failure to be applied") {
            service.serverStatus.value[slowApi.name] == "Fetch failed: 500"
        }

        val status = service.serverStatus.value
        assertEquals(
            "probe A's completion clobbered probe B's entry with a stale map: $status",
            "Fetch failed: 503",
            status[fastApi.name]
        )
        assertEquals("probe A's own entry: $status", "Fetch failed: 500", status[slowApi.name])
        assertFalse(
            "a successful fetch must clear only its own status entry: $status",
            status.containsKey(okApi.name)
        )
        assertEquals(
            "probe C's successful model list was clobbered by a sibling: " +
                "${service.fetchedCloudModels.value}",
            listOf("[${okApi.name}] probe-c-model"),
            service.fetchedCloudModels.value[okApi.name]
        )

        waitUntil("the in-flight count to return to zero") { !service.isLoadingModels.value }
    }

    @Test
    fun `cline static list fetch leaves the in-flight counter to the other fetch`() {
        val service = newService()
        val slow = stub(200, """{"data":[{"id":"probe-d-model"}]}""", holdResponse = true)
        val slowApi = provider("ConcurrencyProbeD", slow.baseUrl)
        val clineApi = CloudApiSetting(
            "ConcurrencyProbeCline",
            "https://api.cline.bot/api/v1",
            "",
            "CL",
            Color(0xFF6366F1)
        )

        // A stale error from an earlier attempt: refreshing the list must clear that one
        // entry — and only that one.
        service._serverStatus.value = service._serverStatus.value + (clineApi.name to "failed: 500")

        service.fetchCloudModels(slowApi)
        assertTrue(
            "probe D's request never reached its stub",
            slow.requestArrived.await(20, TimeUnit.SECONDS)
        )
        assertTrue("probe D's fetch should set the loading flag", service.isLoadingModels.value)

        service.fetchCloudModels(clineApi)
        waitUntil("the Cline static list to be applied", 10_000) {
            service.fetchedCloudModels.value.containsKey(clineApi.name)
        }

        // A double decrement leaves the flag *clear*, not merely cleared for an instant, so
        // polling for a while cannot miss it.
        pumpMainFor(2_000)
        assertTrue(
            "the CL early return cleared isLoadingModels while probe D was still in flight — " +
                "the single decrement must stay in the finally",
            service.isLoadingModels.value
        )

        assertNull(
            "the CL branch must clear only its own stale status entry: " +
                "${service.serverStatus.value}",
            service.serverStatus.value[clineApi.name]
        )
        assertEquals(
            "the CL branch must publish the static Cline list for its own provider",
            CLINE_BOT_MODELS.map { "[${clineApi.name}] $it" },
            service.fetchedCloudModels.value[clineApi.name]
        )

        slow.releaseResponse()
        waitUntil("probe D's successful model list") {
            service.fetchedCloudModels.value.containsKey(slowApi.name)
        }
        waitUntil("the in-flight count to return to zero") { !service.isLoadingModels.value }
    }

    /**
     * The 30s health sweep and forceCheckHealth run on Dispatchers.IO, but they write
     * `_serverStatus` like any other writer and must apply their result on Main. This asserts
     * that structurally: Main is not pumped, the stub answers, and the entry must NOT be
     * visible until Main is pumped. An off-Main writer has nothing to wait for, so its entry
     * would appear while Main is still parked.
     */
    @Test
    fun `health check status write is applied on Main and keeps siblings`() {
        val service = newService()
        val health = stub(200, """{"models":[]}""")
        val target = ServerConfig(name = "ConcurrencyProbeHealth", url = health.baseUrl)
        val sibling = "ConcurrencyProbeSibling"
        service._serverStatus.value = service._serverStatus.value + (sibling to "failed: 503")

        service.forceCheckHealth(target, isGateway = false)
        assertTrue(
            "the health check never reached its stub",
            health.requestArrived.await(20, TimeUnit.SECONDS)
        )
        assertTrue(
            "the stub never answered the health check",
            health.responseSent.await(20, TimeUnit.SECONDS)
        )
        // The response is in hand and the check is waiting on its Main hop, so with Main not
        // pumped its entry cannot be published yet.
        Thread.sleep(500)
        assertFalse(
            "checkServerHealth wrote _serverStatus off Main (its entry appeared while Main " +
                "was parked): ${service.serverStatus.value}",
            service.serverStatus.value.containsKey(target.url)
        )

        waitUntil("the health check's status to be applied on Main", 10_000) {
            service.serverStatus.value[target.url] == "Online"
        }
        assertEquals(
            "the health check clobbered a sibling entry: ${service.serverStatus.value}",
            "failed: 503",
            service.serverStatus.value[sibling]
        )
    }
}

/**
 * A loopback-only stand-in for one provider's model endpoint, scripted with a single reply so a
 * test can give two providers different outcomes and tell whose write landed where.
 *
 * `holdResponse` parks a request inside the server (after the request head is read, before
 * anything is written) until [releaseResponse] is called: that is what makes the overlaps in
 * these tests engineered instead of timing-dependent.
 */
private class StubCloudServer(
    private val statusCode: Int,
    private val responseBody: String,
    private val holdResponse: Boolean = false
) {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** e.g. `http://127.0.0.1:41234` — a custom provider appends `/models` itself. */
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    /** Fires once a request head has been read: the fetch is genuinely in flight. */
    val requestArrived = CountDownLatch(1)

    /** Fires once the scripted reply has been written and the socket closed. */
    val responseSent = CountDownLatch(1)

    private val released = CountDownLatch(1)

    init {
        val port = server.localPort
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break // close() tore the listener down
                }
                Thread({ serve(client) }, "stub-cloud-$port").apply { isDaemon = true }.start()
            }
        }, "stub-cloud-accept-$port").apply { isDaemon = true }.start()
    }

    fun releaseResponse() = released.countDown()

    fun close() {
        released.countDown() // unblock any held worker so the test cannot leak a thread
        runCatching { server.close() }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                readRequestHead(socket.getInputStream())
                requestArrived.countDown()
                if (holdResponse) released.await(60, TimeUnit.SECONDS)
                writeResponse(socket.getOutputStream())
            }
        } catch (e: IOException) {
            // The test closed the socket mid-flight; nothing to report.
        } finally {
            responseSent.countDown()
        }
    }

    private fun readRequestHead(input: InputStream) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == -1) return
            head.append(next.toChar())
        }
    }

    private fun writeResponse(output: OutputStream) {
        val body = responseBody.toByteArray(Charsets.UTF_8)
        val reason = if (statusCode == 200) "OK" else "Stub Error"
        val head = "HTTP/1.1 $statusCode $reason\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }
}
