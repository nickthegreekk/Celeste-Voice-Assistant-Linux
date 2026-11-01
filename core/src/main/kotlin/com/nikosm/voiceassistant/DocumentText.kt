package com.nikosm.voiceassistant

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** Raised when an attachment is readable but its document format is not. */
internal class DocumentException(message: String) : Exception(message)

internal const val DOCX_MAX_XML_BYTES = 64 * 1024 * 1024
internal const val DOCUMENT_MAX_CHARS = 200_000
internal const val PDF_OCR_TEXT_THRESHOLD = 32
internal const val PDF_MAX_PAGES = 100
internal const val PDF_MAX_OCR_PAGES = 10
internal const val PDF_OCR_DPI = 150
internal const val OCR_MAX_CHARS_PER_PAGE = 20_000

/**
 * The instruction sent to the vision model for one page. It is deliberately a transcription
 * task, not a summary: the page's text is evidence the user's question is answered from, and a
 * model that editorialises turns OCR into a second, lossy answer.
 */
internal const val OCR_PROMPT =
    "You are an OCR engine. Transcribe every visible character in the image exactly, preserving " +
        "line breaks. Do not describe the image, summarise it, or add commentary. If there is no " +
        "text, return an empty string."

/** One page's plan: its extracted text and whether a vision model should be asked to read it. */
internal data class PdfPagePlan(
    val page: Int,
    val text: String,
    val needsOcr: Boolean,
)

/**
 * Split `pdftotext`'s output on the form feed it writes between pages. A trailing feed produces
 * an empty last element; callers index by page and treat a missing page as empty.
 */
internal fun splitPdfPages(text: String): List<String> = text.split('\u000C')

/** A page with almost no extractable text is a scan (or an image-only page) worth OCRing. */
internal fun shouldOcrPdfPage(pageText: String, threshold: Int = PDF_OCR_TEXT_THRESHOLD): Boolean =
    pageText.count { !it.isWhitespace() } < threshold

/**
 * Decide which pages get a vision pass, bounded by [maxOcrPages].
 *
 * The budget is assigned in page order, so a 200-page scan OCRs its first ten pages rather than
 * spending an unbounded amount of time on a document the user can narrow. The returned list is
 * exactly [pageCount] entries; text pages keep their extracted text.
 */
internal fun planPdfPages(
    text: String,
    pageCount: Int,
    maxOcrPages: Int = PDF_MAX_OCR_PAGES,
): List<PdfPagePlan> {
    val pages = splitPdfPages(text)
    val count = pageCount.coerceIn(0, PDF_MAX_PAGES)
    var ocrBudget = maxOcrPages.coerceAtLeast(0)
    return (1..count).map { page ->
        val pageText = pages.getOrNull(page - 1).orEmpty().trim()
        val needsOcr = shouldOcrPdfPage(pageText) && ocrBudget > 0
        if (needsOcr) ocrBudget--
        PdfPagePlan(page = page, text = pageText, needsOcr = needsOcr)
    }
}

/**
 * Extract a DOCX's body text with the JDK's ZIP and XML readers — no dependency, and no DTD or
 * external entity resolution. Paragraphs become newlines, tabs and breaks are preserved.
 *
 * A DOCX is a ZIP whose `word/document.xml` holds the runs. The entry is read with a hard cap
 * before parsing, because decompression is where a small malicious archive becomes a large one.
 */
internal fun docxText(path: Path): String {
    val xml = readZipEntry(path, "word/document.xml", DOCX_MAX_XML_BYTES)
        ?: throw DocumentException("This DOCX has no word/document.xml, so its text cannot be read.")
    return parseDocxXml(xml)
}

private fun readZipEntry(path: Path, entryName: String, maxBytes: Int): ByteArray? {
    try {
        ZipInputStream(Files.newInputStream(path)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name != entryName) continue
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = zip.read(chunk)
                    if (read < 0) break
                    if (out.size() + read > maxBytes) {
                        throw DocumentException("The DOCX is too large to extract (over ${maxBytes / (1024 * 1024)} MB).")
                    }
                    out.write(chunk, 0, read)
                }
                return out.toByteArray()
            }
        }
    } catch (e: DocumentException) {
        throw e
    } catch (e: Exception) {
        throw DocumentException("Could not read the DOCX: ${e.message ?: e.javaClass.simpleName}")
    }
}

/**
 * M18.3: turn a PDF into the text an attachment section carries.
 *
 * The text layer is read first. A page with almost no text is rendered and offered to the
 * persona's direct Ollama vision model via [ocrPng]; the page budget is assigned up front by
 * [planPdfPages], so a 200-page scan cannot turn one chat turn into an unbounded OCR job.
 *
 * A missing poppler, a missing vision model and a failed page are all a note in the returned
 * text, never a failed turn: the user still gets whatever text the document did yield.
 */
internal suspend fun AssistantService.extractPdfText(source: Path, persona: Persona?): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val text = documentTools.pdfText(source)
            ?: throw DocumentException(
                "PDF text extraction needs poppler's pdftotext. Install poppler-utils, then try again."
            )
        val pageCount = documentTools.pdfPageCount(source) ?: splitPdfPages(text).size
        val plan = planPdfPages(text, pageCount)
        if (plan.isEmpty()) throw DocumentException("The PDF has no readable pages.")

        val out = StringBuilder()
        var ocrUnavailable = false
        var truncated = false
        for (page in plan) {
            var pageText = page.text
            if (page.needsOcr) {
                val png = documentTools.renderPdfPage(source, page.page, PDF_OCR_DPI)
                val ocr = if (png != null && persona != null) ocrPng(png, persona) else null
                if (!ocr.isNullOrBlank()) {
                    pageText = ocr
                } else {
                    ocrUnavailable = true
                    if (pageText.isBlank()) pageText = "[No extractable text]"
                }
            }
            if (pageText.isBlank()) pageText = "[No readable text on this page.]"
            val section = "--- Page ${page.page} ---\n$pageText\n\n"
            if (out.length + section.length > DOCUMENT_MAX_CHARS) {
                truncated = true
                break
            }
            out.append(section)
        }
        if (pageCount > PDF_MAX_PAGES) {
            out.append("(Only the first $PDF_MAX_PAGES pages are read.)\n")
        }
        if (ocrUnavailable) {
            out.append(
                "(Some pages had little extractable text; OCR was unavailable for them. " +
                    "A direct Ollama vision model is needed.)\n"
            )
        }
        if (truncated) out.append("(Document truncated at the size cap.)\n")
        out.toString().trim()
    }

private fun parseDocxXml(xml: ByteArray): String {
    val factory = XMLInputFactory.newFactory()
    // A document is text, not a program: no DTD, no external entities.
    runCatching { factory.setProperty(XMLInputFactory.SUPPORT_DTD, false) }
    runCatching { factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false) }
    val out = StringBuilder()
    var inText = false
    try {
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(xml))
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "t" -> inText = true
                    "tab" -> out.append('\t')
                    "br" -> out.append('\n')
                }

                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (inText) out.append(reader.text)
                }

                XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                    "t" -> inText = false
                    "p" -> out.append('\n')
                }
            }
            if (out.length >= DOCUMENT_MAX_CHARS) {
                out.append("\n\n(Document truncated at the size cap.)")
                break
            }
        }
        reader.close()
    } catch (e: Exception) {
        throw DocumentException("Could not parse the DOCX text: ${e.message ?: e.javaClass.simpleName}")
    }
    return out.toString().trim()
}
