package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ADAPTED FROM UPSTREAM — deliberately not a copy, and for that reason not on
// `tools/sync-from-android.sh`'s TEST_SOURCES list. Upstream's `SpontaneousSchedulerTest.kt` was one
// file of 35 cases; 30 of them are here unchanged, and 5 are not, because they assert the
// *WorkManager* side of spontaneous delivery: `spontaneousWorkName`'s two-slot scheme and its
// enumerability, the channel and summary notification ids, and `ExistingWorkPolicy`. None of that
// exists in the port — PLAN.md §10 puts the scheduler in the core as a coroutine timer,
// `SpontaneousTimer.kt` needs exactly one line of the scheme (`alternateSpontaneousSlot`, whose
// involution `SpontaneousTimerTest` pins) and has no work names to check.
//
// What is left is what the port can run: the policy. Windows that wrap midnight, a one-sided window,
// the gap floor and its cap, the daily caps and the persisted counters, `nextSpontaneousAttempt`'s
// arithmetic, and the delivery preview's flattening and bound — the decision the delivery hands to
// `evaluateSpontaneousCycle`, which is the part of this feature that is upstream's behaviour rather
// than this port's glue. docs/TEST-MAP.md records the adaptation.
//
// Dropped, each because its subject is WorkManager rather than the policy:
//   - an edit that does not change the schedule leaves WorkManager alone
//   - delivery ids stay clear of the FGS status id
//   - delivery and status live on different channels
//   - a run never arms the slot name it occupies (the involution itself is pinned in
//     SpontaneousTimerTest, where the timer is what arms)
//   - slot names are per persona and both are enumerable

class SpontaneousSchedulerTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private val hourMs = 60 * 60 * 1000L

    /** Fixed instant, so no assertion can ever depend on when the suite runs. */
    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun persona(
        enabled: Boolean = true,
        gapMinutes: Int = 60,
        windowStart: Int? = null,
        windowEnd: Int? = null,
        maxPerDay: Int = 6
    ) = Persona(
        name = "Bob",
        themeColor = Color(0xFF4ADE80),
        model = "[HomeBox] llama3",
        systemPrompt = "You are a warm friend.",
        isCloud = false,
        spontaneousEnabled = enabled,
        spontaneousGapMinutes = gapMinutes,
        spontaneousWindowStartMin = windowStart,
        spontaneousWindowEndMin = windowEnd,
        spontaneousMaxPerDay = maxPerDay
    )

    private fun request(
        now: Long,
        persona: Persona? = persona(),
        state: SpontaneousState = SpontaneousState(),
        eligibility: RoomEligibility = RoomEligibility.Eligible(cloud = false),
        globalSentToday: Int = 0,
        globalMaxPerDay: Int = SpontaneousPolicy.GLOBAL_MAX_PER_DAY
    ) = SpontaneousCycleRequest(
        now = now,
        zoneId = zone,
        personaExists = persona != null,
        spontaneousEnabled = persona?.spontaneousEnabled == true,
        eligibility = eligibility,
        state = state,
        gapMillis = SpontaneousPolicy.effectiveGapMinutes(persona?.spontaneousGapMinutes ?: 0) * 60_000L,
        maxPerDay = SpontaneousPolicy.effectiveMaxPerDay(persona?.spontaneousMaxPerDay ?: 0),
        globalSentToday = globalSentToday,
        globalMaxPerDay = globalMaxPerDay,
        windowStartMin = persona?.spontaneousWindowStartMin,
        windowEndMin = persona?.spontaneousWindowEndMin
    )

    // ------------------------------------------------------------------ window

    @Test fun `no window configured means any time of day`() {
        assertTrue(isInSpontaneousWindow(0, null, null))
        assertTrue(isInSpontaneousWindow(743, null, null))
        assertTrue(isInSpontaneousWindow(1439, null, null))
    }

    @Test fun `a same-day window includes both of its endpoints`() {
        val start = 9 * 60
        val end = 21 * 60
        assertFalse("before opening", isInSpontaneousWindow(start - 1, start, end))
        assertTrue("exactly at opening", isInSpontaneousWindow(start, start, end))
        assertTrue("exactly at closing", isInSpontaneousWindow(end, start, end))
        assertFalse("after closing", isInSpontaneousWindow(end + 1, start, end))
    }

    @Test fun `an overnight window wraps past midnight`() {
        // 22:00 -> 06:00. This is the branch whose absence makes evening windows silently
        // never fire, so it is asserted on both sides of the wrap.
        val start = 22 * 60
        val end = 6 * 60
        assertTrue("late evening", isInSpontaneousWindow(23 * 60, start, end))
        assertTrue("just after midnight", isInSpontaneousWindow(30, start, end))
        assertTrue("just before closing", isInSpontaneousWindow(end, start, end))
        assertFalse("midday", isInSpontaneousWindow(12 * 60, start, end))
        assertFalse("just before opening", isInSpontaneousWindow(start - 1, start, end))
    }

    @Test fun `a one-sided window only constrains the bound that is set`() {
        assertTrue(isInSpontaneousWindow(18 * 60, 9 * 60, null))
        assertFalse(isInSpontaneousWindow(8 * 60, 9 * 60, null))
        assertTrue(isInSpontaneousWindow(5 * 60, null, 21 * 60))
        assertFalse(isInSpontaneousWindow(22 * 60, null, 21 * 60))
    }

    @Test fun `start equal to end is the whole day rather than an off switch`() {
        assertTrue(isInSpontaneousWindow(0, 0, 0))
        assertTrue(isInSpontaneousWindow(13 * 60 + 7, 60, 60))
    }

    // ------------------------------------------------------------------ time helpers

    @Test fun `minute-of-day and today are read in the injected zone`() {
        // 2026-01-01T23:30Z is already 2026-01-02 in Asia/Tokyo — proving the helper is
        // zone-driven and not silently using the host default.
        val instant = at("2026-01-01", 23, 30)
        assertEquals(23 * 60 + 30, spontaneousMinuteOfDay(instant, zone))
        assertEquals("2026-01-01", spontaneousToday(instant, zone))
        assertEquals("2026-01-02", spontaneousToday(instant, ZoneId.of("Asia/Tokyo")))
    }

    @Test fun `next window open lands on today when the window has not started yet`() {
        assertEquals(at("2026-01-01", 9, 0), nextWindowOpen(at("2026-01-01", 7, 0), zone, 9 * 60))
    }

    @Test fun `next window open rolls to tomorrow once today's opening has passed`() {
        assertEquals(at("2026-01-02", 9, 0), nextWindowOpen(at("2026-01-01", 23, 0), zone, 9 * 60))
    }

    @Test fun `next window open with no configured start is the next midnight`() {
        assertEquals(
            at("2026-01-02", 0, 0),
            nextWindowOpen(at("2026-01-01", 20, 15), zone, null)
        )
    }

    @Test fun `the daily reset waits for midnight and then respects the window`() {
        // Cap reached inside a daytime window: midnight is out of hours, so the re-arm is
        // tomorrow's opening rather than midnight itself.
        assertEquals(
            at("2026-01-02", 9, 0),
            nextDailyReset(at("2026-01-01", 15, 0), zone, 9 * 60, 21 * 60)
        )
        // Cap reached inside an OVERNIGHT window: midnight is still in hours, so the
        // persona may resume the moment the date rolls.
        assertEquals(
            at("2026-01-02", 0, 0),
            nextDailyReset(at("2026-01-01", 23, 30), zone, 22 * 60, 6 * 60)
        )
    }

    // ------------------------------------------------------------------ no catch-up

    @Test fun `an already-elapsed gap collapses onto now instead of pointing into the past`() {
        // The phone was asleep since 2026-01-01 with a 1-hour gap: by 2026-01-03 the gap
        // elapsed thousands of times over, yet the next attempt is NOW, not a backlog of
        // missed slots. There is no slot list to replay, structurally.
        val lastSent = at("2026-01-01", 8, 0)
        val now = at("2026-01-03", 10, 0)
        val next = nextSpontaneousAttempt(
            lastAttemptAt = lastSent, gapMillis = hourMs, now = now,
            zoneId = zone, windowStartMin = null, windowEndMin = null
        )
        assertEquals(now, next)
        assertTrue("the next attempt must never be in the past", next >= now)
    }

    @Test fun `a running gap returns the moment the gap actually expires`() {
        val lastSent = at("2026-01-01", 8, 0)
        val now = at("2026-01-01", 8, 20)
        assertEquals(
            lastSent + hourMs,
            nextSpontaneousAttempt(
                lastAttemptAt = lastSent, gapMillis = hourMs, now = now,
                zoneId = zone, windowStartMin = null, windowEndMin = null
            )
        )
    }

    @Test fun `a gap that would expire out of hours is pushed to the next window opening`() {
        // Gap elapses at 21:30 for a 09:00-21:00 window: the correct next attempt is
        // tomorrow's opening, not 21:30.
        val next = nextSpontaneousAttempt(
            lastAttemptAt = at("2026-01-01", 20, 30), gapMillis = hourMs,
            now = at("2026-01-01", 21, 0),
            zoneId = zone, windowStartMin = 9 * 60, windowEndMin = 21 * 60
        )
        assertEquals(at("2026-01-02", 9, 0), next)
    }

    @Test fun `re-arming from a fresh attempt schedules one full gap ahead`() {
        // Passing the attempt time as the base is how silence, failure and a first-ever
        // send all avoid re-firing immediately.
        val attemptedAt = at("2026-01-01", 12, 0)
        assertEquals(
            attemptedAt + hourMs,
            nextSpontaneousAttempt(
                lastAttemptAt = attemptedAt, gapMillis = hourMs, now = attemptedAt,
                zoneId = zone, windowStartMin = null, windowEndMin = null
            )
        )
    }

    // ------------------------------------------------------------------ decision ordering

    @Test fun `a deleted persona cancels even when every schedule says go`() {
        val now = at("2026-01-01", 12, 0)
        val decision = evaluateSpontaneousCycle(
            request(now = now, persona = null, state = SpontaneousState(lastSentAt = now - 10 * hourMs))
        )
        assertTrue(
            "a missing persona must end the chain, not be re-armed for later",
            decision is SpontaneousDecision.Cancel
        )
    }

    @Test fun `the toggle being off cancels even when the persona is eligible and due`() {
        val now = at("2026-01-01", 12, 0)
        val decision = evaluateSpontaneousCycle(
            request(now = now, persona = persona(enabled = false))
        )
        assertTrue(decision is SpontaneousDecision.Cancel)
    }

    @Test fun `an ineligibility cancels rather than postponing`() {
        val now = at("2026-01-01", 12, 0)
        val decision = evaluateSpontaneousCycle(
            request(
                now = now,
                eligibility = RoomEligibility.Blocked(SpontaneousPolicy.CLOUD_BLOCK_REASON)
            )
        )
        assertTrue(
            "becoming cloud must STOP the timer, not wait it out",
            decision is SpontaneousDecision.Cancel
        )
        assertEquals(SpontaneousPolicy.CLOUD_BLOCK_REASON, (decision as SpontaneousDecision.Cancel).reason)
    }

    @Test fun `outside the window postpones to the opening even though the gap has elapsed`() {
        // Ordering: window is consulted before the gap, so a due persona after hours is
        // pushed to tomorrow's opening instead of firing now (a 09:00-21:00 window).
        val now = at("2026-01-01", 23, 0)
        val decision = evaluateSpontaneousCycle(
            request(
                now = now,
                persona = persona(windowStart = 9 * 60, windowEnd = 21 * 60),
                state = SpontaneousState(lastSentAt = now - 10 * hourMs)
            )
        )
        val notDue = decision as? SpontaneousDecision.NotDue ?: fail(decision, "NotDue")
        assertEquals(at("2026-01-02", 9, 0), notDue.nextAttemptAt)
    }

    @Test fun `inside the window a running gap postpones to the exact expiry`() {
        val lastSent = at("2026-01-01", 10, 0)
        val now = lastSent + 20 * 60 * 1000L
        val decision = evaluateSpontaneousCycle(request(now = now, state = SpontaneousState(lastSentAt = lastSent)))
        val notDue = decision as? SpontaneousDecision.NotDue ?: fail(decision, "NotDue")
        assertEquals(lastSent + hourMs, notDue.nextAttemptAt)
    }

    @Test fun `a reached daily cap postpones to the next date and is not a cancellation`() {
        val now = at("2026-01-01", 12, 0)
        val decision = evaluateSpontaneousCycle(
            request(
                now = now,
                state = SpontaneousState(lastSentAt = now - 10 * hourMs, sentTodayCount = 6,
                    sentTodayDate = "2026-01-01")
            )
        )
        val notDue = decision as? SpontaneousDecision.NotDue ?: fail(decision, "NotDue")
        assertEquals("the budget refills tomorrow, so the timer must survive", at("2026-01-02", 0, 0), notDue.nextAttemptAt)
    }

    @Test fun `the daily cap resets when the local date rolls even though the count is untouched`() {
        val now = at("2026-01-02", 12, 0)
        val decision = evaluateSpontaneousCycle(
            request(
                now = now,
                state = SpontaneousState(lastSentAt = now - 10 * hourMs, sentTodayCount = 6,
                    sentTodayDate = "2026-01-01")
            )
        )
        assertTrue("yesterday's count must not block today", decision is SpontaneousDecision.Proceed)
    }

    @Test fun `the global budget blocks every persona at once without cancelling anyone`() {
        val now = at("2026-01-01", 12, 0)
        val decision = evaluateSpontaneousCycle(
            request(now = now, globalSentToday = SpontaneousPolicy.GLOBAL_MAX_PER_DAY)
        )
        val notDue = decision as? SpontaneousDecision.NotDue
            ?: fail(decision, "NotDue — exhausting the shared budget must never stop the feature")
        assertEquals(at("2026-01-02", 0, 0), notDue.nextAttemptAt)
    }

    @Test fun `every gate satisfied means proceed`() {
        val now = at("2026-01-01", 12, 0)
        assertTrue(
            evaluateSpontaneousCycle(
                request(now = now, state = SpontaneousState(lastSentAt = now - 2 * hourMs))
            ) is SpontaneousDecision.Proceed
        )
    }

    // ------------------------------------------------------------------ schedule diffing

    @Test fun `every schedule input is detected as a change`() {
        val before = persona()
        assertTrue("toggle", spontaneousScheduleDiffers(before, before.copy(spontaneousEnabled = false)))
        assertTrue("gap", spontaneousScheduleDiffers(before, before.copy(spontaneousGapMinutes = 90)))
        assertTrue("window start", spontaneousScheduleDiffers(before, before.copy(spontaneousWindowStartMin = 8 * 60)))
        assertTrue("window end", spontaneousScheduleDiffers(before, before.copy(spontaneousWindowEndMin = 20 * 60)))
        assertTrue("cap", spontaneousScheduleDiffers(before, before.copy(spontaneousMaxPerDay = 2)))
        assertTrue("model", spontaneousScheduleDiffers(before, before.copy(model = "[HomeBox] mistral")))
        // The unique-work key IS the name, so a rename orphans the chain.
        assertTrue("rename", spontaneousScheduleDiffers(before, before.copy(name = "Robert")))
    }

    // ------------------------------------------------------------------ jitter

    @Test fun `jitter stays within its bound and never leaves the window`() {
        val base = at("2026-01-01", 20, 58)
        val random = Random(42L)
        repeat(200) {
            val jittered = withSpontaneousJitter(base, zone, 9 * 60, 21 * 60, random)
            assertTrue("jitter must never run backwards", jittered >= base)
            assertTrue("jitter must stay under its cap", jittered < base + SPONTANEOUS_JITTER_MAX_MS)
            if (jittered != base) {
                assertTrue(
                    "jitter may not push a send out of hours",
                    isInSpontaneousWindow(spontaneousMinuteOfDay(jittered, zone), 9 * 60, 21 * 60)
                )
            }
        }
    }

    @Test fun `jitter never carries a send past the closing time`() {
        // 20:59 with a window closing at 21:00: most samples would land out of hours and
        // must be discarded, so the send slips through only while it is still in bounds.
        val base = at("2026-01-01", 20, 59)
        val random = Random(7L)
        var kept = 0
        repeat(200) {
            val jittered = withSpontaneousJitter(base, zone, 9 * 60, 21 * 60, random)
            if (jittered != base) kept++
            assertTrue(
                "jitter may not push a send out of hours",
                isInSpontaneousWindow(spontaneousMinuteOfDay(jittered, zone), 9 * 60, 21 * 60)
            )
        }
        assertTrue("near closing, jitter should mostly be discarded", kept < 200)
    }

    // ------------------------------------------------------------------ target + failure

    @Test fun `the target parser matches the router's bracket form`() {
        val servers = listOf(ServerConfig(name = "HomeBox", url = "http://box:11434"))
        val direct = resolveSpontaneousTarget(persona(), servers)
        assertEquals("HomeBox", direct.serverName)
        assertEquals("llama3", direct.actualModel)
        assertEquals("http://box:11434", direct.baseUrl)

        // A bare model reaches Ollama only via the gateway, which is history-blind — and
        // spontaneousEligibility already blocks that shape, so there is nothing to probe.
        val bare = resolveSpontaneousTarget(persona().copy(model = "llama3"), servers)
        assertEquals(null, bare.baseUrl)

        // The server was deleted: eligibility would have caught this before we got here.
        val gone = resolveSpontaneousTarget(persona(), emptyList())
        assertEquals(null, gone.baseUrl)
    }

    @Test fun `failures are described as unreachable only when they are transport failures`() {
        assertTrue(describeSpontaneousFailure(java.io.IOException("failed to connect")).startsWith("unreachable"))
        assertEquals("Ollama error: 500", describeSpontaneousFailure(Exception("Ollama error: 500")))
        assertEquals("numCtx overflow", describeSpontaneousFailure(Exception("numCtx overflow")))
    }

    @Test fun `an unset cap falls back to the policy default rather than to unlimited`() {
        assertEquals(SpontaneousPolicy.DEFAULT_MAX_PER_DAY, SpontaneousPolicy.effectiveMaxPerDay(0))
        assertEquals(2, SpontaneousPolicy.effectiveMaxPerDay(2))
        assertTrue(
            "the global ceiling must be a real bound, not larger than the per-persona default",
            SpontaneousPolicy.GLOBAL_MAX_PER_DAY <= SpontaneousPolicy.DEFAULT_MAX_PER_DAY * 8
        )
    }

    // ------------------------------------------------------------------ notification shape

    // ---------------------------------------------------------------- the delivery preview

    /** Upstream's case, kept: `spontaneousNotificationPreview` is ported, so its rules are testable
     * here even though the notification that shows it is not. The bounded, flattened, one-line
     * preview is the whole of what a collapsed desktop card carries. */
    @Test fun `the collapsed preview stays one line and bounded`() {
        assertEquals("one short line", spontaneousNotificationPreview("one short line"))
        assertEquals(
            "first line only",
            spontaneousNotificationPreview("first line only\nsecond line here")
        )
        assertEquals(
            "squashed whitespace",
            spontaneousNotificationPreview("squashed   \t  whitespace")
        )
        val long = "word ".repeat(50).trim()
        val preview = spontaneousNotificationPreview(long)
        assertTrue("preview must be bounded, not the whole message", preview.length <= 120)
        assertTrue("a truncated preview must say it is truncated", preview.endsWith("…"))
        assertFalse("flattened first line, never a raw newline", '\n' in preview)
    }

    /**
     * Upstream's helper, kept verbatim: the decision cases assert on a sealed type, and this names
     * which variant was expected without every case writing its own message.
     */
    private fun fail(decision: SpontaneousDecision, expected: String): Nothing =
        throw AssertionError("expected $expected but was $decision")
}
