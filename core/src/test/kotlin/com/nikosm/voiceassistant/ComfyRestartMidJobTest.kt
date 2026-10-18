package com.nikosm.voiceassistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M7's "ComfyUI restarted mid-job" error path.
 *
 * ComfyUI answers `/prompt` and then keeps running while the image is generated, so the interesting
 * failure is the server going away between the submit and the history poll. The loop's contract
 * (AssistantServiceComfy.kt) is explicit: a transport failure is a blip, but four in a row inside
 * eight seconds mean the address is gone, and the run must fail with "stopped responding" at once
 * rather than polling a dead socket for the ten-minute budget.
 *
 * The server here is real HTTP on loopback — not a queue of canned bodies — because the thing under
 * test is what happens when the CONNECTION dies, which a scripted 500 cannot express. Its delays are
 * virtual under `runTest`, so the four-failure streak drains without waiting wall-clock time; the
 * refusals themselves are real and immediate.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComfyRestartMidJobTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv()
    private val settings = SettingsManager(env, InMemoryStore(), NoImageCodec)
    private var server: HttpServer? = null
    private var service: AssistantService? = null

    @Before
    fun setUp() {
        // The service builds its scope on Dispatchers.Main; the test dispatcher must be installed
        // before the constructor runs.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        server?.stop(0)
        service?.let { runCatching { it.shutdown() } }
        drainServiceScopes(listOfNotNull(service), mainDispatcher.scheduler)
        Dispatchers.resetMain()
    }

    @Test
    fun `a ComfyUI that dies mid-job fails as unreachable instead of waiting out the budget`() = runTest {
        val historyCalls = AtomicInteger()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = http
        http.createContext("/prompt") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = """{"prompt_id":"job-1","number":1,"node_errors":{}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.createContext("/history") { exchange ->
            historyCalls.incrementAndGet()
            val body = "{}" // pending: the server is up, the job is still running
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()

        val url = "http://127.0.0.1:${http.address.port}"
        val config = ServerConfig(name = "Comfy", url = url, comfyWorkflow = ComfyFixtures.SAMPLE_WORKFLOW)
        val svc = AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        )
        service = svc

        // The throwable is captured INSIDE the async: a failing async cancels its parent scope, and
        // `runTest` reports that as the test failing even when the test later awaits the exception.
        val job = async {
            runCatching { svc.performComfyImageGeneration("a cat", config, System.currentTimeMillis()) }
                .exceptionOrNull()
        }

        // Wait for the first history poll, which proves the submit and one poll happened while the
        // server was alive. Real time, because the request itself is real IO.
        val deadline = System.currentTimeMillis() + 10_000
        while (historyCalls.get() == 0 && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(5)
        }
        assertTrue("the job reached its first poll", historyCalls.get() > 0)

        // The restart: the socket is gone. Every later poll is a connection refusal.
        http.stop(0)

        val error = job.await()

        assertTrue("the run must fail rather than hang", error is Exception)
        assertTrue(
            "and name the server as gone, not a job failure or a ten-minute timeout: ${error?.message}",
            error!!.message.orEmpty().contains("stopped responding"),
        )
    }
}
