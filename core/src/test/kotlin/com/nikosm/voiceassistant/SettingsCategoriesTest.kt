package com.nikosm.voiceassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings registry and its summary lines.
 *
 * A category with no case in `settingsCategorySummaries` renders a blank row rather than failing —
 * the registry's own KDoc says so, and says a new category is "covered by adding its summary case
 * here". That is a silent, cosmetic failure, which is exactly the kind that survives review: the
 * row is there, clickable, and looks like a mistake in the list rather than a missing line of
 * code. M2 added the "Audio" category, so this is the moment to pin the rule rather than the
 * category.
 */
class SettingsCategoriesTest {

    @Test
    fun `every settings category has a summary line`() {
        val summaries = settingsCategorySummaries(
            ollamaServers = 0, gatewayServers = 0, imageGenServers = 0,
            cloudProvidersWithKey = 0, customCloudProviders = 0,
            personas = 0, rooms = 0, skills = 0,
        )

        SETTINGS_CATEGORIES.forEach { category ->
            assertTrue(
                "'${category.title}' would draw a blank row — add its case to settingsCategorySummaries",
                summaries[category]?.isNotBlank() == true,
            )
        }
    }

    /**
     * The other silent failure in this screen family, and the more expensive one: **a category page
     * must not bring a scroller of its own.**
     *
     * `SettingsScreens.kt` hosts every page's content inside a `LazyColumn` item, and a lazy item is
     * measured with unbounded height — so a `verticalScroll` inside one is measured against infinity
     * and Compose throws *"Vertically scrollable component was measured with an infinity maximum
     * height constraints"*. The window dies, and it dies on **opening the page**. `ModelPicker`
     * states the same rule in a comment, for the same reason, one host up.
     *
     * Not hypothetical: M8's Settings → Filesystem page wrapped its content in one, so it crashed
     * every time it was opened — from the commit that wrote it until the first live run that opened
     * it, through an adversarial review and 454 green tests. No test here can *compose* a UI (there
     * is no Compose test runtime on this classpath), so the rule is pinned at the source instead.
     *
     * The scan covers the file the category pages live in. That is deliberately narrow, and the
     * second line of defence is where it belongs: the shell's own comment, at the point where a new
     * page's content is put inside that `LazyColumn` item.
     */
    @Test
    fun `the filesystem category page brings no scroller of its own`() {
        val source = File("src/main/kotlin/com/nikosm/voiceassistant/FsToolScreens.kt")
        assertTrue("the screen file has moved — this test's path is stale", source.isFile)
        // The *call*, not the word: the file explains this rule in a comment, and a scan for the
        // bare name would match its own documentation.
        assertFalse(
            "FsToolScreens.kt calls verticalScroll: the settings shell already scrolls every " +
                "category page, and a second scroller inside a LazyColumn item is measured against " +
                "infinite height and throws when the page opens",
            source.readText().contains("verticalScroll("),
        )
    }
}
