package com.nikosm.voiceassistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The Settings list's one-line description of the page. */
internal const val LOREBOOK_SETTINGS_SUMMARY = "Background entries a persona can inject"

/** Debounce for the editor's field auto-save, the same rhythm as the skill editor. */
internal const val LOREBOOK_EDITOR_SAVE_DEBOUNCE_MS = 500L

internal const val LOREBOOK_NO_CONTENT_WARNING = "No content — this entry will never inject"
internal const val LOREBOOK_NO_KEYWORDS_WARNING = "No keywords — this entry will never fire"

/** One keyword per line, the editor's wire format. The inverse of [parseLorebookKeywords]. */
internal fun formatLorebookKeywords(keywords: List<String>): String = keywords.joinToString("\n")

internal fun parseLorebookKeywords(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

internal fun clampLorebookScanDepth(value: Int): Int =
    value.coerceIn(1, LOREBOOK_MAX_SCAN_DEPTH)

/** The line under an entry in the editor; red when the entry is legal but inert. */
internal fun lorebookEntrySummary(entry: LorebookEntry): String = when {
    entry.content.isBlank() -> LOREBOOK_NO_CONTENT_WARNING
    entry.activation == LorebookActivation.KEYWORDS && entry.keywords.none { it.isNotBlank() } ->
        LOREBOOK_NO_KEYWORDS_WARNING
    entry.activation == LorebookActivation.ALWAYS -> "Always active"
    else -> "Keywords: ${entry.keywords.filter { it.isNotBlank() }.joinToString(", ")}"
}

/**
 * M18.1's library page. Like Skills, the editor replaces the list in the Settings content area;
 * neither surface owns a scroller, because the category page's LazyColumn already is one.
 */
@Composable
fun LorebookSettings(service: AssistantService) {
    val books by service.lorebooks.collectAsState()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = books.firstOrNull { it.id == editingId }

    if (editing == null) {
        if (editingId != null) editingId = null
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (books.isEmpty()) {
                Text(
                    text = "Lorebooks are user-authored background entries. Give an entry " +
                        "content and either an always-on rule or a few keywords, then switch " +
                        "the book on for a persona: matching turns get it as context.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            books.forEach { book ->
                LorebookRow(
                    book = book,
                    onEdit = { editingId = book.id },
                    onToggle = { enabled ->
                        service.lorebooks.value.indexOfFirst { it.id == book.id }.takeIf { it >= 0 }
                            ?.let { index -> service.updateLorebook(index, book.copy(enabled = enabled)) }
                    },
                    onDelete = { service.removeLorebook(book) },
                )
            }
            NewLorebookRow {
                val book = Lorebook(name = "New Lorebook")
                service.addLorebook(book)
                editingId = book.id
            }
        }
    } else {
        LorebookEditor(
            book = editing,
            service = service,
            onClose = { editingId = null },
        )
    }
}

@Composable
private fun LorebookRow(
    book: Lorebook,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onEdit),
            ) {
                Text(book.name.ifBlank { "Untitled lorebook" }, fontWeight = FontWeight.Bold)
                Text(
                    text = "${book.entries.size} entr${if (book.entries.size == 1) "y" else "ies"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Switch(checked = book.enabled, onCheckedChange = onToggle)
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(Icons.Default.Delete, "Delete lorebook", tint = Color.Red, modifier = Modifier.size(18.dp))
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this lorebook?") },
            text = { Text("Its entries are removed, and every persona that enabled it stops receiving them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewLorebookRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "New lorebook",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LorebookEditor(
    book: Lorebook,
    service: AssistantService,
    onClose: () -> Unit,
) {
    var name by remember(book.id) { mutableStateOf(book.name) }
    var enabled by remember(book.id) { mutableStateOf(book.enabled) }
    var entries by remember(book.id) { mutableStateOf(book.entries) }

    LaunchedEffect(name, enabled, entries) {
        delay(LOREBOOK_EDITOR_SAVE_DEBOUNCE_MS)
        val index = service.lorebooks.value.indexOfFirst { it.id == book.id }
        if (index >= 0) {
            service.updateLorebook(index, Lorebook(id = book.id, name = name, enabled = enabled, entries = entries))
        }
    }

    fun updateEntry(entry: LorebookEntry) {
        entries = entries.map { if (it.id == entry.id) entry else it }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Text("${entries.size} / $LOREBOOK_MAX_ENTRIES_PER_BOOK entries", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Lorebook name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        entries.forEach { entry ->
            LorebookEntryEditor(
                entry = entry,
                onChange = ::updateEntry,
                onDelete = { entries = entries.filterNot { it.id == entry.id } },
            )
        }
        if (entries.size < LOREBOOK_MAX_ENTRIES_PER_BOOK) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    entries = entries + LorebookEntry(name = "New entry")
                },
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add entry", color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            Text(
                "A lorebook holds at most $LOREBOOK_MAX_ENTRIES_PER_BOOK entries.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LorebookEntryEditor(
    entry: LorebookEntry,
    onChange: (LorebookEntry) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
        border = BorderStroke(
            1.dp,
            if (entry.content.isBlank() || (entry.activation == LorebookActivation.KEYWORDS && entry.keywords.none { it.isNotBlank() })) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = entry.name,
                    onValueChange = { onChange(entry.copy(name = it)) },
                    label = { Text("Entry name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(6.dp))
                Switch(checked = entry.enabled, onCheckedChange = { onChange(entry.copy(enabled = it)) })
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete entry", tint = Color.Red, modifier = Modifier.size(18.dp))
                }
            }
            OutlinedTextField(
                value = entry.content,
                onValueChange = { onChange(entry.copy(content = it.take(LOREBOOK_MAX_ENTRY_CHARS))) },
                label = { Text("Content") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
            Text(
                text = "${entry.content.length} / $LOREBOOK_MAX_ENTRY_CHARS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LorebookActivation.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = entry.activation == option,
                        onClick = { onChange(entry.copy(activation = option)) },
                        shape = SegmentedButtonDefaults.itemShape(index, LorebookActivation.entries.size),
                    ) {
                        Text(if (option == LorebookActivation.ALWAYS) "Always" else "Keywords")
                    }
                }
            }
            if (entry.activation == LorebookActivation.KEYWORDS) {
                OutlinedTextField(
                    value = formatLorebookKeywords(entry.keywords),
                    onValueChange = { onChange(entry.copy(keywords = parseLorebookKeywords(it))) },
                    label = { Text("Keywords, one per line") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Case sensitive", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Switch(checked = entry.caseSensitive, onCheckedChange = { onChange(entry.copy(caseSensitive = it)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scan last", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = entry.scanDepth.toString(),
                    onValueChange = { raw ->
                        raw.filter { it.isDigit() }.toIntOrNull()?.let { onChange(entry.copy(scanDepth = clampLorebookScanDepth(it))) }
                    },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.width(6.dp))
                Text("messages", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = lorebookEntrySummary(entry),
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.content.isBlank() || (entry.activation == LorebookActivation.KEYWORDS && entry.keywords.none { it.isNotBlank() })) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The persona editor's Lorebooks section: every book with a switch. Unlike Skills there is no
 * "add" dialog — the library is small, and a switch is the whole interaction.
 *
 * A brand-new persona is not in the service list yet, so its side map has no key to write under;
 * the section says to save it first rather than silently accepting a toggle that would vanish.
 */
@Composable
fun PersonaLorebookSection(
    personaName: String,
    service: AssistantService,
) {
    val books by service.lorebooks.collectAsState()
    val personas by service.personas.collectAsState()
    var enabledIds by remember(personaName) { mutableStateOf(service.personaLorebooks(personaName)) }
    val exists = personas.any { it.name == personaName }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!exists) {
            Text(
                "Save this persona first, then switch its lorebooks on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            return@Column
        }
        if (books.isEmpty()) {
            Text(
                "No lorebooks yet — create them in Settings, then switch them on here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            return@Column
        }
        books.forEach { book ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(book.name.ifBlank { "Untitled lorebook" }, fontWeight = FontWeight.Bold)
                    Text(
                        "${book.entries.size} entr${if (book.entries.size == 1) "y" else "ies"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                Switch(
                    checked = book.id in enabledIds,
                    onCheckedChange = { on ->
                        enabledIds = if (on) (enabledIds + book.id).distinct() else enabledIds - book.id
                        service.setLorebookEnabledForPersona(personaName, book.id, on)
                    },
                )
            }
        }
    }
}
