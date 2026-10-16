package com.nikosm.voiceassistant

import org.junit.Assert.assertTrue
import org.junit.Test

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
}
