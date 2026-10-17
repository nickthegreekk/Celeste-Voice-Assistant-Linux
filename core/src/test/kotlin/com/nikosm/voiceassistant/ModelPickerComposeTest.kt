package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The persona editor's Ollama model picker, composed on its own (ported from upstream's
 * instrumented test in M7; the runner is the only change).
 *
 * `OllamaModelPicker` takes its state as parameters and never collects the service, so a fixed
 * state renders identically every run. The host is pinned small and wrapped in an explicit
 * scroller, which is also how the real dropdown bounds itself.
 */
@OptIn(ExperimentalTestApi::class)
class ModelPickerComposeTest {

    private val fixedFetch: Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, FIXED_HOUR)
        set(java.util.Calendar.MINUTE, FIXED_MINUTE)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val accent = Color(0xFF4ADE80)
    private val server = ServerConfig(
        name = SERVER, url = "http://127.0.0.1:11434", username = null, password = null,
        authType = AuthType.NONE, apiKey = null
    )

    private fun entry(
        name: String,
        caps: Set<ModelCapability>?,
        params: String? = "7.5B",
        quant: String? = "Q4_K_M",
        ctx: Long? = 128_000L,
        size: Long? = 5_000_000_000L
    ) = ModelEntry(name, caps, params, quant, ctx, size)

    private val chat = setOf(ModelCapability.COMPLETION)

    private var state: ModelCatalogState = ModelCatalogState.NotLoaded
    private var health: String? = "Online"

    // Snapshot state, so a test can hand the picker its favourites AFTER it has opened — which
    // is what the real editor does, since its favourites list starts empty and is filled by a
    // flow rather than being passed in ready.
    private val favorites = mutableStateOf<List<String>>(emptyList())
    private var currentModel: String = ""
    private var refreshes = 0
    private var lastToggled: String? = null
    private var lastSelected: String? = null

    /** Upstream's @Before, run per test because each `runComposeUiTest` is its own world. */
    private fun reset() {
        state = ModelCatalogState.Loaded(
            entries = listOf(
                entry("deepseek-coder:6.7b", setOf(ModelCapability.COMPLETION, ModelCapability.TOOLS)),
                entry("gemma4:26b", setOf(ModelCapability.COMPLETION, ModelCapability.VISION), "25.2B", ctx = 262_144L),
                entry("nomic-embed-text", setOf(ModelCapability.EMBEDDING), "137M", "F16"),
                entry("llama3:latest", chat)
            ),
            showStates = listOf(
                "deepseek-coder:6.7b", "gemma4:26b", "nomic-embed-text", "llama3:latest"
            ).associateWith { ModelShowState.RESOLVED },
            fetchedAtMillis = fixedFetch,
        )
        health = "Online"
        favorites.value = emptyList()
        currentModel = ""
        refreshes = 0
        lastToggled = null
        lastSelected = null
    }

    private fun ComposeUiTest.listNode() = onNodeWithTag(HOST_TAG)

    private fun ComposeUiTest.assertShown(text: String) {
        listNode().performScrollToNode(hasText(text, substring = true))
        onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed()
    }

    private fun ComposeUiTest.render() {
        setContent {
            MaterialTheme {
                Box(Modifier.size(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    Box(
                        Modifier
                            .testTag(HOST_TAG)
                            .verticalScroll(rememberScrollState())
                    ) {
                        OllamaModelPicker(
                            server = server,
                            state = state,
                            healthStatus = health,
                            favoriteModels = favorites.value,
                            currentModel = currentModel,
                            onRefresh = { refreshes++ },
                            onToggleFavorite = { lastToggled = it },
                            onSelect = { lastSelected = it },
                            accentColor = accent
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- the list itself

    @Test
    fun rowsCarryTheSameChipsAndSpecLineAsTheModelsScreen() = runComposeUiTest {
        reset()
        render()
        assertShown("deepseek-coder")
        assertShown(":6.7b")
        // The full identity is available on its own line, uncut.
        assertShown("deepseek-coder:6.7b")
        // Capability badges, read through the row's own description.
        listNode().performScrollToNode(hasText("gemma4:26b"))
        onAllNodesWithContentDescription(
            "gemma4:26b on 5800x, vision, reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
        // And the spec line, exactly as the models screen formats it.
        assertShown("25.2B")
        assertShown("256K ctx")
    }

    @Test
    fun anEmbeddingOnlyModelIsLabelled() = runComposeUiTest {
        reset()
        render()
        assertShown("nomic-embed-text")
        assertShown("Embedding only")
    }

    @Test
    fun anUnreportedModelIsStillOffered() = runComposeUiTest {
        reset()
        // No capability may block, hide or disable a model in a picker.
        val loaded = state as ModelCatalogState.Loaded
        state = loaded.copy(
            entries = loaded.entries + entry("mystery-model", null),
            showStates = loaded.showStates + ("mystery-model" to ModelShowState.RESOLVED)
        )
        render()
        assertShown("mystery-model")
        listNode().performScrollToNode(hasText("mystery-model"))
        onAllNodesWithContentDescription(
            "mystery-model on 5800x, capabilities not reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
    }

    // ------------------------------------------------------ favourites / Show all

    @Test
    fun withNoFavoritesEverythingIsListedAndTheToggleIsHidden() = runComposeUiTest {
        reset()
        render()
        assertShown("llama3:latest")
        // Nothing to toggle: there is nothing curated to switch away from.
        onNodeWithText("Favorites").assertDoesNotExist()
        onNodeWithText("Show all").assertDoesNotExist()
    }

    @Test
    fun favoritesOnlyIsTheDefaultOnceTheServerHasAFavorite() = runComposeUiTest {
        reset()
        favorites.value = listOf("[5800x] gemma4:26b")
        render()
        // The curated list is what opens, without the user asking for it.
        onNodeWithText("Favorites").assertIsDisplayed()
        listNode().performScrollToNode(hasText("gemma4:26b"))
        onNodeWithText("gemma4:26b").assertIsDisplayed()
        onNodeWithText("llama3:latest").assertDoesNotExist()
    }

    /**
     * The favourites that arrive after the picker has already opened must still take effect.
     *
     * This is not a corner case invented for the test: the persona editor holds its favourites
     * in a `mutableStateOf(emptyList())` that a collector fills from the service, so a picker
     * opened before that flow's first emission sees no favourites at all. Recording that as
     * "show all" froze the wrong default for the rest of the process.
     */
    @Test
    fun favoritesThatArriveAfterThePickerOpenedStillBecomeTheDefault() = runComposeUiTest {
        reset()
        render()
        // Nothing curated yet, so there is nothing to switch to.
        onNodeWithText("Show all").assertDoesNotExist()
        onNodeWithText("Favorites").assertDoesNotExist()

        favorites.value = listOf("[5800x] gemma4:26b")
        waitForIdle()

        // The list the user curated is what opens, without them having asked for it.
        onNodeWithText("Favorites").assertIsDisplayed()
        listNode().performScrollToNode(hasText("gemma4:26b"))
        onNodeWithText("gemma4:26b").assertIsDisplayed()
        onNodeWithText("llama3:latest").assertDoesNotExist()
    }

    /** A deliberate "Show all" is a choice, so it must outrank the default and survive. */
    @Test
    fun aDeliberateShowAllIsNotOverriddenByTheFavoritesArriving() = runComposeUiTest {
        reset()
        render()
        favorites.value = listOf("[5800x] gemma4:26b")
        waitForIdle()

        onNodeWithText("Favorites").performClick()
        onNodeWithText("Show all").assertIsDisplayed()

        // A later emission must not undo the choice the user actually made.
        favorites.value = listOf("[5800x] gemma4:26b", "[5800x] llama3:latest")
        waitForIdle()
        onNodeWithText("Show all").assertIsDisplayed()
        listNode().performScrollToNode(hasText("llama3:latest"))
        onNodeWithText("llama3:latest").assertIsDisplayed()
    }

    /**
     * The "Show all" choice lasts for as long as this menu stays open, and no longer.
     *
     * DropdownMenu composes its content only while expanded, so closing the menu disposes the
     * picker and unregisters its rememberSaveable provider instead of writing it out. The next
     * open starts from "no choice" and lands back on Favorites.
     */
    @Test
    fun theShowAllChoiceLastsForThisOpenOnly() = runComposeUiTest {
        reset()
        favorites.value = listOf("[5800x] gemma4:26b")
        val mounted = mutableStateOf(true)
        setContent {
            MaterialTheme {
                if (mounted.value) {
                    OllamaModelPicker(
                        server = server,
                        state = state,
                        healthStatus = health,
                        favoriteModels = favorites.value,
                        currentModel = currentModel,
                        onRefresh = { refreshes++ },
                        onToggleFavorite = { lastToggled = it },
                        onSelect = { lastSelected = it },
                        accentColor = accent
                    )
                }
            }
        }
        onNodeWithText("Favorites").performClick()
        onNodeWithText("Show all").assertIsDisplayed()

        // The menu closes, which is what disposes the real picker's content too.
        mounted.value = false
        waitForIdle()
        mounted.value = true
        waitForIdle()

        onNodeWithText("Favorites").assertIsDisplayed()
    }

    /**
     * Un-starring the last favorite while the picker is open must fall back to the full list.
     */
    @Test
    fun losingTheLastFavoriteFallsBackToEveryModel() = runComposeUiTest {
        reset()
        favorites.value = listOf("[5800x] gemma4:26b")
        render()
        onNodeWithText("Favorites").assertIsDisplayed()

        favorites.value = emptyList()
        waitForIdle()

        onNodeWithText("Show all").assertDoesNotExist()
        listNode().performScrollToNode(hasText("llama3:latest"))
        onNodeWithText("llama3:latest").assertIsDisplayed()
    }

    // ---------------------------------------- the current model is always visible

    @Test
    fun theCurrentModelIsMarkedAsSelected() = runComposeUiTest {
        reset()
        currentModel = "[5800x] llama3:latest"
        render()
        assertShown("llama3:latest")
        assertShown("Current")
        listNode().performScrollToNode(hasText("llama3:latest"))
        onAllNodesWithContentDescription(
            "llama3:latest on 5800x, text only, as reported by this server, not a favorite, current model",
            substring = true
        ).assertCountEquals(1)
    }

    @Test
    fun theCurrentModelIsListedWhileItIsNotAFavorite() = runComposeUiTest {
        reset()
        favorites.value = listOf("[5800x] gemma4:26b")
        currentModel = "[5800x] llama3:latest"
        render()
        // Favorites-only is the default here, and the current model is NOT a favorite...
        onNodeWithText("Favorites").assertIsDisplayed()
        // ...yet it is listed and marked, because hiding it would leave the picker unable to
        // show what the persona is actually using.
        assertShown("llama3:latest")
        assertShown("Current")
        // The favorite is there too.
        assertShown("gemma4:26b")
    }

    @Test
    fun aCurrentModelTheServerDeletedIsStillListed() = runComposeUiTest {
        reset()
        currentModel = "[5800x] long-gone:70b"
        render()
        assertShown("long-gone")
        assertShown("Current")
        // It claims nothing the server did not report.
        listNode().performScrollToNode(hasText("long-gone"))
        onAllNodesWithContentDescription(
            "long-gone:70b on 5800x, capabilities not reported by this server, not a favorite, current model",
            substring = true
        ).assertCountEquals(1)
        // And it is not left claiming enrichment is on its way.
        onNodeWithText("checking capabilities…").assertDoesNotExist()
    }

    @Test
    fun aCurrentModelFromAnotherServerIsNotInjected() = runComposeUiTest {
        reset()
        // The picker belongs to 5800x; another connection's model must not be listed here.
        currentModel = "[Other] llama3"
        render()
        onNodeWithText("Current").assertDoesNotExist()
    }

    // ------------------------------------------------------- what a tap reports

    @Test
    fun theStarReportsTheQualifiedString() = runComposeUiTest {
        reset()
        render()
        assertShown("deepseek-coder")
        onNodeWithContentDescription("Toggle favorite deepseek-coder:6.7b on 5800x")
            .assertIsDisplayed().performClick()
        // Exactly what the old inline dropdown sent to toggleFavoriteModel.
        assertEquals("[5800x] deepseek-coder:6.7b", lastToggled)
        // ...and starring is not selecting.
        assertNull("the star must not select the row", lastSelected)
    }

    @Test
    fun selectingARowReportsTheIdenticalStoredString() = runComposeUiTest {
        reset()
        render()
        assertShown("gemma4:26b")
        listNode().performScrollToNode(hasText("gemma4:26b"))
        onNodeWithContentDescription(
            "gemma4:26b on 5800x, vision, reported by this server, not a favorite",
            substring = true
        ).performClick()
        // Byte-for-byte the string the pre-change dropdown wrote into `model`.
        assertEquals("[5800x] gemma4:26b", lastSelected)
    }

    @Test
    fun selectingAFavoriteReportsTheSameQualifiedString() = runComposeUiTest {
        reset()
        favorites.value = listOf("[5800x] gemma4:26b")
        currentModel = "[5800x] llama3:latest"
        render()
        listNode().performScrollToNode(hasText("gemma4:26b"))
        onNodeWithContentDescription("Toggle favorite gemma4:26b on 5800x")
            .performClick()
        assertEquals("[5800x] gemma4:26b", lastToggled)
    }

    // ----------------------------------------------------------------- the states

    @Test
    fun loadingShowsProgress() = runComposeUiTest {
        reset()
        state = ModelCatalogState.Loading
        render()
        assertShown("Loading models…")
    }

    @Test
    fun aFailedFetchSaysSoAndStillShowsTheCurrentModel() = runComposeUiTest {
        reset()
        state = ModelCatalogState.Failed("connection refused", fixedFetch)
        currentModel = "[5800x] llama3:latest"
        render()
        assertShown("Could not load models from this server. connection refused")
        // The failure does not hide what the persona is using.
        assertShown("llama3:latest")
        assertShown("Current")
    }

    @Test
    fun anUnreachableServerKeepsTheLastGoodList() = runComposeUiTest {
        reset()
        health = "failed: connection refused"
        val loaded = state as ModelCatalogState.Loaded
        state = loaded.copy(stale = true, degradedReason = null)
        currentModel = "[5800x] llama3:latest"
        render()
        assertShown("Unreachable — reported by this server")
        assertShown("This server looks unreachable. Showing the last models loaded.")
        // The list, not an error page.
        assertShown("gemma4:26b")
        assertShown("llama3:latest")
    }

    @Test
    fun anEmptyServerSaysSo() = runComposeUiTest {
        reset()
        state = ModelCatalogState.Loaded(emptyList(), emptyMap(), fixedFetch)
        render()
        assertShown("No models on this server")
    }

    // --------------------------------------------------------------- first open

    @Test
    fun aNotLoadedCatalogFetchesOnceOnOpen() = runComposeUiTest {
        reset()
        state = ModelCatalogState.NotLoaded
        render()
        assertEquals("first open fetches exactly once", 1, refreshes)
    }

    @Test
    fun anAlreadyLoadedCatalogDoesNotFetchOnOpen() = runComposeUiTest {
        reset()
        render()
        assertEquals("never polled, and never refetched behind the user's back", 0, refreshes)
    }

    @Test
    fun theRefreshControlForcesARefetch() = runComposeUiTest {
        reset()
        render()
        assertEquals(0, refreshes)
        onNodeWithContentDescription("Refresh models for 5800x").performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun openingThePickerDoesNotChangeTheStoredValue() = runComposeUiTest {
        reset()
        currentModel = "[5800x] llama3:latest"
        render()
        // Merely composing the picker emits nothing: only a tap does.
        assertNull("no selection was made", lastSelected)
        assertNull("no favorite was toggled", lastToggled)
    }

    @Test
    fun showAllListsEverything() = runComposeUiTest {
        reset()
        favorites.value = listOf("[5800x] gemma4:26b")
        render()
        // The chip reads "Favorites" while it is ON; tapping it is the switch to show-all.
        onNodeWithText("Favorites").performClick()
        onNodeWithText("Show all").assertIsDisplayed()
        listNode().performScrollToNode(hasText("llama3:latest"))
        onNodeWithText("llama3:latest").assertIsDisplayed()
        assertShown("gemma4:26b")
    }

    @Test
    fun favoritesAreKeyedByServer() = runComposeUiTest {
        reset()
        favorites.value = listOf("[Other] gemma4:26b")
        render()
        // Not a favorite of 5800x, so the toggle is hidden and nothing is lit.
        onNodeWithText("Favorites").assertDoesNotExist()
        listNode().performScrollToNode(hasText("gemma4:26b"))
        onAllNodesWithContentDescription(
            "gemma4:26b on 5800x, vision, reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
    }

    /**
     * Every row must be reachable when the catalog outgrows the dropdown: the picker must not
     * cap its own height, or the surrounding scroller measures the capped height, finds nothing
     * to scroll, and clips the surplus.
     */
    @Test
    fun everyRowIsReachableWhenTheCatalogOutgrowsTheDropdown() = runComposeUiTest {
        reset()
        val many = (1..14).map { entry("row-$it:8b", chat) }
        state = ModelCatalogState.Loaded(
            entries = many,
            showStates = many.associate { it.name to ModelShowState.RESOLVED },
            fetchedAtMillis = fixedFetch,
        )
        render()

        onNodeWithText("row-1:8b", substring = true).assertExists()

        listNode().performScrollToNode(hasText("row-14:8b"))
        onNodeWithText("row-14:8b", substring = true).assertIsDisplayed()
        // Reaching it took scrolling: the first row is now off-screen.
        onNodeWithText("row-1:8b", substring = true).assertIsNotDisplayed()
        // And it is a real row, not a ghost: it can be starred.
        onNodeWithContentDescription("Toggle favorite row-14:8b on 5800x", substring = true)
            .performClick()
        assertEquals("[$SERVER] row-14:8b", lastToggled)
    }

    private companion object {
        const val HOST_WIDTH_DP = 320
        const val HOST_HEIGHT_DP = 460
        const val SERVER = "5800x"
        const val HOST_TAG = "pickerHostScroller"
        const val FIXED_HOUR = 9
        const val FIXED_MINUTE = 5
    }
}
