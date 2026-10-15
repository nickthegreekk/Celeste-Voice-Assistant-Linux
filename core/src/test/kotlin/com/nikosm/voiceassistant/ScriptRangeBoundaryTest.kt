package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where each script range starts and stops, to the code point.
 *
 * `detectScriptLanguage` decides which voice a reply is spoken in, and it decides it from
 * inclusive code-point ranges written as escape sequences. Several of those boundaries are
 * **unassigned** code points — U+3040, U+30FF, U+9FFF and U+D7AF among them — which is the point:
 * written as literals they would be invisible characters, and a tidy-up that replaced the escapes
 * with the characters they denote, or a paste that picked up a lookalike, would move a boundary
 * without the line looking any different. A wrong-language utterance is not a crash and would be
 * diagnosed by ear, months later.
 *
 * So this file pins, for every range, the first code point, the last code point, and one code
 * point on each side. It is not testing that Unicode ranges are inclusive; it is testing that
 * *these* ranges are *these* numbers.
 *
 * Upstream's `ScriptLanguageDetectionTest` — byte-identical in this module — covers the behaviour
 * with real text: kana beating a Han majority, a Latin preamble not crowding out the evidence, the
 * dominance threshold. This file covers none of that. It is the arithmetic.
 */
class ScriptRangeBoundaryTest {

    /**
     * Three of the same code point, because the detector refuses to commit on fewer: a dominance
     * count under 3 reads as noise and returns null. The kana short-circuit is exempt — see the
     * test for it below.
     */
    private fun scriptOf(codePoint: Int): String? =
        detectScriptLanguage(String(CharArray(3) { codePoint.toChar() }))

    private fun assertRange(name: String, lo: Int, hi: Int, justBelow: Int, justAbove: Int) {
        assertEquals("U+%04X is the first code point of %s".format(lo, name), name, scriptOf(lo))
        assertEquals("U+%04X is the last code point of %s".format(hi, name), name, scriptOf(hi))
        assertNull("U+%04X is below the %s range and belongs to no script".format(justBelow, name), scriptOf(justBelow))
        assertNull("U+%04X is above the %s range and belongs to no script".format(justAbove, name), scriptOf(justAbove))
    }

    @Test
    fun `Greek is exactly U+0370 to U+03FF`() {
        // U+0370 is GREEK CAPITAL LETTER HETA, the block's first assigned character; below it is
        // the tail of the combining-diacritics block.
        //
        // No `assertRange` here, and the reason is worth keeping: Cyrillic opens at U+0400, so
        // Greek and Russian are ADJACENT and there is no code point above Greek that belongs to no
        // script. Writing `justAbove = 0x0400 - 1` — which is U+03FF, inside Greek — asserts that
        // the last Greek code point is unassigned, and it was doing exactly that until this test
        // ran. Where two ranges touch, the neighbour's first code point IS the boundary check.
        assertEquals("U+0370 is the first code point of Greek", "Greek", scriptOf(0x0370))
        assertEquals("U+03FF is the last code point of Greek", "Greek", scriptOf(0x03FF))
        assertNull("U+036F is in the combining-diacritics block and belongs to no script", scriptOf(0x036F))
        assertEquals("U+0400 is Russian, not Greek — the ranges are adjacent", "Russian", scriptOf(0x0400))
    }

    @Test
    fun `Russian is exactly U+0400 to U+04FF`() {
        // Adjacent to Greek above, so the "below" probe would land inside Greek. U+0500 opens the
        // Cyrillic Supplement block, which this range deliberately does not cover.
        assertEquals("U+03FF is Greek, not Russian", "Greek", scriptOf(0x03FF))
        assertEquals("Russian", scriptOf(0x0400))
        assertEquals("Russian", scriptOf(0x04FF))
        assertNull("U+0500 is outside the range", scriptOf(0x0500))
    }

    @Test
    fun `Hebrew is exactly U+0590 to U+05FF`() {
        // Adjacent to Arabic above, exactly as Greek is to Russian — see that test for why the
        // neighbour's first code point is the check rather than a null probe.
        assertEquals("U+0590 is the first code point of Hebrew", "Hebrew", scriptOf(0x0590))
        assertEquals("U+05FF is the last code point of Hebrew", "Hebrew", scriptOf(0x05FF))
        assertNull("U+058F belongs to no script", scriptOf(0x058F))
        assertEquals("U+0600 is Arabic, not Hebrew — the ranges are adjacent", "Arabic", scriptOf(0x0600))
    }

    @Test
    fun `Arabic is exactly U+0600 to U+06FF`() {
        assertEquals("U+05FF is Hebrew, not Arabic", "Hebrew", scriptOf(0x05FF))
        assertEquals("Arabic", scriptOf(0x0600))
        assertEquals("Arabic", scriptOf(0x06FF))
        assertNull("U+0700 opens the Syriac block, outside the range", scriptOf(0x0700))
    }

    @Test
    fun `Hindi is exactly U+0900 to U+097F`() {
        assertRange("Hindi", lo = 0x0900, hi = 0x097F, justBelow = 0x08FF, justAbove = 0x0980)
    }

    @Test
    fun `Chinese is exactly U+4E00 to U+9FFF`() {
        assertRange("Chinese", lo = 0x4E00, hi = 0x9FFF, justBelow = 0x4DFF, justAbove = 0xA000)
    }

    @Test
    fun `Korean is exactly U+AC00 to U+D7AF`() {
        assertRange("Korean", lo = 0xAC00, hi = 0xD7AF, justBelow = 0xABFF, justAbove = 0xD7B0)
    }

    @Test
    fun `Japanese is the whole kana block, U+3040 to U+30FF`() {
        // The boundaries here are unassigned code points in the hiragana and katakana blocks —
        // no glyph at all. They are the clearest case for the escapes.
        assertRange("Japanese", lo = 0x3040, hi = 0x30FF, justBelow = 0x303F, justAbove = 0x3100)
    }

    @Test
    fun `a single kana is decisive even though it is under the dominance threshold`() {
        // The M3 fix, and the only branch that bypasses the ">= 3 characters" rule: ordinary
        // Japanese is frequently kanji-majority, so a count let Chinese outvote the kana and route
        // Japanese replies to a Mandarin voice. Kana are exclusive to Japanese, so one is enough.
        assertEquals("Japanese", detectScriptLanguage("あ"))
        assertEquals("Japanese", detectScriptLanguage("あ"))
        // And it wins over a Han majority, which is the case that was broken.
        assertEquals("Japanese", detectScriptLanguage("日本語のテキストです"))
    }

    @Test
    fun `fewer than three characters of a script is treated as noise rather than evidence`() {
        assertEquals("Chinese", scriptOf(0x4E00))
        assertNull("two is below the threshold", detectScriptLanguage("一一"))
        assertNull("and so is one", detectScriptLanguage("一"))
    }

    @Test
    fun `the boundaries are the code points, not whatever a literal might have been swallowed as`() {
        // The tidy-up this file exists to catch: replacing each escape with the character it
        // denotes. Identical code points, so this passes either way — which is the point. What it
        // pins is that the boundaries are where they say they are, so a *mistaken* literal, the
        // kind a paste produces, moves the range and fails here.
        for (boundary in listOf(0x3040, 0x30FF, 0x4E00, 0x9FFF, 0xAC00, 0xD7AF, 0x0900)) {
            val asLiteral = boundary.toChar()
            assertEquals(
                "the escape and the literal must denote the same code point",
                detectScriptLanguage(CharArray(3) { asLiteral }.concatToString()),
                scriptOf(boundary),
            )
        }
    }
}
