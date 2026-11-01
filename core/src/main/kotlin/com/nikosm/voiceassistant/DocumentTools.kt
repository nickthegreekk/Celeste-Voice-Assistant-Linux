package com.nikosm.voiceassistant

import java.nio.file.Path

/**
 * M18.3: the document seam. `:core` decides what a PDF means; `:platform` runs the tools that
 * read one. The split is AGENTS.md §4's rule: `ProcessBuilder` may appear only in `:platform`,
 * and a test must be able to answer a PDF without poppler installed.
 *
 * Every method returns null for "the tool is missing or refused", never throws for that case:
 * the caller turns null into a sentence the user can act on. A malformed document is a null
 * from the command, not a crash in a chat turn.
 */
interface DocumentTools {
    /** The PDF's text, page-separated by a form feed, or null when poppler cannot read it. */
    suspend fun pdfText(path: Path): String?

    /** The PDF's page count, or null when `pdfinfo` is unavailable or unreadable. */
    suspend fun pdfPageCount(path: Path): Int?

    /** One page rendered as PNG bytes at [dpi], or null when `pdftoppm` cannot render it. */
    suspend fun renderPdfPage(path: Path, page: Int, dpi: Int): ByteArray?

    companion object {
        /** The refusal a build without the platform implementation gets. */
        val NONE: DocumentTools = object : DocumentTools {
            override suspend fun pdfText(path: Path): String? = null
            override suspend fun pdfPageCount(path: Path): Int? = null
            override suspend fun renderPdfPage(path: Path, page: Int, dpi: Int): ByteArray? = null
        }
    }
}
