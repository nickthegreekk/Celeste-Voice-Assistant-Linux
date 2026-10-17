package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
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
import org.junit.Test

/**
 * The per-server models screen, composed on its own (ported from upstream's instrumented test
 * in M7; the runner is the only change).
 *
 * `ModelsScreen` takes its state as parameters and never collects the service itself, so a
 * fixed state renders identically every run: no clock (the freshness line is computed from a
 * fixed timestamp), no service binding, no infinite transition.
 */
@OptIn(ExperimentalTestApi::class)
class ModelsScreenComposeTest {

    /**
     * Today at 14:32 **local**, so "Updated 14:32" is stable wherever the suite runs: a
     * hardcoded instant would render as a different wall-clock time on every timezone.
     */
    private val fixedFetch: Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, FIXED_HOUR)
        set(java.util.Calendar.MINUTE, FIXED_MINUTE)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val accent = Color(0xFF4ADE80)
    private val server = ServerConfig(
        name = SERVER, url = URL, username = null, password = null,
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
    private val visionChat = setOf(ModelCapability.COMPLETION, ModelCapability.VISION, ModelCapability.TOOLS)

    private var state: ModelCatalogState = ModelCatalogState.NotLoaded
    private var health: String? = "Online"
    private var favorites: List<String> = emptyList()
    private var refreshes = 0
    private var lastToggled: String? = null
    private var backs = 0

    private fun reset() {
        state = ModelCatalogState.Loaded(
            entries = listOf(
                entry("deepseek-coder:6.7b", setOf(ModelCapability.COMPLETION, ModelCapability.TOOLS)),
                entry("gemma4:26b", visionChat, params = "25.2B", ctx = 262_144L),
                entry("nomic-embed-text", setOf(ModelCapability.EMBEDDING), params = "137M", quant = "F16"),
                entry("unreported-model", null)
            ),
            fetchedAtMillis = fixedFetch,
            showStates = mapOf(
                "deepseek-coder:6.7b" to ModelShowState.RESOLVED,
                "gemma4:26b" to ModelShowState.RESOLVED,
                "nomic-embed-text" to ModelShowState.RESOLVED,
                "unreported-model" to ModelShowState.RESOLVED
            )
        )
        health = "Online"
        favorites = emptyList()
        refreshes = 0
        lastToggled = null
        backs = 0
    }

    private fun ComposeUiTest.listNode() = onNodeWithTag(HOST_TAG)

    private fun ComposeUiTest.assertShown(text: String) {
        listNode().performScrollToNode(hasText(text, substring = true))
        onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * A capability label exists twice on purpose: once as a FILTER CHIP and once as a row's
     * reported-capability badge. Only the chip is clickable, so clickability picks it out.
     */
    private fun ComposeUiTest.filterChip(text: String) = onNode(hasText(text) and hasClickAction())

    private fun ComposeUiTest.clickFilterChip(text: String) {
        listNode().performScrollToNode(hasText(text))
        filterChip(text).performClick()
    }

    private fun ComposeUiTest.render() {
        setContent {
            MaterialTheme {
                Box(Modifier.size(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    Box(
                        Modifier
                            // Tagged because the screen itself contains a horizontally scrolling
                            // chip row: `hasScrollAction()` alone matches both.
                            .testTag(HOST_TAG)
                            .verticalScroll(rememberScrollState())
                    ) {
                        ModelsScreen(
                            server = server,
                            state = state,
                            healthStatus = health,
                            favoriteModels = favorites,
                            onRefresh = { refreshes++ },
                            onToggleFavorite = { lastToggled = it },
                            accentColor = accent,
                            onBack = { backs++ }
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- rows and chips

    @Test
    fun rowsShowSplitNamesChipsAndSpecs() = runComposeUiTest {
        reset()
        render()
        assertShown("deepseek-coder")
        // The tag is shown, and NOT repeated inside the prominent name.
        assertShown(":6.7b")
        assertShown("deepseek-coder:6.7b")
        assertShown("gemma4:26b")
        onAllNodesWithContentDescription(
            "gemma4:26b on 5800x, vision, tools, reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
        assertShown("25.2B")
        assertShown("256K ctx")
        // Sizes render in GiB (the same formatter the rest of the app uses).
        assertShown("4.7 GB")
    }

    @Test
    fun textIsNeverShownAsAChip() = runComposeUiTest {
        reset()
        render()
        assertShown("gemma4:26b")
        // COMPLETION is real, it is simply not worth a chip: every chat model has it.
        onNodeWithText("Text").assertDoesNotExist()
    }

    @Test
    fun anEmbeddingOnlyModelIsLabelled() = runComposeUiTest {
        reset()
        render()
        assertShown("nomic-embed-text")
        assertShown("Embedding only")
    }

    @Test
    fun anUnreportedModelClaimsNoCapability() = runComposeUiTest {
        reset()
        render()
        assertShown("unreported-model")
        listNode().performScrollToNode(hasText("unreported-model"))
        onAllNodesWithContentDescription(
            "unreported-model on 5800x, capabilities not reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
    }

    @Test
    fun theHeaderShowsFreshnessAndHealth() = runComposeUiTest {
        reset()
        render()
        assertShown("Updated 14:32")
        assertShown("Online")
    }

    @Test
    fun neverLoadedSaysSo() = runComposeUiTest {
        reset()
        state = ModelCatalogState.NotLoaded
        render()
        assertShown("Never loaded")
    }

    // ------------------------------------------------------------------ the star

    @Test
    fun theStarCallsBackWithTheServerQualifiedName() = runComposeUiTest {
        reset()
        render()
        assertShown("deepseek-coder")
        listNode().performScrollToNode(hasText("deepseek-coder"))
        onAllNodesWithContentDescription(
            "deepseek-coder:6.7b on 5800x, tools, reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
        // The star is the row's only control; tapping it must send the QUALIFIED name.
        onNodeWithContentDescription("Toggle favorite deepseek-coder:6.7b on 5800x")
            .assertIsDisplayed().performClick()
        assertEquals("[5800x] deepseek-coder:6.7b", lastToggled)
    }

    @Test
    fun favouritesAreKeyedByServer() = runComposeUiTest {
        reset()
        favorites = listOf("[$SERVER] gemma4:26b", "[Other] gemma4:26b")
        render()
        assertShown("gemma4:26b")
        // The Other server's favorite must not light this one up.
        listNode().performScrollToNode(hasText("gemma4:26b"))
        onAllNodesWithContentDescription(
            "gemma4:26b on 5800x, vision, tools, reported by this server, favorite",
            substring = true
        ).assertCountEquals(1)
    }

    // ------------------------------------------------------------------ filtering

    @Test
    fun theVisionFilterNarrowsTheList() = runComposeUiTest {
        reset()
        render()
        assertShown("deepseek-coder")
        clickFilterChip("Vision")
        listNode().performScrollToNode(hasText("gemma4:26b"))
        onNodeWithText("gemma4:26b").assertIsDisplayed()
        // The non-vision models are gone, and the unreported one never matched.
        onNodeWithText("deepseek-coder:6.7b").assertDoesNotExist()
        onNodeWithText("unreported-model").assertDoesNotExist()
    }

    @Test
    fun filtersAreAnded() = runComposeUiTest {
        reset()
        render()
        clickFilterChip("Vision")
        clickFilterChip("Thinking")
        // gemma4 has vision but not thinking; nothing reports both.
        assertShown("No model on this server reports vision, thinking — reported by this server")
    }

    @Test
    fun anEmptyFilterResultExplainsItself() = runComposeUiTest {
        reset()
        render()
        clickFilterChip("Audio")
        assertShown("No model on this server reports audio — reported by this server")
    }

    // -------------------------------------------------------------- favourites view

    @Test
    fun favouritesToggleDefaultsToShowAllAndExplainsAnEmptyView() = runComposeUiTest {
        reset()
        render()
        assertShown("Show all")
        onNodeWithText("Show all").performClick()
        assertShown("No favorites yet. Tap a star to add one.")
    }

    @Test
    fun favouritesOnlyShowsJustTheFavourites() = runComposeUiTest {
        reset()
        favorites = listOf("[$SERVER] gemma4:26b")
        render()
        onNodeWithText("Show all").performClick()
        assertShown("gemma4:26b")
        onNodeWithText("deepseek-coder:6.7b").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ the states

    @Test
    fun loadingShowsProgress() = runComposeUiTest {
        reset()
        state = ModelCatalogState.Loading
        render()
        assertShown("Loading models…")
    }

    @Test
    fun aStaleListIsKeptWhileRefreshing() = runComposeUiTest {
        reset()
        state = (state as ModelCatalogState.Loaded).copy(stale = true, degradedReason = UNREACHABLE_REASON)
        render()
        assertShown("Showing the last models loaded (Couldn't reach this server).")
        // ...and the list itself is still there, not blanked.
        assertShown("gemma4:26b")
    }

    @Test
    fun aStaleListCarriesTheHttpStatusThatBrokeIt() = runComposeUiTest {
        reset()
        state = (state as ModelCatalogState.Loaded).copy(
            stale = true,
            degradedReason = httpErrorReason(503)
        )
        render()
        assertShown("Showing the last models loaded (This server returned an error (HTTP 503)).")
        assertShown("gemma4:26b")
    }

    @Test
    fun anUnreachableServerKeepsTheLastGoodList() = runComposeUiTest {
        reset()
        health = "failed: connection refused"
        state = (state as ModelCatalogState.Loaded).copy(stale = true, degradedReason = null)
        render()
        assertShown("Unreachable — reported by this server")
        assertShown("This server looks unreachable. Showing the last models loaded.")
        assertShown("gemma4:26b")
    }

    @Test
    fun aFailedFetchWithNoListSaysSo() = runComposeUiTest {
        reset()
        state = ModelCatalogState.Failed("connection refused", fixedFetch)
        render()
        assertShown("Could not load models from this server. connection refused")
    }

    @Test
    fun anEmptyServerSaysSo() = runComposeUiTest {
        reset()
        state = ModelCatalogState.Loaded(emptyList(), emptyMap(), fixedFetch)
        render()
        assertShown("No models on this server")
    }

    // ----------------------------------------------------------- pending + back nav

    @Test
    fun aPendingShowHoldsTheChipsPlace() = runComposeUiTest {
        reset()
        val loaded = state as ModelCatalogState.Loaded
        state = loaded.copy(
            showStates = loaded.showStates + ("deepseek-coder:6.7b" to ModelShowState.PENDING)
        )
        render()
        assertShown("deepseek-coder")
        // A placeholder, so the chips do not flip once enrichment lands.
        listNode().performScrollToNode(hasText("deepseek-coder"))
        assertShown("checking capabilities…")
    }

    @Test
    fun backReturnsToTheServerList() = runComposeUiTest {
        reset()
        render()
        onNodeWithContentDescription("Back to servers").performClick()
        assertEquals(1, backs)
    }

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

    private companion object {
        const val HOST_WIDTH_DP = 360
        const val HOST_HEIGHT_DP = 520
        const val SERVER = "5800x"
        const val URL = "http://127.0.0.1:11434"
        const val HOST_TAG = "modelsHostScroller"
        const val FIXED_HOUR = 14
        const val FIXED_MINUTE = 32
    }
}
