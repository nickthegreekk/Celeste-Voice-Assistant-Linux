package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Settings → Health (PLAN.md §10/M12): the configured local services, checked when the user asks.
 *
 * **Nothing here runs on its own.** Opening the page shows "Not checked"; the request happens on
 * the button, once, and the results stay only for as long as the page is open. That is the plan's
 * no-unrequested-network rule applied to a status line: a health check is a network call, and the
 * app makes it because the user pressed a button, not because a screen was drawn.
 *
 * No scroll of its own — this page lives inside the settings shell's `LazyColumn` item, and a
 * second scroller there is measured against infinity (M8's Files screen learned that live).
 */
@Composable
internal fun ServerHealthSettings(service: AssistantService, contentPadding: Int = 16) {
    val targets = remember { healthTargetsFor(service.settingsManager) }
    var results by remember { mutableStateOf<Map<String, HealthResult>>(emptyMap()) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxWidth().padding(contentPadding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Server health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "On demand only: nothing on this page polls. Check now asks each Ollama server, Voice " +
                "Gateway and image server you have configured whether it answers, and how quickly. " +
                "Cloud providers are tested where they are configured, because each provider has " +
                "its own authentication shape.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Button(
            enabled = targets.isNotEmpty() && !checking,
            onClick = {
                checking = true
                scope.launch {
                    results = targets.associate { it.key to (HealthResult.Checking as HealthResult) }
                    val fresh = supervisorScope {
                        targets.map { target ->
                            async(Dispatchers.IO) { target.key to checkHealth(target) }
                        }.awaitAll()
                    }.toMap()
                    results = fresh
                    checking = false
                }
            },
        ) { Text(if (checking) "Checking…" else "Check now") }

        if (targets.isEmpty()) {
            Text(
                "No servers are configured yet. Add them in Settings → Servers or Settings → Image Gen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        targets.forEach { target ->
            HealthRow(target, results[target.key])
        }
    }
}

@Composable
private fun HealthRow(target: HealthTarget, result: HealthResult?) {
    Surface(shape = RectangleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${target.kind.label} · ${target.name}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                target.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            when (result) {
                null -> Text(
                    "Not checked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                HealthResult.Checking -> Text(
                    "Checking…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                is HealthResult.Ok -> Text(
                    "OK · ${result.latencyMs} ms · ${result.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is HealthResult.Failed -> Text(
                    "Failed · ${result.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The Settings row's one line — static, like the Filesystem and MCP rows. */
internal const val HEALTH_SETTINGS_SUMMARY = "Check the configured servers, only when you ask"
