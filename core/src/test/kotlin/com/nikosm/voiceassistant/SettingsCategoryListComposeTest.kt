package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings home screen, composed on its own (ported from upstream's instrumented test in
 * M7).
 *
 * `SettingsCategoryList` is a static list over precomputed data: no clock, no animation, no
 * infinite transition, no service binding. That is what makes this test deterministic — it is
 * exactly why the list takes its summaries as a parameter instead of collecting them itself.
 *
 * The one port change is the runner: upstream's `createComposeRule` becomes Compose Desktop's
 * `runComposeUiTest`. Everything else — the short fixed host, the scroll-into-view discipline,
 * the assertions — is upstream's, because those are the properties that make "below the fold" a
 * fact about the test rather than about the screen it happens to run on.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsCategoryListComposeTest {

    private val accent = Color(0xFF4ADE80)
    private var summaries: Map<SettingsCategory, String> = emptyMap()
    private var selected: SettingsCategory? = null

    private fun setUpSummaries() {
        summaries = settingsCategorySummaries(
            ollamaServers = 2,
            gatewayServers = 1,
            imageGenServers = 1,
            cloudProvidersWithKey = 3,
            customCloudProviders = 0,
            personas = 14,
            rooms = 2,
            skills = 4
        )
        selected = null
    }

    /** The scrolling list itself, for [performScrollToNode]. */
    private fun ComposeUiTest.listNode() = onNode(hasScrollAction())

    /** Scrolls the row labelled [text] into view, then asserts it is really displayed. */
    private fun ComposeUiTest.assertRowDisplayed(text: String) {
        listNode().performScrollToNode(hasText(text))
        onNodeWithText(text).assertIsDisplayed()
    }

    /** Scrolls the row labelled [text] into view and clicks it. */
    private fun ComposeUiTest.clickRow(text: String) {
        listNode().performScrollToNode(hasText(text))
        onNodeWithText(text).performClick()
    }

    /** Renders the list exactly as the dialog does — nothing else. */
    private fun ComposeUiTest.render() {
        setContent {
            MaterialTheme {
                // A deliberately SHORT, fixed-size host: the list is a LazyColumn, so the rows
                // below the first screenful are not composed until scrolled to. Pinning the
                // viewport makes "below the fold" a property of the test.
                Box(Modifier.size(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    SettingsCategoryList(
                        accentColor = accent,
                        summaries = summaries,
                        onCategorySelected = { selected = it }
                    )
                }
            }
        }
    }

    @Test
    fun everyCategoryRowShowsItsTitleAndSummary() = runComposeUiTest {
        setUpSummaries()
        render()

        onNodeWithText("Settings").assertIsDisplayed()

        listOf(
            "General" to GENERAL_SETTINGS_SUMMARY,
            "Servers" to "2 Ollama, 1 gateway, 1 image-gen",
            "Cloud" to "3 providers",
            "Personas" to "14 personas",
            "Rooms" to "2 rooms",
            "Skills" to "4 skills"
        ).forEach { (title, summary) ->
            assertRowDisplayed(title)
            assertRowDisplayed(summary)
        }
    }

    @Test
    fun theSectionCaptionsAreShown() = runComposeUiTest {
        setUpSummaries()
        render()
        listOf("App", "Connections", "Assistants", "System").forEach { caption ->
            listNode().performScrollToNode(hasText(caption))
            onNodeWithText(caption).assertIsDisplayed()
        }
    }

    /**
     * Retargeted 2026-10-18 (recorded in docs/TEST-MAP.md): the registry gained a System section
     * holding Audio, Health, Filesystem and Terminal, and MCP moved to Connections. The registry
     * is the single source of the screen's shape, so asserting it directly is what makes the move
     * a checked fact rather than a comment.
     */
    @Test
    fun systemHoldsTheMachinePagesAndMcpSitsWithConnections() {
        val groups = settingsCategoryGroups().associate { it.caption to it.categories.map { c -> c.title } }

        assertEquals(listOf("Audio", "Health", "Filesystem", "Terminal"), groups["System"])
        assertTrue(groups["Connections"]!!.contains("MCP"))
        assertTrue(
            groups["Connections"]!!.none { it == "Audio" || it == "Health" }
        )
        assertTrue(
            groups["Assistants"]!!.none { it == "MCP" || it == "Filesystem" || it == "Terminal" }
        )
    }

    @Test
    fun tappingARowReportsThatCategory() = runComposeUiTest {
        setUpSummaries()
        render()

        clickRow("Servers")

        assertEquals("Servers", selected?.title)
    }

    @Test
    fun tappingAnyRowReportsItsOwnCategory() = runComposeUiTest {
        setUpSummaries()
        render()
        listOf("General", "Servers", "Cloud", "Personas", "Rooms", "Skills").forEach { title ->
            selected = null
            clickRow(title)
            assertEquals("tapping '$title' reported the wrong category", title, selected?.title)
        }
    }

    @Test
    fun eachRowReadsAsOneAccessibleElementNamingItAndItsSummary() = runComposeUiTest {
        setUpSummaries()
        render()

        // The whole row is the click target, and a screen reader gets "Servers, 2 Ollama,
        // 1 gateway, 1 image-gen" — not three separate unlabeled fragments.
        listNode().performScrollToNode(hasText("Servers"))
        onNodeWithContentDescription("Servers, 2 Ollama, 1 gateway, 1 image-gen")
            .assertIsDisplayed()
            .performClick()
        assertEquals("Servers", selected?.title)
    }

    @Test
    fun anEmptyConfigurationStillRendersEveryRowWithAFallbackSummary() = runComposeUiTest {
        summaries = settingsCategorySummaries(0, 0, 0, 0, 0, 0, 0, 0)
        selected = null
        render()

        listOf("No servers yet", "None set up yet", "No rooms yet").forEach { text ->
            assertRowDisplayed(text)
        }
        assertTrue(SETTINGS_CATEGORIES.isNotEmpty())
    }

    private companion object {
        const val HOST_WIDTH_DP = 360
        const val HOST_HEIGHT_DP = 420
    }
}
