package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Settings → Screen (PLAN.md §10/M19): the switch, the audit, the persona override and the
 * per-capture confirmation.
 *
 * **The copy here is part of the feature.** A screenshot contains everything on the display,
 * including windows the user did not have in mind; the page and the dialog have to say that
 * plainly, and say where the image goes (the persona's local vision model, nowhere else).
 */

/** The settings row's one line. Static, like Filesystem's and Terminal's, for the same reason. */
internal const val SCREEN_SETTINGS_SUMMARY = "Look at your screen — every capture asks first"

internal val ScreenToolMode.label: String
    get() = when (this) {
        ScreenToolMode.OFF -> "Off"
        ScreenToolMode.ASK -> "Ask first"
    }

internal const val SCREEN_AUDIT_ROWS = 20

@Composable
internal fun ScreenSettings(service: AssistantService, contentPadding: Int = 16) {
    var mode by remember { mutableStateOf(service.settingsManager.getScreenMode()) }
    var audit by remember { mutableStateOf(emptyList<ScreenAuditEntry>()) }

    LaunchedEffect(mode) {
        audit = readScreenAudit(service.appEnv.stateDir, SCREEN_AUDIT_ROWS)
    }

    // No scroll of its own: this page is an item of the settings shell's LazyColumn, and a lazy
    // item is measured with unbounded height (the Files screen's measure-time crash, PLAN.md §10).
    Column(
        modifier = Modifier.fillMaxWidth().padding(contentPadding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Screen vision", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Celeste can look at your screen when you ask a question about what is visible. A " +
                "screenshot captures the ENTIRE display — every window, not only the one you " +
                "mean. Each capture is shown to you and happens only after you approve it, and " +
                "the image goes to the vision model on the persona's Ollama server: only its " +
                "written description comes back, and nothing is added to the chat or written to disk.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Text("Policy", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ScreenToolMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = {
                        mode = option
                        service.setScreenModeSetting(option)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ScreenToolMode.entries.size),
                ) { Text(option.label) }
            }
        }
        Text(
            if (mode.allowsScreen) {
                "A persona may override this in its own editor (Global / Off / Ask first). The " +
                    "tool works only for a direct-Ollama persona whose model can see images; a " +
                    "cloud or gateway-only persona is not offered it."
            } else {
                "Screen access is disabled everywhere. Turn this on to let the assistant ask to look."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )

        if (mode.allowsScreen) {
            Spacer(Modifier.height(8.dp))
            Text("Recent captures", style = MaterialTheme.typography.labelLarge)
            if (audit.isEmpty()) {
                Text(
                    "Nothing has looked at the screen yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                audit.forEach { entry -> ScreenAuditRow(entry) }
            }
            Text(
                "Recorded in screen-capture-audit.jsonl under the state directory. The question " +
                    "and the outcome are kept; the screenshot and the description are not.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * The one-line audit row: the question (first line, bounded), the outcome, the capture size and
 * how it was approved. Pure so the copy is pinned by a test rather than by a screenshot.
 */
internal fun screenAuditSummary(entry: ScreenAuditEntry): String {
    val question = entry.question.lineSequence().firstOrNull().orEmpty().take(160)
    val size = if (entry.bytes > 0) "${entry.bytes / 1024} KiB, " else ""
    return "$question  —  ${entry.result} ($size${entry.confirmed})"
}

@Composable
private fun ScreenAuditRow(entry: ScreenAuditEntry) {
    Text(
        screenAuditSummary(entry),
        style = MaterialTheme.typography.bodySmall,
        color = if (entry.result == "ok") {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

/**
 * The persona editor's three-way row: Global (inherit), Off, Ask first. The semantics mirror
 * [PersonaCommandSection]'s: null is "follow the global", not "off".
 */
@Composable
internal fun PersonaScreenSection(
    personaMode: ScreenToolMode?,
    onModeChange: (ScreenToolMode?) -> Unit,
    service: AssistantService,
) {
    val options: List<ScreenToolMode?> = listOf(null) + ScreenToolMode.entries
    val global = service.settingsManager.getScreenMode()
    val effective = personaMode ?: global
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = personaMode == option,
                    onClick = { onModeChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(option?.label ?: "Global") }
            }
        }
        Text(
            when {
                !effective.allowsScreen -> "This persona cannot look at your screen."
                else -> "Works only when this persona talks to a direct Ollama server whose model " +
                    "can see images. Every capture asks you first, and the whole screen is captured."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * The last line of defence before a screenshot: who is asking, what for, and where the image goes.
 *
 * There is no "always allow": a screen changes between captures, so a grant made about what was
 * on it a moment ago would be answering a question the user has not been asked. **Dismissal
 * denies**, and so does the gate's timeout when nobody is there to answer.
 */
@Composable
internal fun ScreenApprovalDialog(
    request: ScreenApprovalRequest,
    onAnswer: (allow: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Look at your screen?") },
        text = {
            Column {
                Text(
                    "${request.personaName} wants to capture your entire screen and send it to " +
                        "the vision model on your Ollama server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text("It is looking for:", style = MaterialTheme.typography.labelSmall)
                Text(
                    request.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Every window is captured, including private ones. The screenshot is used " +
                            "for this answer only — it is not added to the chat or written to disk.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text("Look") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text("Cancel") }
        },
    )
}
