package com.nikosm.voiceassistant

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The service-level model catalog cache, driven against a loopback Ollama stub (ported from
 * upstream's instrumented test in M7).
 *
 * These are the four states [ModelCatalogState] exists for, plus the two behaviours that
 * are easy to get wrong and are therefore pinned here:
 *
 *  - a failed REFRESH keeps the last good list, marked stale, instead of emptying it;
 *  - a failed /api/show for ONE model leaves that model on its tags values while every
 *    other model still resolves.
 *
 * The port change is only the harness: a fresh service over an in-memory store per test (the
 * cache lives on the service), and Main pumped by the polling helper because the fetch is
 * launched on the service scope.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelCatalogCacheTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val mainDispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()

    private lateinit var service: AssistantService

    // Two models: `good` resolves from /api/show, `bad` has no show body so the stub
    // answers 404 for it alone.
    private val tagsJson = """
        {"models":[
          {"name":"good:8b","size":4294967296,
           "details":{"parameter_size":"8.0B","quantization_level":"Q4_K_M"},
           "capabilities":["completion","tools"]},
          {"name":"bad:1b","size":1073741824,
           "details":{"parameter_size":"1.0B","quantization_level":"Q8_0"},
           "capabilities":["completion"]}
        ]}
    """.trimIndent()

    private val goodShow = """
        {"capabilities":["completion","vision","tools"],
         "details":{"parameter_size":"8.0B","quantization_level":"Q4_K_M"},
         "model_info":{"llama.context_length":131072}}
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        service = AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        ).also { services += it }
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        services.clear()
        Dispatchers.resetMain()
    }

    private fun serverFor(stub: ModelCatalogStub) = ServerConfig("CatalogProbe", stub.url)

    /** Poll a real condition on the live cache; no fixed sleeps. */
    private fun awaitCatalog(
        url: String,
        timeoutMs: Long = 20_000,
        predicate: (ModelCatalogState) -> Boolean
    ): ModelCatalogState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            val state = service.modelCatalogFor(url)
            if (predicate(state)) return state
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        return service.modelCatalogFor(url)
    }

    @Test
    fun `an unfetched server reads as NotLoaded`() {
        assertEquals(
            ModelCatalogState.NotLoaded,
            service.modelCatalogFor("http://127.0.0.1:1/never-touched")
        )
    }

    @Test
    fun `a cold fetch goes NotLoaded to Loading to Loaded`() {
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            assertEquals(ModelCatalogState.NotLoaded, service.modelCatalogFor(stub.url))

            service.fetchModelCatalog(server)

            val loaded = awaitCatalog(stub.url) { it is ModelCatalogState.Loaded }
            assertTrue(
                "expected Loaded, was ${service.modelCatalogFor(stub.url)}",
                loaded is ModelCatalogState.Loaded
            )
            loaded as ModelCatalogState.Loaded
            assertEquals(listOf("good:8b", "bad:1b"), loaded.entries.map { it.name })
            assertTrue("a fresh load is not stale", !loaded.stale)
            assertNull(loaded.degradedReason)
        }
    }

    @Test
    fun `show is called for every model not lazily`() {
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            }
            val shown = stub.requests.filter { it.path.endsWith("/api/show") }
                .mapNotNull { Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(it.body)?.groupValues?.get(1) }
            assertEquals(
                "every model must be enriched, not just the visible ones",
                listOf("bad:1b", "good:8b"),
                shown.sorted()
            )
        }
    }

    @Test
    fun `show wins the merge and size survives from tags`() {
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            // The tags-level publish is ALSO a Loaded, with everything PENDING. Waiting
            // only for Loaded would race the enrichment.
            val loaded = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            } as ModelCatalogState.Loaded
            val good = loaded.entries.first { it.name == "good:8b" }
            // tags said [completion, tools]; show adds vision.
            assertTrue(ModelCapability.VISION in good.capabilities.orEmpty())
            // context length exists only in show.
            assertEquals(131072L, good.contextLength)
            // size exists only in tags and must not be erased by the merge.
            assertEquals(4294967296L, good.sizeBytes)
        }
    }

    @Test
    fun `a failed show for one model leaves only that model on its tags values`() {
        // `good:8b` has a show body; `bad:1b` does not, so the stub 404s it alone.
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val loaded = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            } as ModelCatalogState.Loaded

            assertEquals(ModelShowState.RESOLVED, loaded.showStates["good:8b"])
            assertEquals("the one that failed must say so", ModelShowState.FAILED, loaded.showStates["bad:1b"])

            val bad = loaded.entries.first { it.name == "bad:1b" }
            assertEquals(
                "a failed model keeps its tags capabilities",
                setOf(ModelCapability.COMPLETION),
                bad.capabilities
            )
            assertNull("and gains no context length from the failed show", bad.contextLength)
            assertEquals("but keeps its tags size", 1073741824L, bad.sizeBytes)

            assertEquals(
                "one model's failure must not empty the list",
                2,
                loaded.entries.size
            )
        }
    }

    @Test
    fun `a cold fetch against a failing server yields Failed`() {
        ModelCatalogStub(tagsJson, failTags = true).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val state = awaitCatalog(stub.url) { it is ModelCatalogState.Failed }
            assertTrue("expected Failed, was $state", state is ModelCatalogState.Failed)
        }
    }

    @Test
    fun `a failed REFRESH keeps the last good list marked stale`() {
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            service.fetchModelCatalog(server)
            // Wait for the enrichment to finish, not just for the tags-level publish, so
            // the refresh below really is a refresh of a settled list.
            val first = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            } as ModelCatalogState.Loaded
            assertTrue("a first good load is not stale", !first.stale)

            // The server goes away, then the user hits refresh.
            stub.failTagsNow = true
            service.fetchModelCatalog(server, force = true)

            val after = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.stale
            }
            assertTrue(
                "a failed refresh must not replace the list with Failed",
                after is ModelCatalogState.Loaded
            )
            after as ModelCatalogState.Loaded
            assertTrue("the surviving list must be marked stale", after.stale)
            assertNotNull("and must say why", after.degradedReason)
            assertEquals(
                "the entries themselves must be the last good ones",
                listOf("good:8b", "bad:1b"),
                after.entries.map { it.name }
            )
        }
    }

    @Test
    fun `a second fetch without force does not re-request`() {
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            service.fetchModelCatalog(server)
            awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            }
            val afterFirst = stub.requests.size
            service.fetchModelCatalog(server)
            Thread.sleep(500)
            assertEquals(
                "a cached server must not be re-fetched without a user action",
                afterFirst,
                stub.requests.size
            )
        }
    }

    @Test
    fun `dropping a cache entry by URL removes it`() {
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            service.fetchModelCatalog(server)
            awaitCatalog(stub.url) { it is ModelCatalogState.Loaded }

            service.dropModelCatalog(stub.url)

            assertEquals(
                ModelCatalogState.NotLoaded,
                service.modelCatalogFor(stub.url)
            )
        }
    }

    // -------------------------------------------------- what "no models" actually means
    //
    // These pin the distinction the old code threw away: it mapped a refused connection,
    // an HTTP error and a genuinely empty server onto one empty list, then reported all
    // three as "server reported no models" -- so a user whose Ollama was down was told
    // their server simply had nothing installed on it.

    /** A port nothing is listening on: the transport-failure case. */
    private fun closedPortUrl(): String {
        val s = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = s.localPort
        s.close()
        return "http://127.0.0.1:$port"
    }

    @Test
    fun `a closed port is unreachable not an empty server`() {
        val url = closedPortUrl()
        service.fetchModelCatalog(ServerConfig("ClosedPort", url))
        val state = awaitCatalog(url) { it is ModelCatalogState.Failed }
        assertTrue("expected Failed, was $state", state is ModelCatalogState.Failed)
        assertEquals(
            "a refused connection must not be reported as an empty server",
            UNREACHABLE_REASON,
            (state as ModelCatalogState.Failed).reason
        )
    }

    @Test
    fun `an http 500 is reported with its status and not as unreachable`() {
        ModelCatalogStub(tagsJson, failTags = true).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val state = awaitCatalog(stub.url) { it is ModelCatalogState.Failed }
            assertTrue("expected Failed, was $state", state is ModelCatalogState.Failed)
            val reason = (state as ModelCatalogState.Failed).reason
            assertEquals("the status must survive into the wording", httpErrorReason(500), reason)
            assertTrue("the wording must name the status: $reason", reason.contains("500"))
            assertTrue(
                "a server that answers 500 is not unreachable: $reason",
                !reason.contains("reach")
            )
            assertTrue(
                "the fallback endpoint must still be consulted",
                stub.requests.any { it.path.endsWith("/v1/models") }
            )
        }
    }

    @Test
    fun `a successful empty list is an answer and is never stale`() {
        ModelCatalogStub("""{"models":[]}""").use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val state = awaitCatalog(stub.url) { it is ModelCatalogState.Loaded }
            assertTrue("expected Loaded, was $state", state is ModelCatalogState.Loaded)
            state as ModelCatalogState.Loaded
            assertTrue("an empty answer is not stale: it is the truth", !state.stale)
            assertNull("and it carries no failure reason", state.degradedReason)
            assertTrue("the list really is empty", state.entries.isEmpty())
            // The evidence for treating a successful empty /api/tags as final: the second
            // endpoint is never consulted to overrule a first one that plainly worked.
            assertTrue(
                "a 200 on /api/tags must not fall through to /v1/models",
                stub.requests.none { it.path.endsWith("/v1/models") }
            )
        }
    }

    @Test
    fun `an empty answer over a good list empties it rather than going stale`() {
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            service.fetchModelCatalog(server)
            val first = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            } as ModelCatalogState.Loaded
            assertEquals(2, first.entries.size)

            // Same server, same URL, now reporting zero models: the user deleted them all.
            stub.emptyTagsNow = true
            service.fetchModelCatalog(server, force = true)

            val after = awaitCatalog(stub.url) { it is ModelCatalogState.Loaded && it.entries.isEmpty() }
            after as ModelCatalogState.Loaded
            assertTrue(
                "an empty answer is not a failure, so it must not be marked stale",
                !after.stale
            )
            assertNull("nor carry a failure reason", after.degradedReason)
        }
    }

    @Test
    fun `the v1 models fallback still serves a gateway with no api tags`() {
        // 404 on /api/tags, models on /v1/models: the case the fallback exists for.
        ModelCatalogStub(
            tagsJson,
            tagsStatus = 404,
            openAiModelsBody = """{"data":[{"id":"gw-model-a"},{"id":"gw-model-b"}]}"""
        ).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val state = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            }
            assertTrue("expected Loaded, was $state", state is ModelCatalogState.Loaded)
            assertEquals(
                listOf("gw-model-a", "gw-model-b"),
                (state as ModelCatalogState.Loaded).entries.map { it.name }
            )
        }
    }

    @Test
    fun `a failed refresh after a gateway load keeps the list and says unreachable`() {
        ModelCatalogStub(
            tagsJson,
            tagsStatus = 404,
            openAiModelsBody = """{"data":[{"id":"gw-model-a"}]}"""
        ).use { stub ->
            val server = serverFor(stub)
            service.fetchModelCatalog(server)
            awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            }
            // Now BOTH endpoints fail at transport level: the stub stops answering.
            stub.close()
            service.fetchModelCatalog(server, force = true)

            val after = awaitCatalog(stub.url) { it is ModelCatalogState.Loaded && it.stale }
            after as ModelCatalogState.Loaded
            assertEquals(UNREACHABLE_REASON, after.degradedReason)
            assertEquals(listOf("gw-model-a"), after.entries.map { it.name })
        }
    }

    @Test
    fun `a 200 that is not a model list never empties a good list`() {
        // A 2xx is not evidence of a good answer. Each of these is a server that answered
        // with something that is not a model list, and each must keep the previous list
        // rather than replacing it with an empty one -- which is what the old code did,
        // because all three parsed to "no models".
        val bodies = mapOf(
            "an html login page" to "<html><body>Sign in to continue</body></html>",
            "truncated json" to """{"models":[{"name":"half:1b",""",
            "an object with no models key" to "{}"
        )
        for ((what, body) in bodies) {
            ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
                val server = serverFor(stub)
                service.fetchModelCatalog(server)
                val first = awaitCatalog(stub.url) {
                    it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
                } as ModelCatalogState.Loaded
                assertEquals("precondition for $what", 2, first.entries.size)

                // Same server, same URL, now answering 200 with something else entirely.
                stub.tagsBodyOverride = body
                service.fetchModelCatalog(server, force = true)

                val after = awaitCatalog(stub.url) { it is ModelCatalogState.Loaded && it.stale }
                assertTrue("$what must not replace the list with Failed", after is ModelCatalogState.Loaded)
                after as ModelCatalogState.Loaded
                assertEquals(
                    "$what must be reported as an unexpected response",
                    UNEXPECTED_RESPONSE_REASON,
                    after.degradedReason
                )
                assertEquals(
                    "$what must keep the last good list",
                    listOf("good:8b", "bad:1b"),
                    after.entries.map { it.name }
                )
            }
        }
    }

    @Test
    fun `a 200 that is not a model list is never reported as an empty server`() {
        // Cold, with nothing to fall back on: this must still not read as "No models on this
        // server", which is what an empty Loaded means and what the user would act on.
        ModelCatalogStub("""{"not_models":true}""").use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val state = awaitCatalog(stub.url) { it is ModelCatalogState.Failed }
            assertTrue("expected Failed, was $state", state is ModelCatalogState.Failed)
            assertEquals(
                UNEXPECTED_RESPONSE_REASON,
                (state as ModelCatalogState.Failed).reason
            )
            assertTrue(
                "the wording must not claim the server reported no models",
                !state.reason.contains("no models")
            )
        }
    }

    @Test
    fun `a malformed tags still lets the gateway endpoint answer`() {
        // /api/tags serves HTML, /v1/models speaks the OpenAI shape: the fallback must
        // still work, because Malformed falls through where an empty success does not.
        ModelCatalogStub(
            "<html>not ollama</html>",
            openAiModelsBody = """{"data":[{"id":"gw-only:1b"}]}"""
        ).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val state = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            }
            assertTrue("expected Loaded, was $state", state is ModelCatalogState.Loaded)
            assertEquals(
                listOf("gw-only:1b"),
                (state as ModelCatalogState.Loaded).entries.map { it.name }
            )
        }
    }
}
