package com.nikosm.voiceassistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

/**
 * The session model picker (PLAN.md §10/M11): the models the persona's own Ollama server lists,
 * choose one for the rest of the app run, or go back to the persona's configured model.
 *
 * ## Why only Ollama, in this first slice
 *
 * The list comes from the same shared catalog the persona editor and the models screen use, so the
 * three surfaces cannot disagree about what a server offers. Cloud sessions need a provider model
 * list in the header and a routing decision (a cloud persona's model is a different namespace);
 * that is a later addition, not something to fake with the Ollama list. The control is hidden for
 * cloud, gateway and image-generator personas for the same reason.
 *
 * ## What it does not do
 *
 * It does not edit the persona: the selection lives in [AssistantService.setSessionModel]'s
 * in-memory map and disappears with the process. The dialog says so in as many words, because a
 * model choice that looks saved but is not is a worse surprise than one that says "session".
 *
 * Capabilities are **display only**, as in the persona picker: nothing is hidden or disabled. A
 * model that does not advertise tools gets a "no tools" hint and, if the session's current model
 * is one, a sentence saying what may not work — the header choice remains the user's.
 */
@Composable
internal fun SessionModelDialog(
    service: AssistantService,
    persona: Persona,
    currentOverride: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val bases = remember(persona) { service.settingsManager.getOllamaBases() ?: emptyList() }
    val server = remember(bases, persona) { sessionModelServer(persona, bases) }
    var catalog by remember(server) {
        mutableStateOf(server?.let { service.modelCatalogFor(it.url) } ?: ModelCatalogState.NotLoaded)
    }

    LaunchedEffect(server) {
        val target = server ?: return@LaunchedEffect
        if (catalog is ModelCatalogState.NotLoaded) service.fetchModelCatalog(target, force = false)
        service.modelCatalog.collect { states -> catalog = states[target.url] ?: ModelCatalogState.NotLoaded }
    }

    val currentModel = currentOverride ?: persona.model
    val loaded = catalog as? ModelCatalogState.Loaded
    val currentEntry = loaded?.entries?.firstOrNull { entry ->
        server != null && "[${server.name}] ${entry.name}" == currentModel
    }
    val lacksTools = currentEntry?.capabilities?.let { ModelCapability.TOOLS !in it } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model for this session") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Overrides ${persona.name}'s model until the app closes. The persona itself is " +
                        "not changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )

                if (currentOverride != null) {
                    TextButton(onClick = { onSelect(null) }) {
                        Text("Use the persona's model (${persona.model.substringAfter("] ")})")
                    }
                }

                if (lacksTools) {
                    Text(
                        "This model does not advertise tool calling; file, MCP and memory tools " +
                            "may be unavailable this session.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                when {
                    server == null -> Text(
                        "This persona has no Ollama connection configured.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    loaded != null -> {
                        if (loaded.stale) {
                            Text(
                                "Showing the last known list${loaded.degradedReason?.let { ": $it" } ?: ""}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        if (loaded.entries.isEmpty()) {
                            Text("This server lists no models.", style = MaterialTheme.typography.bodySmall)
                        }
                        loaded.entries.forEach { entry ->
                            val qualified = "[${server.name}] ${entry.name}"
                            val selected = currentModel == qualified
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(qualified) }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    val hints = buildList {
                                        entry.capabilities?.let { caps ->
                                            if (ModelCapability.TOOLS !in caps) add("no tools")
                                            if (ModelCapability.VISION in caps) add("vision")
                                        }
                                        entry.parameterSize?.let { add(it) }
                                    }
                                    if (hints.isNotEmpty()) {
                                        Text(
                                            hints.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }

                    catalog is ModelCatalogState.Failed ->
                        Text((catalog as ModelCatalogState.Failed).reason, style = MaterialTheme.typography.bodySmall)

                    else -> Text("Loading models…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
