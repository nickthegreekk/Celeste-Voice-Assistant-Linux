package com.nikosm.voiceassistant

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown export (2026-10-20).
 *
 * Two halves, tested separately because they have different failure modes. The formatter is pure:
 * its tests pin the document's shape — speaker headings, the legacy-timestamp omission, errors as
 * blockquotes, and image links — with a fixed clock and zone so the expected text is exact. The
 * writer owns the filesystem: its tests prove images land in a sibling folder with relative links,
 * and that a missing attachment is named in the document instead of failing the export.
 */
class ConversationExportTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `the formatter renders speakers, times, images and an error blockquote`() {
        val markdown = conversationToMarkdown(
            title = "Conversation with Celeste",
            messages = listOf(
                ExportMessage("You", "Hello", false, at("2026-10-19T20:14:00Z")),
                ExportMessage(
                    speaker = "Celeste",
                    text = "Hi there.\nSecond line.",
                    isError = false,
                    timestamp = at("2026-10-19T20:15:00Z"),
                    images = listOf(
                        ExportImage("Generated image", null, "chat.images/0002-generated-image-gen.png"),
                    ),
                ),
                ExportMessage("Celeste", "Could not reach the server.", true, at("2026-10-19T20:16:00Z")),
            ),
            exportedAt = at("2026-10-20T09:00:00Z"),
            zone = utc,
        )

        val expected = """
            # Conversation with Celeste

            _Exported from Celeste on 2026-10-20 09:00._

            ---

            ## You — 2026-10-19 20:14

            Hello

            ---

            ## Celeste — 2026-10-19 20:15

            Hi there.
            Second line.

            ![Generated image](chat.images/0002-generated-image-gen.png)

            ---

            ## Celeste — 2026-10-19 20:16

            > **Error:** Could not reach the server.
        """.trimIndent()

        assertEquals(expected, markdown.trimEnd())
    }

    @Test
    fun `a legacy message renders without a fabricated time`() {
        // timestamp 0 means unknown; the model's own note says omit rather than invent a time.
        val markdown = conversationToMarkdown(
            title = "Chat",
            messages = listOf(ExportMessage("You", "old message", false, timestamp = 0)),
            exportedAt = at("2026-10-20T09:00:00Z"),
            zone = utc,
        )

        assertTrue("the heading must still name the speaker: $markdown", "## You\n" in markdown)
        assertFalse("no time may be invented for a legacy message: $markdown", "## You —" in markdown)
    }

    @Test
    fun `the two message models map to the same export shape`() {
        val chat = ChatMessage(
            role = "assistant",
            text = "Here is a picture.",
            imagePath = "/tmp/attached.png",
            generatedImagePath = "/tmp/generated.png",
            timestamp = 5,
        )
        val exported = chat.toExportMessage("Celeste")
        assertEquals("Celeste", exported.speaker)
        assertEquals(listOf("Attached image", "Generated image"), exported.images.map { it.label })
        assertEquals("/tmp/attached.png", exported.images[0].source.toString())

        assertEquals(
            "the human is always You",
            "You",
            RoomMessage(speakerName = RoomMessage.USER_SPEAKER, text = "hi").toExportMessage().speaker,
        )
        assertEquals(
            "a persona keeps its name",
            "Ada",
            RoomMessage(speakerName = "Ada", text = "hi").toExportMessage().speaker,
        )
    }

    @Test
    fun `the writer copies images beside the document and links them relatively`() {
        val root = TempRoots.newRoot("celeste-export")
        val attached = root.resolve("photo one.png").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val generated = root.resolve("gen.png").also { Files.write(it, byteArrayOf(4, 5)) }
        val destination = root.resolve("chat.md")

        val count = writeConversationMarkdown(
            destination = destination,
            title = "Chat",
            messages = listOf(
                ExportMessage("You", "look at this", false, 1, listOf(ExportImage("Attached image", attached))),
                ExportMessage("Celeste", "nice", false, 2, listOf(ExportImage("Generated image", generated))),
            ),
            exportedAt = at("2026-10-20T09:00:00Z"),
            zone = utc,
        )
        val markdown = Files.readString(destination)

        assertEquals(2, count)
        assertTrue(
            "the attachment must be a relative link into the sibling folder: $markdown",
            "![Attached image](chat.images/0001-attached-image-photo_one.png)" in markdown,
        )
        assertTrue(
            "the generated image must be there too: $markdown",
            "![Generated image](chat.images/0002-generated-image-gen.png)" in markdown,
        )
        assertTrue(
            "the copied attachment must exist",
            Files.isRegularFile(root.resolve("chat.images/0001-attached-image-photo_one.png")),
        )
        assertTrue(
            "the copied generated image must exist",
            Files.isRegularFile(root.resolve("chat.images/0002-generated-image-gen.png")),
        )
    }

    @Test
    fun `a missing image is named in the document instead of failing the export`() {
        val root = TempRoots.newRoot("celeste-export")
        val destination = root.resolve("chat.md")

        writeConversationMarkdown(
            destination = destination,
            title = "Chat",
            messages = listOf(
                ExportMessage(
                    speaker = "You",
                    text = "the file is gone",
                    isError = false,
                    timestamp = 1,
                    images = listOf(ExportImage("Attached image", root.resolve("gone.png"))),
                ),
            ),
            exportedAt = at("2026-10-20T09:00:00Z"),
            zone = utc,
        )

        val markdown = Files.readString(destination)
        assertTrue("the transcript must still be written: $markdown", "the file is gone" in markdown)
        assertTrue(
            "the lost attachment must be named: $markdown",
            "*[Attached image could not be exported]*" in markdown,
        )
    }

    @Test
    fun `default file names are conservative`() {
        assertEquals("Celeste", exportFileSlug("Celeste"))
        assertEquals("a-b-c", exportFileSlug("a/b c"))
        assertEquals("chat", exportFileSlug("   "))
    }

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()
}
