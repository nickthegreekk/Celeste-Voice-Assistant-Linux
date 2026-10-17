package com.nikosm.voiceassistant

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.nikosm.voiceassistant.ui.theme.DarkColorScheme
import com.nikosm.voiceassistant.ui.theme.LightColorScheme
import com.nikosm.voiceassistant.ui.theme.baseColorScheme
import com.nikosm.voiceassistant.ui.theme.isDynamicColorSupported
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
 * The Adaptive Theme's portable half (ported from upstream's instrumented test in M7).
 *
 * Upstream's dynamic cases assert equality against Android's wallpaper-derived scheme; this port
 * has no `WallpaperManager` and `baseColorScheme` deliberately has no dynamic branch, so those
 * cases are **dropped rather than faked** — the feature they test does not exist here, and a
 * stand-in scheme would only assert the stand-in. What remains is the part that is real on both
 * platforms: the switch resolves to the exact static scheme, the dynamic gate is closed, and the
 * service's flow and the settings file agree in both directions.
 *
 * The comparison is field-wise because `ColorScheme` is not a data class; upstream's own comment
 * ("never by identity") travels with the helper.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class AdaptiveThemeTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Field-wise comparison: ColorScheme is compared by value, never by identity. */
    private fun assertSameScheme(expected: ColorScheme, actual: ColorScheme, tag: String) {
        assertEquals("$tag.primary", expected.primary, actual.primary)
        assertEquals("$tag.onPrimary", expected.onPrimary, actual.onPrimary)
        assertEquals("$tag.secondary", expected.secondary, actual.secondary)
        assertEquals("$tag.tertiary", expected.tertiary, actual.tertiary)
        assertEquals("$tag.background", expected.background, actual.background)
        assertEquals("$tag.surface", expected.surface, actual.surface)
        assertEquals("$tag.onSurface", expected.onSurface, actual.onSurface)
        assertEquals("$tag.error", expected.error, actual.error)
    }

    @Test
    fun `the switch off resolves to the exact static schemes`() = runComposeUiTest {
        lateinit var dark: ColorScheme
        lateinit var light: ColorScheme
        setContent {
            dark = baseColorScheme(darkTheme = true)
            light = baseColorScheme(darkTheme = false)
        }
        waitForIdle()

        assertSameScheme(DarkColorScheme, dark, "static-dark")
        assertSameScheme(LightColorScheme, light, "static-light")
    }

    @Test
    fun `dynamic colour is unsupported on the desktop, so the branch cannot be taken`() {
        assertFalse(isDynamicColorSupported())
    }

    @Test
    fun `the persona accents stay pairwise distinct`() {
        val accents = (DEFAULT_PERSONAS + CLOUD_PERSONAS + TRANSLATOR_PERSONA)
            .map { it.name to it.themeColor }

        assertEquals(
            "built-in personas must keep pairwise-distinct accents",
            accents.size,
            accents.map { it.second }.distinct().size,
        )
    }

    @Test
    fun `the service flow and the settings file agree in both directions`() {
        val service = AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        )
        try {
            assertFalse(
                "the toggle's default must be OFF, so a user who never touches it keeps the " +
                    "static theme",
                service.adaptiveTheme.value,
            )

            service.setAdaptiveTheme(true)
            assertTrue("ON must re-emit on the flow MainActivity collects", service.adaptiveTheme.value)
            assertTrue(
                "ON must persist — the next launch reads it back",
                SettingsManager(env, store, NoImageCodec).getAdaptiveTheme(),
            )

            service.setAdaptiveTheme(false)
            assertFalse("OFF must re-emit false", service.adaptiveTheme.value)
            assertFalse(
                "OFF must persist, or the next launch would silently re-enable it",
                SettingsManager(env, store, NoImageCodec).getAdaptiveTheme(),
            )
        } finally {
            runCatching { service.shutdown() }
        }
    }
}
