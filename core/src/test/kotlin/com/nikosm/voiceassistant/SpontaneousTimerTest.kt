package com.nikosm.voiceassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam 10's timer, on virtual time.
 *
 * The three things it has to get right are the three the ported callers depend on: a run fires at
 * the instant it was armed for and *with the slot it was armed with* (the slot is how a re-arm
 * avoids cancelling its own run — and it is only meaningful if it survives the round trip), a
 * second arm for a persona replaces the first rather than adding a second chain, and `isArmed`
 * answers true for the whole life of a run. That last one is the guard `refreshSpontaneousSchedule`
 * reads before arming: an application opened hourly must not restart a four-hour countdown, and a
 * timer that went quiet the moment it fired would let a settings refresh arm a duplicate.
 *
 * `runCurrent`, never `advanceUntilIdle`, as in TtsChunkSequencerTest: "advance until nothing is
 * left to run" would spin to the timeout while a delivery that is deliberately waiting stays
 * waiting.
 */
class SpontaneousTimerTest {

    private class RecordingLogger : AppLogger {
        val warnings = mutableListOf<String>()

        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
            if (level == LogLevel.WARN) warnings += message
        }
    }

    private val logger = RecordingLogger()
    private val delivered = mutableListOf<Pair<String, Int>>()

    /** A timer attached to the test's own scope, which is what makes the delays virtual. */
    private fun TestScopeTimer(scope: kotlinx.coroutines.CoroutineScope, deliver: suspend (String, Int) -> Unit = { p, s -> delivered += p to s }) =
        SpontaneousTimer(logger).also { it.attach(scope, deliver) }

    @Test
    fun `a run fires after its delay, carrying its persona and its slot`() = runTest {
        val timer = TestScopeTimer(this)
        timer.arm("Celeste", 0, 4_000)

        advanceTimeBy(3_999)
        runCurrent()
        assertEquals("nothing fires early", emptyList<Pair<String, Int>>(), delivered)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Celeste" to 0), delivered)
    }

    @Test
    fun `the slot survives the round trip, because it is the caller's re-arm rule`() = runTest {
        // The delivery arms `alternateSpontaneousSlot(slot)`, so a run that arrived with the wrong
        // slot would arm the one it is occupying and cancel itself.
        val timer = TestScopeTimer(this)
        timer.arm("Celeste", 1, 10)

        advanceTimeBy(11)
        runCurrent()

        assertEquals(listOf("Celeste" to 1), delivered)
        assertEquals(0, alternateSpontaneousSlot(1))
        assertEquals(1, alternateSpontaneousSlot(0))
    }

    @Test
    fun `a second arm for a persona replaces the first, it does not add a chain`() = runTest {
        val timer = TestScopeTimer(this)
        timer.arm("Celeste", 0, 1_000)
        advanceTimeBy(500)
        timer.arm("Celeste", 1, 1_000)

        // The first would have fired at 1000; the replacement fires at 1500, and only once.
        advanceTimeBy(600)
        runCurrent()
        assertEquals(emptyList<Pair<String, Int>>(), delivered)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("Celeste" to 1), delivered)
    }

    @Test
    fun `personas do not interfere with each other`() = runTest {
        val timer = TestScopeTimer(this)
        timer.arm("Celeste", 0, 100)
        timer.arm("Friend", 0, 200)

        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("Celeste" to 0), delivered)

        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("Celeste" to 0, "Friend" to 0), delivered)
    }

    @Test
    fun `armed is true while a run is pending and while it is delivering, false once it is over`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val timer = TestScopeTimer(this) { _, _ -> gate.await() }

        assertFalse("nothing armed yet", timer.isArmed("Celeste"))
        timer.arm("Celeste", 0, 10)
        assertTrue("a pending run is armed", timer.isArmed("Celeste"))

        advanceTimeBy(11)
        runCurrent()
        assertTrue("a run in flight still counts as armed — the guard reads this", timer.isArmed("Celeste"))

        gate.complete(Unit)
        runCurrent()
        assertFalse("the run is over, so whoever it armed holds the chain", timer.isArmed("Celeste"))
    }

    @Test
    fun `a disposal cancels the pending run and says so`() = runTest {
        val timer = TestScopeTimer(this)
        timer.arm("Celeste", 0, 100)
        timer.disarm("Celeste")
        assertFalse(timer.isArmed("Celeste"))

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("a disarmed chain never speaks", emptyList<Pair<String, Int>>(), delivered)
    }

    @Test
    fun `arming before delivery is attached arms nothing, and says why`() = runTest {
        // The window between `AppEnv` existing and the service attaching is real: settings load and
        // the safety net run inside `start()`. A chain that reported itself armed with no timer
        // behind it is the failure this project has already paid for once — a plausible wrong answer
        // instead of an honest one.
        val timer = SpontaneousTimer(logger)
        timer.arm("Celeste", 0, 100)

        assertFalse(timer.isArmed("Celeste"))
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings.single().contains("before delivery was attached"))
    }

    @Test
    fun `a delivery that throws does not take the timer with it`() = runTest {
        // A transport failure inside the run is the turn's business and is caught there, but the
        // timer must survive one that escapes: the chain's next arm is what keeps a persona
        // speaking, and a dead timer would silence it with no log line anywhere.
        var calls = 0
        val timer = TestScopeTimer(this) { _, _ ->
            calls++
            if (calls == 1) throw IllegalStateException("boom")
        }

        timer.arm("Celeste", 0, 10)
        advanceTimeBy(11)
        runCurrent()
        assertFalse("the failed run released its slot", timer.isArmed("Celeste"))

        timer.arm("Celeste", 1, 10)
        advanceTimeBy(11)
        runCurrent()
        assertEquals(2, calls)
    }
}
