package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M18.1's pure half: which entries fire, why, and what the injected block can and cannot contain.
 *
 * The activation rule is deterministic and therefore testable without a model: a keyword either
 * appears in the window or it does not.
 */
class LorebookTest {

    private fun entry(
        name: String = "Entry",
        content: String = "CONTENT",
        activation: LorebookActivation = LorebookActivation.KEYWORDS,
        keywords: List<String> = listOf("Vee"),
        caseSensitive: Boolean = false,
        scanDepth: Int = LOREBOOK_DEFAULT_SCAN_DEPTH,
        enabled: Boolean = true,
    ) = LorebookEntry(
        name = name,
        content = content,
        activation = activation,
        keywords = keywords,
        caseSensitive = caseSensitive,
        scanDepth = scanDepth,
        enabled = enabled,
    )

    private fun book(
        id: String = "book",
        name: String = "World",
        entries: List<LorebookEntry> = listOf(entry()),
        enabled: Boolean = true,
    ) = Lorebook(id = id, name = name, entries = entries, enabled = enabled)

    @Test
    fun `always fires with no matching text`() {
        val activated = activatedLorebookEntries(
            listOf(book(entries = listOf(entry(activation = LorebookActivation.ALWAYS)))),
            listOf("book"),
            listOf("nothing relevant"),
        )

        assertEquals(1, activated.size)
        assertEquals("always", activated.single().reason)
    }

    @Test
    fun `a keyword matches case-insensitively by default, and the current text is in the window`() {
        val books = listOf(book())

        assertEquals(1, activatedLorebookEntries(books, listOf("book"), listOf("VEE appears")).size)
        assertEquals(1, activatedLorebookEntries(books, listOf("book"), listOf("vee appears")).size)
        assertTrue(activatedLorebookEntries(books, listOf("book"), listOf("someone else")).isEmpty())
    }

    @Test
    fun `case sensitivity is honoured when asked`() {
        val books = listOf(book(entries = listOf(entry(caseSensitive = true))))

        assertEquals(1, activatedLorebookEntries(books, listOf("book"), listOf("Vee appears")).size)
        assertTrue(activatedLorebookEntries(books, listOf("book"), listOf("vee appears")).isEmpty())
    }

    @Test
    fun `scan depth counts the most recent messages, current turn included`() {
        val books = listOf(book(entries = listOf(entry(scanDepth = 2))))

        // The keyword is three messages back; the two-message window is the next two.
        assertTrue(
            activatedLorebookEntries(
                books,
                listOf("book"),
                listOf("Vee appears", "unrelated", "current"),
            ).isEmpty(),
        )
        assertEquals(
            1,
            activatedLorebookEntries(
                books,
                listOf("book"),
                listOf("unrelated", "Vee appears", "current"),
            ).size,
        )
    }

    @Test
    fun `a disabled entry, disabled book, or unselected book never fires`() {
        val disabledEntry = book(entries = listOf(entry(enabled = false)))
        val disabledBook = book(id = "off", enabled = false)
        val enabledBook = book(id = "on")

        assertTrue(activatedLorebookEntries(listOf(disabledEntry), listOf("book"), listOf("Vee")).isEmpty())
        assertTrue(activatedLorebookEntries(listOf(disabledBook), listOf("off"), listOf("Vee")).isEmpty())
        assertTrue(activatedLorebookEntries(listOf(enabledBook), listOf("other"), listOf("Vee")).isEmpty())
    }

    @Test
    fun `blank content and keyword entries with no keywords never fire`() {
        val blank = book(entries = listOf(entry(content = "  ")))
        val noKeywords = book(entries = listOf(entry(keywords = listOf("  "))))

        assertTrue(activatedLorebookEntries(listOf(blank), listOf("book"), listOf("Vee")).isEmpty())
        assertTrue(activatedLorebookEntries(listOf(noKeywords), listOf("book"), listOf("Vee")).isEmpty())
    }

    @Test
    fun `entries fire in library order then entry order`() {
        val books = listOf(
            book(id = "b1", name = "First", entries = listOf(entry(name = "one"), entry(name = "two"))),
            book(id = "b2", name = "Second", entries = listOf(entry(name = "three"))),
        )

        val names = activatedLorebookEntries(books, listOf("b1", "b2"), listOf("Vee"))
            .map { it.entry.name }

        assertEquals(listOf("one", "two", "three"), names)
    }

    @Test
    fun `the block is labelled and names only the entries it kept`() {
        val activated = activatedLorebookEntries(
            listOf(book(entries = listOf(entry(content = "MARKER", keywords = listOf("Vee"))))),
            listOf("book"),
            listOf("Vee"),
        )

        val turn = lorebookBlockForTurn(activated)

        assertTrue(turn.block.contains("## Lorebook"))
        assertTrue(turn.block.contains("[World / Entry]"))
        assertTrue(turn.block.contains("MARKER"))
        assertEquals(1, turn.used.size)
        assertEquals("lorebook", turn.used.single().kind)
        assertEquals("World / Entry", turn.used.single().name)
        assertEquals("keyword: Vee", turn.used.single().detail)
        assertFalse(turn.truncated)
    }

    @Test
    fun `the block respects its cap and reports what was left out`() {
        val activated = activatedLorebookEntries(
            listOf(
                book(
                    entries = listOf(
                        entry(name = "short", content = "A".repeat(40)),
                        entry(name = "long", content = "B".repeat(400)),
                    ),
                ),
            ),
            listOf("book"),
            listOf("Vee"),
        )

        // The header alone is ~150 chars; 260 admits the short entry and not the 400-char one.
        val turn = lorebookBlockForTurn(activated, maxChars = 260)

        assertTrue("block was ${turn.block.length} chars", turn.block.length <= 260)
        assertTrue(turn.truncated)
        assertEquals("only the entry that fit is recorded", 1, turn.used.size)
        assertEquals("short", turn.used.single().name.substringAfter(" / "))
    }

    @Test
    fun `names with newlines do not break the block shape`() {
        val activated = activatedLorebookEntries(
            listOf(book(name = "World\nBook", entries = listOf(entry(name = "A\nB")))),
            listOf("book"),
            listOf("Vee"),
        )

        val block = lorebookBlockForTurn(activated).block

        assertTrue(block.contains("[World Book / A B]"))
        assertFalse(block.contains("World\nBook"))
    }

    @Test
    fun `keyword editor helpers round-trip and cap`() {
        assertEquals(listOf("Vee", "Nikos"), parseLorebookKeywords(" Vee \n\n Nikos \n Vee "))
        assertEquals("Vee\nNikos", formatLorebookKeywords(listOf("Vee", "Nikos")))
        assertEquals(1, clampLorebookScanDepth(0))
        assertEquals(LOREBOOK_MAX_SCAN_DEPTH, clampLorebookScanDepth(999))
    }

    @Test
    fun `an entry summary names the inert states`() {
        assertEquals(LOREBOOK_NO_CONTENT_WARNING, lorebookEntrySummary(entry(content = " ")))
        assertEquals(
            LOREBOOK_NO_KEYWORDS_WARNING,
            lorebookEntrySummary(entry(keywords = listOf(" "))),
        )
        assertEquals(
            "Always active",
            lorebookEntrySummary(entry(activation = LorebookActivation.ALWAYS)),
        )
    }
}
