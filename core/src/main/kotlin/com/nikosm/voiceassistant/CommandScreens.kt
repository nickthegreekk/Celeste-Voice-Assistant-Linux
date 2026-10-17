package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Settings → Terminal (PLAN.md §10/M16): the switch, the folder the commands start in, and the
 * audit of what has run.
 *
 * **The copy here is part of the feature, not decoration.** A shell cannot be path-contained, so
 * the screen has to say plainly that commands run with the user's own privileges and are not
 * limited to the folder. The folder is a working directory, and the dialog per command is the
 * boundary that actually exists.
 */

/** The settings row's one line. Static, like Filesystem's, for the same reason. */
internal const val COMMAND_SETTINGS_SUMMARY = "Run shell commands — every one asks first"

internal val CommandToolMode.label: String
    get() = when (this) {
        CommandToolMode.OFF -> "Off"
        CommandToolMode.ASK -> "Ask first"
    }

internal const val COMMAND_AUDIT_ROWS = 20

/**
 * The global page. There is no per-skill or per-workspace control beyond the persona override in
 * the persona editor, because commands are a per-persona grant in the same sense the filesystem
 * mode is.
 */
@Composable
internal fun CommandSettings(service: AssistantService, contentPadding: Int = 16) {
    var mode by remember { mutableStateOf(service.settingsManager.getCommandMode()) }
    var audit by remember { mutableStateOf(emptyList<CommandAuditEntry>()) }
    val workspace = service.fsWorkspaceSetting()

    LaunchedEffect(mode) {
        audit = readCommandAudit(service.appEnv.stateDir, COMMAND_AUDIT_ROWS)
    }

    // No scroll of its own: this page is an item of the settings shell's LazyColumn, and a lazy
    // item is measured with unbounded height (the Files screen's measure-time crash, PLAN.md §10).
    Column(
        modifier = Modifier.fillMaxWidth().padding(contentPadding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Terminal commands", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Celeste can run a shell command for you. Every command is shown to you, with its exact " +
                "text and folder, and runs only after you approve it — a refusal means nothing ran. " +
                "Commands run with your own permissions and are NOT limited to the folder below; " +
                "treat the confirmation as the boundary, because it is the only one there is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Text("Policy", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CommandToolMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = {
                        mode = option
                        service.setCommandModeSetting(option)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, CommandToolMode.entries.size),
                ) { Text(option.label) }
            }
        }
        Text(
            if (mode.allowsCommands) {
                "A persona may override this in its own editor (Global / Off / Ask first)."
            } else {
                "Commands are disabled everywhere. Turn this on to let the assistant propose one."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )

        Spacer(Modifier.height(4.dp))
        Text("Working directory", style = MaterialTheme.typography.labelLarge)
        Text(
            workspace ?: "No folder chosen. Commands need one — choose it in Settings → Filesystem.",
            style = MaterialTheme.typography.bodySmall,
            color = if (workspace == null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Commands start in the filesystem workspace folder. The folder is a starting point, not " +
                "a limit. The filesystem tools' own Off/read-only/read-write mode does not decide " +
                "whether commands are available — only this page and the persona override do.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )

        if (mode.allowsCommands) {
            Spacer(Modifier.height(8.dp))
            Text("Recent commands", style = MaterialTheme.typography.labelLarge)
            if (audit.isEmpty()) {
                Text(
                    "Nothing has run yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                audit.forEach { entry -> CommandAuditRow(entry) }
            }
            Text(
                "Recorded in command-audit.jsonl under the state directory. The command line and " +
                    "the outcome are kept; the output is not.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun CommandAuditRow(entry: CommandAuditEntry) {
    val command = entry.command.lineSequence().firstOrNull().orEmpty().take(160)
    Text(
        "$command  —  ${entry.result} (${entry.confirmed})",
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
 * [PersonaFsSection]'s: null is "follow the global", not "off", because a persona that was never
 * configured should inherit what the user set once.
 */
@Composable
internal fun PersonaCommandSection(
    personaMode: CommandToolMode?,
    onModeChange: (CommandToolMode?) -> Unit,
    service: AssistantService,
) {
    val options: List<CommandToolMode?> = listOf(null) + CommandToolMode.entries
    val global = service.settingsManager.getCommandMode()
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
                !effective.allowsCommands -> "This persona cannot run commands."
                service.fsWorkspaceSetting() == null -> "This persona can ask to run commands, but no " +
                    "working directory is chosen yet — set one in Settings → Filesystem."
                else -> "Commands start in ${service.fsWorkspaceSetting()}, and every one asks you first."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * The last line of defence: the exact command line, its folder, and two answers.
 *
 * Unlike the file-write dialog, there is no "always allow this folder" — only "this exact command
 * for this session". A shell can do anything, so granting a folder would grant every command
 * anyone can write into it, which is the prompt-injection shape this dialog exists to stop.
 *
 * **Dismissal denies**, and so does the gate's timeout when nobody is there to answer.
 */
@Composable
internal fun CommandApprovalDialog(
    request: CommandApprovalRequest,
    onAnswer: (allow: Boolean, trustForSession: Boolean) -> Unit,
) {
    var trust by remember(request.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onAnswer(false, false) },
        title = { Text("Run a command?") },
        text = {
            Column {
                Text(
                    "${request.personaName} wants to run:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    request.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "In: ${request.workingDirectory}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "It runs with your permissions and can reach beyond that folder.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = trust, onCheckedChange = { trust = it })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Run this exact command without asking again this session",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true, trust) }) { Text("Run") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false, false) }) { Text("Cancel") }
        },
    )
}
