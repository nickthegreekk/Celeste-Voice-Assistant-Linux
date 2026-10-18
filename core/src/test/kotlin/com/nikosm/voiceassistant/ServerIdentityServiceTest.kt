package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Server identity through the REAL service (ported from upstream's instrumented test in M7):
 * unique names at the chokepoint, and the `"[Server] model"` rewrite a rename performs.
 *
 * Every assertion reads the persisted value back through `SettingsManager` rather than the
 * service's StateFlow, because the whole point is what survives to disk: an in-memory flow
 * would pass even if the next save overwrote the rewrite, which is exactly the failure mode
 * being guarded against. The port change is only the harness — one service over a fresh
 * in-memory store per test instead of binding the device's.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerIdentityServiceTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    private val mainUrl = "http://127.0.0.1:1/step2-main"
    private val workUrl = "http://127.0.0.1:1/step2-work"

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val service = newService()
        service.addOllamaBase("Main", mainUrl)
        service.addOllamaBase("Work", workUrl)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
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

    private fun service(): AssistantService = services.first()

    private fun storedPersona(name: String): Persona? =
        settings.getPersonas().orEmpty().firstOrNull { it.name == name }

    private fun storedFavorites(): List<String> = settings.getFavoriteModels().orEmpty()

    /** Wait for the debounced `saveSettings()` to reach disk. */
    private fun awaitStoredPersona(name: String, predicate: (Persona?) -> Boolean): Persona? {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val current = storedPersona(name)
            if (current != null && predicate(current)) return current
            Thread.sleep(25)
        }
        return storedPersona(name)
    }

    private fun awaitStoredFavorites(predicate: (List<String>) -> Boolean): List<String> {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val current = storedFavorites()
            if (predicate(current)) return current
            Thread.sleep(25)
        }
        return storedFavorites()
    }

    private fun probe(name: String, model: String, spontaneous: Boolean = false) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = model,
        systemPrompt = "probe",
        isCloud = false,
        spontaneousEnabled = spontaneous
    )

    // ------------------------------------------------------------ unique names

    @Test
    fun `a duplicate ollama name is refused at the chokepoint`() {
        val before = service().ollamaBaseUrls.value.size
        service().addOllamaBase("Main", "http://127.0.0.1:1/step2-other")

        val names = service().ollamaBaseUrls.value.map { it.name }
        assertEquals("the list must not grow", before, names.size)
        assertEquals(
            "and the duplicate must not be stashed under a different name",
            listOf("Main", "Work"),
            names.sorted()
        )
    }

    @Test
    fun `a duplicate name differing only in case is refused`() {
        val before = service().ollamaBaseUrls.value.size
        service().addOllamaBase("main", "http://127.0.0.1:1/step2-case")
        assertEquals(before, service().ollamaBaseUrls.value.size)
    }

    @Test
    fun `editing a server onto an existing name is refused`() {
        val work = service().ollamaBaseUrls.value.first { it.name == "Work" }
        service().updateOllamaBase(work, "Main", "http://127.0.0.1:1/step2-hijack")
        assertTrue(
            "the rename must not have been applied",
            service().ollamaBaseUrls.value.any { it.name == "Work" && it.url == workUrl }
        )
    }

    @Test
    fun `saving a server without changing its name is allowed`() {
        val main = service().ollamaBaseUrls.value.first { it.name == "Main" }
        service().updateOllamaBase(main, "Main", main.url)
        assertTrue(service().ollamaBaseUrls.value.any { it.name == "Main" && it.url == mainUrl })
    }

    // ------------------------------------------------------------ rename rewrite

    @Test
    fun `a rename rewrites favorites AND personas, read back from storage`() {
        service().addPersona(probe("Step2Renamed", "[Main] llama3"))
        service().toggleFavoriteModel("[Main] llama3")
        service().toggleFavoriteModel("[Work] other:1b")
        assertTrue(
            "[Main] llama3 must be a favorite on disk first",
            awaitStoredFavorites { it.contains("[Main] llama3") }.contains("[Main] llama3")
        )
        assertTrue(
            "the persona must be on disk first",
            awaitStoredPersona("Step2Renamed") { it?.model == "[Main] llama3" } != null
        )

        val main = service().ollamaBaseUrls.value.first { it.name == "Main" }
        service().updateOllamaBase(main, "Renamed", main.url)

        // Read from DISK, not the flow.
        assertEquals(
            "the persona's stored model must follow the rename",
            "[Renamed] llama3",
            awaitStoredPersona("Step2Renamed") { it?.model == "[Renamed] llama3" }?.model
        )
        val favs = awaitStoredFavorites { it.none { f -> f.startsWith("[Main]") } }
        assertTrue("the favorite must follow the rename: $favs", favs.contains("[Renamed] llama3"))
        assertFalse("the old prefix must be gone: $favs", favs.any { it.startsWith("[Main]") })
        assertTrue("another server's favorite must be untouched: $favs", favs.contains("[Work] other:1b"))
    }

    @Test
    fun `a spontaneous persona keeps its opt-in across its server's rename`() {
        service().addPersona(probe("Step2Spont", "[Main] llama3", spontaneous = true))
        assertTrue(
            "precondition: the persona is opted in on disk",
            awaitStoredPersona("Step2Spont") { it?.spontaneousEnabled == true }?.spontaneousEnabled == true
        )

        val main = service().ollamaBaseUrls.value.first { it.name == "Main" }
        service().updateOllamaBase(main, "Renamed2", main.url)

        // The guard strips spontaneousEnabled from any persona naming an UNCONFIGURED server.
        // After the rewrite the server exists under its new name, so the opt-in must remain.
        assertEquals(
            "[Renamed2] llama3",
            awaitStoredPersona("Step2Spont") { it?.model == "[Renamed2] llama3" }?.model
        )
        assertTrue(
            "the rename must not cost the user their spontaneous opt-in",
            awaitStoredPersona("Step2Spont") { it?.spontaneousEnabled == true }?.spontaneousEnabled == true
        )
    }

    @Test
    fun `an immediate persona save after the rename does not undo it`() {
        service().addPersona(probe("Step2Resave", "[Main] llama3"))
        val main = service().ollamaBaseUrls.value.first { it.name == "Main" }
        service().updateOllamaBase(main, "Renamed3", main.url)
        assertEquals(
            "[Renamed3] llama3",
            awaitStoredPersona("Step2Resave") { it?.model == "[Renamed3] llama3" }?.model
        )

        // The editor debounces and then commits a WHOLE persona rebuilt from its snapshot.
        // That save is exactly what would put the old string back if the rewrite had only
        // touched storage and not the in-memory list.
        val idx = service().personas.value.indexOfFirst { it.name == "Step2Resave" }
        assertTrue(idx >= 0)
        service().updatePersona(idx, service().personas.value[idx].copy(systemPrompt = "edited"))
        assertEquals(
            "a later persona save must not restore the old prefix",
            "[Renamed3] llama3",
            awaitStoredPersona("Step2Resave") {
                it?.model == "[Renamed3] llama3" && it.systemPrompt == "edited"
            }?.model
        )
    }

    @Test
    fun `a name that is a prefix of another survives the rename`() {
        service().addOllamaBase("Main2", "http://127.0.0.1:1/step2-main2")
        service().addPersona(probe("Step2Prefixed", "[Main2] llama3"))

        val main = service().ollamaBaseUrls.value.first { it.name == "Main" }
        service().updateOllamaBase(main, "Renamed4", main.url)

        assertEquals(
            "renaming Main must not touch the Main2 persona",
            "[Main2] llama3",
            awaitStoredPersona("Step2Prefixed") { it?.model == "[Main2] llama3" }?.model
        )
    }

    @Test
    fun `a model name containing brackets survives the rename`() {
        val tricky = "[Main] hf.co/x/Qwen[2]-7B-GGUF:q4_k_m"
        service().addPersona(probe("Step2Brackets", tricky))
        val main = service().ollamaBaseUrls.value.first { it.name == "Main" }
        service().updateOllamaBase(main, "Renamed5", main.url)
        assertEquals(
            "[Renamed5] hf.co/x/Qwen[2]-7B-GGUF:q4_k_m",
            awaitStoredPersona("Step2Brackets") {
                it?.model == "[Renamed5] hf.co/x/Qwen[2]-7B-GGUF:q4_k_m"
            }?.model
        )
    }

    // ------------------------------------------------------------------- delete

    @Test
    fun `deleting a server drops its favorites and its cache, and leaves personas alone`() {
        service().addPersona(probe("Step2Delete", "[Main] llama3"))
        service().toggleFavoriteModel("[Main] llama3")
        service().toggleFavoriteModel("[Work] other:1b")
        // Wait until BOTH are on disk. The post-delete assertion waits on "no [Main]", which is
        // trivially satisfied by an empty list, so without this precondition the test would
        // pass for the wrong reason.
        assertTrue(
            "precondition: both favorites must be persisted",
            awaitStoredFavorites { it.size == 2 }.size == 2
        )
        val main = service().ollamaBaseUrls.value.first { it.name == "Main" }
        service().fetchModelCatalog(main)

        service().removeOllamaBase(main)

        val favs = awaitStoredFavorites { f -> f.none { it.startsWith("[Main]") } }
        assertFalse("its favorite must be gone: $favs", favs.any { it.startsWith("[Main]") })
        assertTrue("the other server's favorite must survive: $favs", favs.contains("[Work] other:1b"))
        assertEquals(
            "the persona is deliberately NOT rewritten or deleted",
            "[Main] llama3",
            awaitStoredPersona("Step2Delete") { it?.model == "[Main] llama3" }?.model
        )
        assertEquals(
            "the cache entry must be dropped with the server",
            ModelCatalogState.NotLoaded,
            service().modelCatalogFor(mainUrl)
        )
    }
}
