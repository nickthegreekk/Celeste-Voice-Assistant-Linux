package com.nikosm.voiceassistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * The transcript search dialog (PLAN.md §10/M13): one field, results as you type, newest first.
 *
 * Searched after a 250 ms pause rather than on every keystroke, because each search decodes the
 * saved transcripts. The dialog says where the search looks and that it is local; a result tap
 * opens the persona or room it belongs to — see [openTranscriptHit] for what "open" means and its
 * one limitation (no scroll-to-message).
 */
@Composable
internal fun TranscriptSearchDialog(service: AssistantService, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TranscriptHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(250)
        results = service.searchTranscripts(query)
        searched = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search chats") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Words to find") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Every saved persona, room and main-chat message. Local only — nothing is sent " +
                        "anywhere.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                when {
                    !searched -> Text(
                        "Type to search; results appear as you type.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    results.isEmpty() -> Text(
                        "No transcripts match \"$query\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {
                        Text(
                            "${results.size} match(es), newest first",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Column(
                            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            results.forEach { hit ->
                                TranscriptHitRow(hit) {
                                    service.openTranscriptHit(hit)
                                    onDismiss()
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TranscriptHitRow(hit: TranscriptHit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            "${hit.kind.label} · ${hit.sourceName} · ${hit.speaker}${timeSuffix(hit.timestamp)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
    }
}

private fun timeSuffix(timestamp: Long): String =
    if (timestamp <= 0L) {
        ""
    } else {
        " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
    }
