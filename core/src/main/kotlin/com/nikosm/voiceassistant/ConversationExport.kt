package com.nikosm.voiceassistant

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Markdown export for a conversation (2026-10-20, requested after the 0.2.0 review).
 *
 * The transcript already lives on disk as `chat.json` per persona and per room
 * ([ConversationFolders]), which is a storage format, not something a user can send to anyone.
 * This is the human-readable counterpart: one Markdown file, with the images beside it, produced
 * through the same save dialog the backup export uses.
 *
 * **The formatter is pure and separate from the writer.** [conversationToMarkdown] takes resolved
 * entries and a clock/zone, so the layout is testable without touching a filesystem; the writer
 * copies images and fills in their relative targets before calling it. The service-facing
 * extensions at the bottom are the only part that knows about coroutines and `notifyUser`.
 *
 * **Images are copied, not linked.** An absolute `~/.celeste-linux/<persona>/images/...` link in a
 * file the user emails to someone is a broken image and a leaked directory layout; a sibling
 * `<export>.images/` folder with relative links survives being moved. A file that was already
 * missing is named in the document rather than failing the whole export — the conversation is the
 * valuable part, and a lost attachment should not cost the user the transcript.
 *
 * **Reasoning is deliberately omitted.** `ChatMessage.reasoning` is model scratchpad, not the
 * conversation, and including it would roughly double the file for no reader's benefit.
 */

/** One speaker turn, in the shape both [ChatMessage] and [RoomMessage] can produce. */
internal data class ExportMessage(
    val speaker: String,
    val text: String,
    val isError: Boolean,
    /** Epoch millis; 0 means unknown (legacy messages) and renders no time, as the UI does. */
    val timestamp: Long,
    val images: List<ExportImage> = emptyList(),
)

/**
 * An image belonging to a message.
 *
 * [source] is the file to copy, or null when there is nothing to copy (a lost attachment). The
 * writer fills [target] with the Markdown link once the copy has succeeded; a null target means
 * the image is named in the document but not exported.
 */
internal data class ExportImage(
    val label: String,
    val source: Path?,
    val target: String? = null,
)

/** `ChatMessage`'s two image slots, in the order the UI shows them. */
internal fun ChatMessage.toExportMessage(assistantName: String): ExportMessage = ExportMessage(
    speaker = if (role == "user") "You" else assistantName.ifBlank { "Assistant" },
    text = text,
    isError = isError,
    timestamp = timestamp,
    images = buildList {
        imagePath?.takeIf { it.isNotBlank() }?.let {
            add(ExportImage("Attached image", runCatching { Path.of(it) }.getOrNull()))
        }
        generatedImagePath?.takeIf { it.isNotBlank() }?.let {
            add(ExportImage("Generated image", runCatching { Path.of(it) }.getOrNull()))
        }
    },
)

/** Rooms have no images; the human is the reserved [RoomMessage.USER_SPEAKER] and is called "You". */
internal fun RoomMessage.toExportMessage(): ExportMessage = ExportMessage(
    speaker = if (isUser) "You" else speakerName,
    text = text,
    isError = isError,
    timestamp = timestamp,
)

/**
 * Renders the whole conversation. [exportedAt] and [zone] are parameters rather than reads of the
 * system clock so a test can pin both; the production caller uses the defaults.
 *
 * The document is intentionally plain: a title, one line saying where it came from, and one
 * `##` section per turn separated by horizontal rules. No table of contents, no escaping of the
 * user's own Markdown — the words are theirs and should read as they wrote or received them.
 */
internal fun conversationToMarkdown(
    title: String,
    messages: List<ExportMessage>,
    exportedAt: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildString {
    appendLine("# ${title.ifBlank { "Conversation" }}")
    appendLine()
    appendLine("_Exported from Celeste on ${formatExportTime(exportedAt, zone)}._")
    for (message in messages) {
        appendLine()
        appendLine("---")
        appendLine()
        append("## ${message.speaker}")
        // Legacy messages decode with timestamp 0. Rendering "1970" or "now" would both be
        // fabrications; the model's own comment says omit, and the UI already does.
        if (message.timestamp > 0) append(" — ${formatExportTime(message.timestamp, zone)}")
        appendLine()
        appendLine()
        if (message.isError) {
            appendError(message.text)
        } else if (message.text.isNotBlank()) {
            appendLine(message.text)
        } else if (message.images.isEmpty()) {
            appendLine("_(empty message)_")
        }
        for (image in message.images) {
            appendLine()
            val target = image.target
            if (target != null) {
                appendLine("![${image.label}]($target)")
            } else {
                appendLine("*[${image.label} could not be exported]*")
            }
        }
    }
}

/**
 * Writes [messages] to [destination] as Markdown, copying images into `<name>.images/` beside it.
 *
 * Returns the number of messages written. Throws on a destination that cannot be written; a
 * single image that cannot be copied is a note in the document instead, because one missing file
 * is not a reason to lose the transcript.
 */
internal fun writeConversationMarkdown(
    destination: Path,
    title: String,
    messages: List<ExportMessage>,
    exportedAt: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    val prepared = prepareImages(destination, messages)
    Files.writeString(destination, conversationToMarkdown(title, prepared, exportedAt, zone))
    return messages.size
}

/**
 * A safe default file name: `celeste-<name>-<date>.md` is built by the UI from this. Anything
 * outside the conservative set becomes `_`, and an empty result falls back to `chat`.
 */
internal fun exportFileSlug(name: String): String {
    val slug = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_')
    return slug.take(60).ifBlank { "chat" }
}

// Locale.ROOT so the digits and the pattern letters do not follow a machine's locale: an export
// should read the same on every machine, and a locale that renders non-Latin digits would make the
// timestamps inconsistent with the rest of the document.
private val EXPORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)

private fun formatExportTime(epochMillis: Long, zone: ZoneId): String =
    EXPORT_TIME.format(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone))

/** Each line quoted, so a multi-line error stays one blockquote in any Markdown renderer. */
private fun StringBuilder.appendError(text: String) {
    val lines = text.lines()
    appendLine("> **Error:** ${lines.firstOrNull().orEmpty()}")
    for (line in lines.drop(1)) appendLine("> $line")
}

/**
 * Copies every image that exists into `<destination-base>.images/` and returns the messages with
 * their [ExportImage.target] set. Names carry the message index and the image's kind, so two
 * attachments with the same file name cannot overwrite each other in one export.
 */
private fun prepareImages(destination: Path, messages: List<ExportMessage>): List<ExportMessage> {
    if (messages.none { it.images.any { image -> image.source != null } }) return messages

    val parent = destination.toAbsolutePath().parent ?: return messages
    val fileName = destination.fileName?.toString().orEmpty().ifBlank { "conversation.md" }
    val base = fileName.substringBeforeLast('.', fileName)
    val folderName = "$base.images"
    val folder = parent.resolve(folderName)

    return try {
        Files.createDirectories(folder)
        messages.mapIndexed { index, message ->
            message.copy(
                images = message.images.map { image ->
                    val source = image.source
                    if (source == null || !Files.isRegularFile(source)) {
                        image
                    } else {
                        val targetName = "%04d-%s-%s".format(
                            index + 1,
                            imageLabelSlug(image.label),
                            safeImageFileName(source),
                        )
                        try {
                            Files.copy(source, folder.resolve(targetName), StandardCopyOption.REPLACE_EXISTING)
                            image.copy(target = "$folderName/$targetName")
                        } catch (e: Exception) {
                            // Unreadable source, no permission, a full disk: name it in the
                            // document and keep the transcript.
                            image
                        }
                    }
                },
            )
        }
    } catch (e: Exception) {
        // The folder itself could not be created. The Markdown still writes; every image will be
        // reported as not exported.
        messages
    }
}

private fun imageLabelSlug(label: String): String =
    label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "image" }

private fun safeImageFileName(source: Path): String {
    val name = source.fileName?.toString().orEmpty()
    val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.')
    return safe.takeLast(80).ifBlank { "image" }
}

/**
 * The service entry points. They run on [AssistantService.serviceScope] for the reason the backup
 * export does: closing the window must not cancel a write halfway and leave a truncated file.
 * Feedback goes through `notifyUser`, which the main screen already renders as a snackbar.
 */
internal fun AssistantService.exportChatMarkdown(
    target: Path,
    title: String,
    messages: List<ChatMessage>,
    assistantName: String,
) = exportConversationMarkdown(target, title, messages.map { it.toExportMessage(assistantName) })

internal fun AssistantService.exportRoomMarkdown(
    target: Path,
    title: String,
    messages: List<RoomMessage>,
) = exportConversationMarkdown(target, title, messages.map { it.toExportMessage() })

private fun AssistantService.exportConversationMarkdown(
    target: Path,
    title: String,
    messages: List<ExportMessage>,
) {
    if (messages.isEmpty()) {
        notifyUser("Nothing to export")
        return
    }
    serviceScope.launch {
        try {
            val count = withContext(Dispatchers.IO) {
                writeConversationMarkdown(target, title, messages)
            }
            notifyUser("Exported $count messages to ${target.fileName ?: target}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notifyUser("Export failed: ${e.message}")
        }
    }
}
