package com.nikosm.voiceassistant

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M18.3's pure document half: DOCX body text and the PDF page plan.
 *
 * The DOCX fixture is fabricated in the test — a tiny ZIP with `word/document.xml` — because a
 * real document would put the user's own content in the repository. The PDF plan is pure and
 * needs no poppler at all.
 */
class DocumentTextTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun writeDocx(path: Path, xml: String) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    @Test
    fun `docx body text keeps runs, paragraphs and tabs`() {
        val path = temp.newFile("probe.docx").toPath()
        writeDocx(
            path,
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> world</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second</w:t><w:tab/><w:t>tabbed</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """.trimIndent(),
        )

        assertEquals("Hello world\nSecond\ttabbed", docxText(path))
    }

    @Test
    fun `docx with no document entry is a document error`() {
        val path = temp.newFile("empty.docx").toPath()
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("docProps/core.xml"))
            zip.write("<x/>".toByteArray())
            zip.closeEntry()
        }

        val error = runCatching { docxText(path) }.exceptionOrNull()
        assertTrue("expected DocumentException, got $error", error is DocumentException)
    }

    @Test
    fun `a file that is not a zip is a document error`() {
        val path = temp.newFile("bad.docx").toPath()
        Files.writeString(path, "not a zip")

        val error = runCatching { docxText(path) }.exceptionOrNull()
        assertTrue("expected DocumentException, got $error", error is DocumentException)
    }

    @Test
    fun `pdf pages split on the form feed and short pages are OCR candidates`() {
        assertEquals(listOf("one", "two", ""), splitPdfPages("one\u000Ctwo\u000C"))
        assertFalse(shouldOcrPdfPage("x".repeat(PDF_OCR_TEXT_THRESHOLD)))
        assertTrue(shouldOcrPdfPage("short"))
    }

    @Test
    fun `the OCR budget goes to the first scanned pages and the page cap holds`() {
        val text = "x".repeat(40) + "\u000C" + "short" + "\u000C" + "short"
        val plan = planPdfPages(text, pageCount = 3, maxOcrPages = 1)

        assertEquals(3, plan.size)
        assertFalse(plan[0].needsOcr)
        assertTrue(plan[1].needsOcr)
        assertFalse("the budget is spent", plan[2].needsOcr)
        assertEquals(100, planPdfPages(text, pageCount = 1000).size)
    }

    @Test
    fun `a page with no text is planned as OCR when the budget allows`() {
        val plan = planPdfPages("", pageCount = 2, maxOcrPages = 2)

        assertEquals(2, plan.size)
        assertTrue(plan.all { it.needsOcr })
    }
}
