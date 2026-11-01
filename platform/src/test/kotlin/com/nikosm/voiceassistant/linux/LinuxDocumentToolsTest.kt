package com.nikosm.voiceassistant.linux

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M18.3's real platform reader, against poppler and a PDF the test writes itself.
 *
 * The fixture is fabricated here (five tiny objects and an xref) rather than checked in, so no
 * user document is ever in the repository. The test is skipped on a machine without poppler —
 * the production path reports that case as a sentence.
 */
class LinuxDocumentToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun popplerInstalled(): Boolean =
        Files.isExecutable(Path.of("/usr/bin/pdftotext")) &&
            Files.isExecutable(Path.of("/usr/bin/pdfinfo")) &&
            Files.isExecutable(Path.of("/usr/bin/pdftoppm"))

    @Test
    fun `poppler reads the text, counts the page and renders it`() = runTest {
        assumeTrue("poppler-utils is not installed", popplerInstalled())
        val file = temp.newFile("probe.pdf").toPath()
        Files.write(file, simplePdf("Hello PDF"))

        val tools = LinuxDocumentTools(temp.root.toPath(), RecordingLogger())

        val text = tools.pdfText(file)
        assertNotNull("pdftotext returned null", text)
        assertTrue("missing the text: $text", text!!.contains("Hello PDF"))
        assertEquals(1, tools.pdfPageCount(file))

        val png = tools.renderPdfPage(file, page = 1, dpi = 72)
        assertNotNull("pdftoppm returned null", png)
        assertTrue("not a PNG: ${png!!.take(8)}", png!!.size > 8 && png[0] == 0x89.toByte() && png[1] == 'P'.code.toByte())
    }

    /**
     * A minimal one-page PDF. No library and no checked-in fixture: the text stream and the five
     * objects are enough for poppler, and the xref offsets are computed as the bytes are written.
     */
    private fun simplePdf(text: String): ByteArray {
        val stream = "BT /F1 24 Tf 72 720 Td ($text) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun write(value: String) = out.write(value.toByteArray(Charsets.ISO_8859_1))

        write("%PDF-1.4\n")
        objects.forEachIndexed { index, body ->
            offsets += out.size()
            write("${index + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n")
        write("0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}
