package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.nio.file.Path

/**
 * Settings → Filesystem (PLAN.md §10/M8): the folder the assistant may read and write in, and what
 * it may do there.
 *
 * **Off is the shipped state and the first option**, because the plan asks for exactly that: nothing
 * is reachable until the user names a path and picks a mode.
 *
 * The always-excluded paths are named here, in the screen, with their resolved absolute values —
 * *"the always-excluded paths are named in the UI rather than hidden"*, and the plan's reason is
 * worth having on screen rather than in a document: "read my notes" and "read my keys" are one word
 * apart, and that failure is silent, because the key would go into a prompt — possibly a cloud one.
 */
@Composable
internal fun FsToolSettings(service: AssistantService, contentPadding: Int = 16) {
    var mode by remember { mutableStateOf(service.fsModeSetting()) }
    var workspaceText by remember { mutableStateOf(service.fsWorkspaceSetting().orEmpty()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var audit by remember { mutableStateOf(emptyList<FsToolAuditEntry>()) }
    // Recomputed on every change rather than cached: a cache of "which paths may not be read" is the
    // kind of value that goes stale in the direction of allowing something.
    val excluded = service.fsExcludedPaths()

    LaunchedEffect(mode, workspaceText) {
        audit = readFsToolAudit(service.appEnv.stateDir, FS_AUDIT_ROWS)
    }

    // **No scroll of its own.** This page is rendered inside the settings shell's `LazyColumn`
    // item (`SettingsScreens.kt`), and a lazy item is measured with unbounded height — so a
    // `verticalScroll` here is measured against infinity and Compose throws
    // "Vertically scrollable component was measured with an infinity maximum height constraints".
    // It did, every time this page was opened, until the first live run that opened it. The shell
    // scrolls; a category page does not. `ModelPicker` records the same rule for the same reason.
    Column(
        modifier = Modifier.fillMaxWidth().padding(contentPadding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Filesystem tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Celeste can list and read files in one folder you choose, and — in read-write mode — write " +
                "them. It cannot run programs, and it never reaches outside this folder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            FsToolMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = {
                        mode = option
                        service.setFsModeSetting(option)
                        notice = null
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, FsToolMode.entries.size),
                ) { Text(option.label) }
            }
        }

        Text("Workspace folder", style = MaterialTheme.typography.labelLarge)
        Text(
            workspaceText.ifBlank { "No folder chosen — nothing is reachable." },
            style = MaterialTheme.typography.bodyMedium,
            color = if (workspaceText.isBlank()) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val picked = runCatching { service.fileDialogs.openDirectory("Choose the assistant's folder") }.getOrNull()
                if (picked != null) {
                    val refusal = service.fsWorkspaceRefusalFor(picked)
                    if (refusal != null) {
                        notice = refusal
                    } else {
                        workspaceText = picked.toString()
                        service.setFsWorkspaceSetting(picked.toString())
                        notice = null
                    }
                }
            }) { Text("Choose folder…") }
            if (workspaceText.isNotBlank()) {
                TextButton(onClick = {
                    workspaceText = ""
                    service.setFsWorkspaceSetting(null)
                    notice = null
                }) { Text("Clear") }
            }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // Named, in place, with the paths resolved — the guard the user can read.
        Surface(shape = RectangleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Always excluded, whatever the folder", style = MaterialTheme.typography.labelLarge)
                excluded.forEach { path ->
                    Text(path.toString(), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "These are your keys and the settings file that holds your API keys. Choosing a " +
                        "folder that contains one does not make it reachable.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        val trusted = service.fsWriteGate.trustedForSession()
        if (trusted.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Trusted for this session: ${trusted.joinToString { it.toString() }}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { service.fsWriteGate.forgetTrust(); notice = "Writes will ask again." }) {
                    Text("Stop trusting")
                }
            }
        }

        Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "The last ${FS_AUDIT_ROWS} operations, newest first. The full record is at " +
                "${service.appEnv.stateDir.resolve(FS_AUDIT_FILE)} and is a plain text file.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (audit.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            audit.forEach { entry -> FsAuditRow(entry) }
        }
    }
}

/**
 * The write confirmation: **what is about to change, in the user's own files**, and three answers —
 * allow once, allow and trust this folder for the session, or deny.
 *
 * The plan asks for a confirmation "showing the path and the change", and the change is the part
 * that matters: a poisoned document asking the model to overwrite something is caught by a person
 * reading the path and the sizes, which is the last line of defence this design has. So the dialog
 * shows the resolved path, create-or-replace, the old size against the new, and the first lines of
 * what would be written.
 *
 * **Dismissal denies.** A dialog closed by tapping outside it is not an approval, and the same is
 * true of the timeout behind it (see `FsWriteApprovalGate`).
 */
@Composable
internal fun FsWriteApprovalDialog(request: FsWriteRequest, onAnswer: (approve: Boolean, trustForSession: Boolean) -> Unit) {
    var trust by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onAnswer(false, false) },
        title = { Text(if (request.existed) "Replace a file?" else "Create a file?") },
        text = {
            Column {
                Text("${request.personaName} wants to write:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(request.resolved.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (request.existed) {
                        "${request.oldBytes} bytes would be replaced by ${request.newBytes}."
                    } else {
                        "${request.newBytes} bytes would be created."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("It would contain:", style = MaterialTheme.typography.labelSmall)
                Text(
                    request.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = trust, onCheckedChange = { trust = it })
                    Text("Allow writes in this folder for this session", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true, trust) }) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false, false) }) { Text("Cancel") }
        },
    )
}

/**
 * The persona editor's section: this persona's own answer, or the global one.
 *
 * **Null is "follow the global"** and is the default, which is why the four-way row starts there
 * rather than at Off: a persona that has never been configured should inherit what the user set once
 * in Settings, not silently opt out of it. Choosing any mode *pins* it for this persona, and the
 * folder then appears for it — the shape the image-generation entry picker already uses
 * ("follow the global" first, the specific choice after).
 *
 * Written through the service on every change, keyed by the editor's **live** name: the editor
 * rebuilds a whole `Persona` on save, so name-keyed state must not wait for that.
 */
@Composable
internal fun PersonaFsSection(
    personaMode: FsToolMode?,
    onModeChange: (FsToolMode?) -> Unit,
    workspace: String?,
    onWorkspaceChange: (String?) -> Unit,
    service: AssistantService,
) {
    val options: List<FsToolMode?> = listOf(null) + FsToolMode.entries
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
        val effective = resolveFsWorkspace(
            personaMode = personaMode,
            personaPath = workspace,
            globalMode = service.fsModeSetting(),
            globalPath = service.fsWorkspaceSetting(),
        )
        Text(
            when {
                effective == null -> "This persona cannot reach any files."
                else -> "This persona may ${if (effective.mode.allowsWrites) "read and write" else "read"} in ${effective.root}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (personaMode != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching { service.fileDialogs.openDirectory("Choose this persona's folder") }
                        .getOrNull()
                        ?.let { picked ->
                            if (service.fsWorkspaceRefusalFor(picked) == null) onWorkspaceChange(picked.toString())
                        }
                }) { Text(if (workspace.isNullOrBlank()) "Choose folder…" else "Change folder…") }
                if (!workspace.isNullOrBlank()) {
                    TextButton(onClick = { onWorkspaceChange(null) }) { Text("Use the global folder") }
                }
            }
            Text(
                "Empty means the folder chosen in Settings → Filesystem.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

internal const val FS_AUDIT_ROWS = 20

@Composable
private fun FsAuditRow(entry: FsToolAuditEntry) {
    val outcome = when (entry.result) {
        "ok" -> entry.result
        else -> "${entry.result}${entry.detail?.let { " — $it" } ?: ""}"
    }
    Text(
        "${entry.operation}  ${entry.relative}  ${entry.bytes} B  $outcome",
        style = MaterialTheme.typography.bodySmall,
        color = if (entry.result == "ok") {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

/** The three modes as the user reads them. */
internal val FsToolMode.label: String
    get() = when (this) {
        FsToolMode.OFF -> "Off"
        FsToolMode.READ_ONLY -> "Read-only"
        FsToolMode.READ_WRITE -> "Read-write"
    }

/**
 * The Settings row's one line. Static, like the Audio row's, and for its reason: naming the folder
 * here would mean reading a setting to draw a list. The screen itself says everything.
 */
internal const val FS_SETTINGS_SUMMARY = "Which folder the assistant may read and write in"
