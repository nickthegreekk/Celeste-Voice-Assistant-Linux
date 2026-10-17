package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 * Spontaneous scheduling — the delivery cycle and the service-level lifecycle wiring (ported
 * from upstream's instrumented worker test in M7).
 *
 * The decision arithmetic is exhaustively covered on the JVM by `SpontaneousSchedulerTest`, and
 * the timer's own arm/disarm semantics by `SpontaneousTimerTest`. What this class adds is that
 * the pieces AROUND the decision behave against the real service and the `Scheduler` seam:
 * a run's cancel/not-due paths, the delivery state's date rollover, the cleanup a deletion and a
 * rename perform, and the import reconciliation that forces a fresh schedule.
 *
 * Upstream drove its WorkManager worker through `TestListenableWorkerBuilder` and inspected
 * unique-work chains. The port has no WorkManager: the service attaches a delivery lambda to the
 * in-process scheduler, so here the same entry point is called directly
 * (`runSpontaneousDelivery`) and the seam's state is read from `RecordingScheduler`. The
 * WorkManager-specific observations are dropped with their subject and recorded in
 * docs/TEST-MAP.md: unique-work replacement by slot, chain ids, and the null-input run (the
 * in-process scheduler has no work name and no unattributed input).
 *
 * Every probe persona's window is placed ~12 hours from the wall clock, so a run is always out
 * of hours whatever time the suite executes — the one scenario that proves the re-arm path
 * without ever performing a generation.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpontaneousDeliveryTest {

    private companion object {
        const val SERVER = "ProbeWorkerOllama"
        const val ENABLED = "ProbeWorkerEnabled"
        const val DISABLED = "ProbeWorkerDisabled"
        const val GHOST = "ProbeWorkerGhost"
        const val DELETE = "ProbeWorkerDelete"
        const val RENAME_OLD = "ProbeWorkerRenameOld"
        const val RENAME_NEW = "ProbeWorkerRenameNew"
        const val HOUR_MS = 60 * 60 * 1000L
    }

    private val scheduler = RecordingScheduler()
    private val env = FakeAppEnv(notifier = RecordingNotifier(), scheduler = scheduler)
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val mainDispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()

    private lateinit var service: AssistantService

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
        // The persisted server list, not only the service's flow: `settings.savePersonas`
        // sanitizes against the STORE's configured servers, so a persona saved before the
        // server reaches the store would have its opt-in stripped on the way in.
        settings.saveOllamaBases(listOf(ServerConfig(SERVER, "http://192.0.2.9:11434")))
        service._ollamaBaseUrls.value = listOf(ServerConfig(SERVER, "http://192.0.2.9:11434"))
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        services.clear()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A one-hour window positioned ~12 hours ahead of the wall clock, so the run under test is
     * always outside it — at any hour the suite happens to run.
     */
    private fun windowFarFromNow(): Pair<Int, Int> {
        val now = spontaneousMinuteOfDay(System.currentTimeMillis(), ZoneId.systemDefault())
        val start = (now + 12 * 60) % 1440
        return start to (start + 60) % 1440
    }

    private fun probePersona(name: String, enabled: Boolean, window: Pair<Int, Int>? = null): Persona =
        Persona(
            name = name,
            themeColor = Color(0xFF4ADE80),
            model = "[$SERVER] llama3",
            systemPrompt = "You are $name.",
            isCloud = false,
            spontaneousEnabled = enabled,
            spontaneousGapMinutes = 15,
            spontaneousWindowStartMin = window?.first,
            spontaneousWindowEndMin = window?.second
        )

    private fun saveProbe(name: String, enabled: Boolean, window: Pair<Int, Int>? = null) {
        val persona = probePersona(name, enabled, window)
        settings.savePersonas(
            settings.getPersonas().orEmpty().filterNot { it.name == name } + persona
        )
        if (service.personas.value.none { it.name == name }) service.addPersona(persona)
        // Non-vacuity: the guard must not have stripped the toggle, or every "re-armed"
        // assertion below would be testing a persona the worker cancels on sight.
        val stored = settings.getPersonas()!!.first { it.name == name }
        assertEquals(
            "probe '$name' must round-trip with the toggle it was saved with",
            enabled, stored.spontaneousEnabled
        )
    }

    private fun runDelivery(personaName: String, slot: Int = 0) {
        runBlocking { service.runSpontaneousDelivery(personaName, slot) }
        mainDispatcher.scheduler.runCurrent()
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return true
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        return condition()
    }

    // ---------------------------------------------------------------- the run

    @Test
    fun `a run for a missing persona ends the chain`() {
        // GHOST is never saved, so the first guard is the one under test: this must be a
        // CANCEL (chain ends), never a re-arm that would keep a dead timer alive forever.
        runDelivery(GHOST)

        assertFalse("nothing may be left pending for a persona that is gone", scheduler.isArmed(GHOST))
        assertEquals(
            "a cancelled run must not fabricate delivery state",
            SpontaneousState(), settings.spontaneousStateFor(GHOST)
        )
    }

    @Test
    fun `a run for a switched-off persona ends the chain`() {
        saveProbe(DISABLED, enabled = false)
        runDelivery(DISABLED)
        assertFalse("a switched-off persona must not be re-armed", scheduler.isArmed(DISABLED))
    }

    @Test
    fun `an out-of-window run is re-armed`() {
        // The control for the two tests above, and the reason they are not vacuous: an
        // eligible, switched-on persona DOES leave a chain behind — so "not armed" above is
        // the cancel path doing its job, not the worker failing to arm anything at all.
        val window = windowFarFromNow()
        saveProbe(ENABLED, enabled = true, window = window)
        assertFalse(scheduler.isArmed(ENABLED))

        runDelivery(ENABLED)

        assertTrue(
            "an out-of-window run must arm the next attempt and end this one",
            waitFor { scheduler.isArmed(ENABLED) }
        )
        assertEquals(
            "a run that sent nothing must not move lastSentAt",
            SpontaneousState(), settings.spontaneousStateFor(ENABLED)
        )
    }

    @Test
    fun `repeated out-of-window runs each re-arm`() {
        saveProbe(ENABLED, enabled = true, window = windowFarFromNow())
        val before = scheduler.arms.count { it.personaName == ENABLED }
        repeat(3) { runDelivery(ENABLED) }
        assertTrue(waitFor { scheduler.arms.count { it.personaName == ENABLED } >= before + 3 })
        assertTrue("the chain must still be armed", scheduler.isArmed(ENABLED))
    }

    // ---------------------------------------------------------------- delivery state

    @Test
    fun `delivery state rolls its counter on a new date`() {
        settings.deleteSpontaneousState(ENABLED)
        assertEquals(SpontaneousState(), settings.spontaneousStateFor(ENABLED))

        settings.recordSpontaneousSent(ENABLED, at = 1_000L, todayDate = "2026-01-01")
        settings.recordSpontaneousSent(ENABLED, at = 2_000L, todayDate = "2026-01-01")
        val sameDay = settings.spontaneousStateFor(ENABLED)
        assertEquals(2, sameDay.sentTodayCount)
        assertEquals(2_000L, sameDay.lastSentAt)
        assertEquals("2026-01-01", sameDay.sentTodayDate)

        // A new local date must RESET the counter.
        settings.recordSpontaneousSent(ENABLED, at = 3_000L, todayDate = "2026-01-02")
        val nextDay = settings.spontaneousStateFor(ENABLED)
        assertEquals(1, nextDay.sentTodayCount)
        assertEquals("2026-01-02", nextDay.sentTodayDate)

        // And it must survive a fresh SettingsManager, i.e. actually reach the store.
        assertEquals(nextDay, SettingsManager(env, store, NoImageCodec).spontaneousStateFor(ENABLED))
    }

    @Test
    fun `recording one persona spares another`() {
        settings.deleteSpontaneousState(ENABLED)
        settings.deleteSpontaneousState(DISABLED)
        settings.recordSpontaneousSent(ENABLED, at = 5_000L, todayDate = "2026-01-01")
        settings.recordSpontaneousSent(DISABLED, at = 6_000L, todayDate = "2026-01-01")

        assertEquals(5_000L, settings.spontaneousStateFor(ENABLED).lastSentAt)
        assertEquals(6_000L, settings.spontaneousStateFor(DISABLED).lastSentAt)

        settings.deleteSpontaneousState(ENABLED)
        assertEquals(
            "deleting one entry must leave the map intact",
            6_000L, settings.spontaneousStateFor(DISABLED).lastSentAt
        )
        assertEquals(SpontaneousState(), settings.spontaneousStateFor(ENABLED))
    }

    // -------------------------------------------------- the service-level lifecycle wiring

    @Test
    fun `a deletion through the real removePersona drops the spontaneous state with it`() {
        saveProbe(DELETE, enabled = true, window = windowFarFromNow())
        armSpontaneousWork(env, DELETE, slot = 0, delayMillis = HOUR_MS)
        assertTrue("precondition: the probe must hold a live chain", scheduler.isArmed(DELETE))
        settings.recordSpontaneousSent(DELETE, at = 9_000L, todayDate = "2026-01-01")
        assertEquals(
            "precondition: the probe really carries state",
            9_000L, settings.spontaneousStateFor(DELETE).lastSentAt
        )

        val index = service.personas.value.indexOfFirst { it.name == DELETE }
        assertTrue("the added probe must be in the service's list", index >= 0)
        service.removePersona(index)

        assertTrue(
            "the deleted persona's state entry must die with it",
            waitFor { settings.spontaneousStateFor(DELETE) == SpontaneousState() }
        )
        assertFalse("the state map must not carry the deleted persona", DELETE in settings.getSpontaneousStates())
        assertTrue("and its chain must be torn down with it", waitFor { !scheduler.isArmed(DELETE) })
    }

    @Test
    fun `a rename through the real updatePersona carries the spontaneous state to the new name`() {
        saveProbe(RENAME_OLD, enabled = true, window = windowFarFromNow())
        armSpontaneousWork(env, RENAME_OLD, slot = 0, delayMillis = HOUR_MS)
        assertTrue("precondition: the probe must hold a live chain", scheduler.isArmed(RENAME_OLD))
        settings.recordSpontaneousSent(RENAME_OLD, at = 5_000L, todayDate = "2026-03-03")
        settings.recordSpontaneousSent(RENAME_OLD, at = 6_000L, todayDate = "2026-03-03")
        val before = settings.spontaneousStateFor(RENAME_OLD)
        assertEquals("precondition: the probe really has pacing to lose", 2, before.sentTodayCount)

        val index = service.personas.value.indexOfFirst { it.name == RENAME_OLD }
        assertTrue("the added probe must be in the service's list", index >= 0)
        service.updatePersona(index, service.personas.value[index].copy(name = RENAME_NEW))

        // The migration itself is synchronous inside updatePersona's rename branch.
        val moved = settings.spontaneousStateFor(RENAME_NEW)
        assertEquals("lastSentAt must survive the rename", before.lastSentAt, moved.lastSentAt)
        assertEquals("and the daily counter", before.sentTodayCount, moved.sentTodayCount)
        assertEquals("and the date that counter belongs to", before.sentTodayDate, moved.sentTodayDate)
        assertEquals("the old key must be gone", SpontaneousState(), settings.spontaneousStateFor(RENAME_OLD))
        assertTrue("the old name's chain must be disarmed", waitFor { !scheduler.isArmed(RENAME_OLD) })
    }

    // -------------------------------------------------- the import reconciliation

    @Test
    fun `an import that changes the gap re-arms the chain fresh`() {
        saveProbe(ENABLED, enabled = true, window = windowFarFromNow())
        // Capture the backup BEFORE touching the service.
        val export = settings.exportBackup()
        armSpontaneousWork(env, ENABLED, slot = 0, delayMillis = HOUR_MS)
        assertTrue("precondition: the probe must hold a live chain before the import", scheduler.isArmed(ENABLED))
        val armsBeforeImport = scheduler.arms.count { it.personaName == ENABLED }

        // This device's own payload, with only the probe's gap changed (15 → 45).
        assertTrue(
            "precondition: the probe must carry the gap being edited",
            Regex("\"name\":\"$ENABLED\".*?\"spontaneousGapMinutes\":15").containsMatchIn(export)
        )
        val modified = Regex("(\"name\":\"$ENABLED\".*?\"spontaneousGapMinutes\":)15")
            .replace(export) { it.groupValues[1] + "45" }
        assertTrue(service.importBackup(modified))
        // Non-vacuity on the import side: the restore really landed the new gap.
        assertEquals(
            "the restored probe must carry the imported gap", 45,
            settings.getPersonas()!!.first { it.name == ENABLED }.spontaneousGapMinutes
        )

        // The reconciliation is async (service coroutines), so poll. The identity upstream
        // checked through WorkInfo ids is here a FRESH arm: the pre-import schedule is
        // replaced, not left to finish its stale cycle.
        assertTrue(
            "the import must leave a fresh arm in place",
            waitFor {
                scheduler.isArmed(ENABLED) &&
                    scheduler.arms.count { it.personaName == ENABLED } > armsBeforeImport
            }
        )
    }

    @Test
    fun `an import that disables a persona disarms its chain immediately`() {
        saveProbe(ENABLED, enabled = true, window = windowFarFromNow())
        val export = settings.exportBackup()
        armSpontaneousWork(env, ENABLED, slot = 0, delayMillis = HOUR_MS)
        assertTrue("precondition: the probe must be armed before the import", scheduler.isArmed(ENABLED))

        assertTrue(
            "precondition: the probe must carry the toggle being flipped",
            Regex("\"name\":\"$ENABLED\".*?\"spontaneousEnabled\":true").containsMatchIn(export)
        )
        val modified = Regex("(\"name\":\"$ENABLED\".*?\"spontaneousEnabled\":)true")
            .replace(export) { it.groupValues[1] + "false" }
        assertTrue(service.importBackup(modified))
        assertFalse(
            "the restored probe must really be switched off",
            settings.getPersonas()!!.first { it.name == ENABLED }.spontaneousEnabled
        )

        // Without the reconciliation this chain stays armed and only ends when its next run
        // fires, finds the persona switched off, and self-cancels — one stale execution away.
        assertTrue(
            "the chain must be gone, not left to fire once and self-cancel",
            waitFor { !scheduler.isArmed(ENABLED) }
        )
    }
}
