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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * M18.4: `{{...}}` in a persona prompt, skill or lorebook entry resolves at composition time.
 *
 * The two contracts this file pins are what make the feature safe: a known key becomes the live
 * value (with aliases and tolerant whitespace/case), and an UNKNOWN key is returned exactly as
 * written — so a prompt that discusses a literal `{{example}}` is not eaten by the app.
 */
class PromptPlaceholdersTest {

    private val env = FakeAppEnv()
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val dispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, dispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    private fun service(): AssistantService = AssistantService(
        appEnv = env,
        audioPlayback = SilentAudioPlayback,
        audioDevices = NoAudioDevices,
        tts = NoTextToSpeech,
        audioCapture = NoAudioCapture,
        fileDialogs = NoFileDialogs,
        settingsManager = settings,
    ).also { services += it }

    private fun persona(
        name: String = "Celeste",
        prompt: String = "You are {{char}}, speaking with {{user}}.",
        model: String = "[Home] ornith-1.5:9b",
    ) = Persona(
        name = name,
        themeColor = Color(0xFF7C5CE0),
        model = model,
        systemPrompt = prompt,
    )

    private fun values() = PlaceholderValues(
        date = "2026-11-01",
        time = "09:05",
        dateTime = "2026-11-01 09:05",
        timezone = "Europe/Athens",
        modelName = "ornith-1.5:9b",
        personaName = "Celeste",
        userName = "Nikos",
        location = "Athens",
        os = "Linux 6.18.60-1-lts",
    )

    @Test
    fun `every known key and alias resolves`() {
        val text = "{{cur_date}}|{{date}}|{{cur_time}}|{{time}}|{{cur_datetime}}|{{datetime}}|" +
            "{{timezone}}|{{model_name}}|{{model}}|{{char}}|{{persona}}|{{user}}|" +
            "{{location}}|{{os}}|{{system_version}}"
        assertEquals(
            "2026-11-01|2026-11-01|09:05|09:05|2026-11-01 09:05|2026-11-01 09:05|" +
                "Europe/Athens|ornith-1.5:9b|ornith-1.5:9b|Celeste|Celeste|Nikos|" +
                "Athens|Linux 6.18.60-1-lts|Linux 6.18.60-1-lts",
            resolvePlaceholders(text, values()),
        )
    }

    @Test
    fun `keys are case-insensitive and tolerate inner whitespace`() {
        assertEquals(
            "Nikos 2026-11-01",
            resolvePlaceholders("{{ USER }} {{ Cur_Date }}", values()),
        )
    }

    @Test
    fun `uppercase keys resolve even under a Turkish default locale`() {
        val original = Locale.getDefault()
        try {
            // Turkish lowercases a Latin I to a dotless letter; `{{TIMEZONE}}` must still match.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("Europe/Athens", resolvePlaceholders("{{TIMEZONE}}", values()))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `an unknown key is left exactly as written`() {
        val text = "Unknown {{battery_level}} and {{ not a key }} stay."
        assertEquals(text, resolvePlaceholders(text, values()))
        assertEquals("no placeholders here", resolvePlaceholders("no placeholders here", values()))
    }

    @Test
    fun `the live values format the clock and fall back to the word user`() {
        val now = ZonedDateTime.of(2026, 11, 1, 9, 5, 0, 0, ZoneId.of("Europe/Athens"))
        val v = placeholderValues(now, "m", "P", userName = "", location = "", os = "Linux")
        assertEquals("2026-11-01", v.date)
        assertEquals("09:05", v.time)
        assertEquals("2026-11-01 09:05", v.dateTime)
        assertEquals("Europe/Athens", v.timezone)
        assertEquals("user", v.userName)
    }

    @Test
    fun `composeSystemPrompt resolves the persona prompt only when values are supplied`() {
        val p = persona()
        val composed = composeSystemPrompt(
            persona = p,
            currentDateTime = "Sunday, November 1, 2026, 09:05",
            searchContext = "",
            ragContext = "",
            suffix = "",
            placeholders = values(),
        )
        assertTrue(composed.contains("You are Celeste, speaking with Nikos."))
        assertFalse(composed.contains("{{char}}"))

        val untouched = composeSystemPrompt(
            persona = p,
            currentDateTime = "Sunday, November 1, 2026, 09:05",
            searchContext = "",
            ragContext = "",
            suffix = "",
        )
        assertTrue("null placeholders must leave the stored prompt alone", untouched.contains("{{char}}"))
    }

    @Test
    fun `the live values read the nickname, location and server-tagged model`() {
        settings.saveUserNickname("  Nikos  ")
        settings.saveUserLocation("Athens")
        val now = ZonedDateTime.of(2026, 11, 1, 9, 5, 0, 0, ZoneId.of("Europe/Athens"))

        val v = service().placeholderValuesFor(persona(), now)

        assertEquals("ornith-1.5:9b", v.modelName)
        assertEquals("Nikos", v.userName)
        assertEquals("Athens", v.location)
        assertEquals("Celeste", v.personaName)
    }

    @Test
    fun `a skill and a lorebook entry resolve with the same values as the persona prompt`() {
        val svc = service()
        settings.saveUserNickname("Nikos")
        val skill = Skill(
            name = "Greeting",
            triggerPhrases = listOf("hello"),
            instructions = "Greet {{user}} by name.",
        )
        val book = Lorebook(
            id = "book",
            name = "World",
            entries = listOf(
                LorebookEntry(
                    id = "entry",
                    name = "Tea",
                    content = "{{user}} prefers tea.",
                    activation = LorebookActivation.ALWAYS,
                ),
            ),
        )
        svc.addSkill(skill)
        val p = persona().copy(enabledSkillIds = listOf(skill.id))
        svc.addPersona(p)
        svc.addLorebook(book)
        svc.setLorebookEnabledForPersona(p.name, book.id, true)

        val ctx = svc.turnContextWithContext("hello there", p, emptyList())

        assertTrue(ctx.systemPromptSuffix.contains("Greet Nikos by name."))
        assertTrue(ctx.systemPromptSuffix.contains("Nikos prefers tea."))
        assertFalse(ctx.systemPromptSuffix.contains("{{user}}"))
    }

    @Test
    fun `the nickname is saved trimmed and survives a backup round-trip`() {
        settings.saveUserNickname("  Nikos  ")
        assertEquals("Nikos", settings.getUserNickname())

        val restored = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        assertTrue(restored.importBackup(settings.exportBackup()))

        assertEquals("Nikos", restored.getUserNickname())
    }
}
