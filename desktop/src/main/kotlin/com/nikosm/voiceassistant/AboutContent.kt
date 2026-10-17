package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The About window's content (M17).
 *
 * Deliberately small and factual: the application's name, what it is, the version, the upstream
 * commit the ported files were copied from, and the licence. The pin is the same resource
 * `--selftest` prints, so the window and the smoke test can never disagree about it.
 */
@Composable
fun AboutContent(version: String, upstreamCommit: String) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Celeste", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A voice assistant that talks to your own models: speech in, speech out, images.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        AboutRow("Version", version)
        AboutRow("Upstream commit", upstreamCommit)
        AboutRow("Licence", "GPL-3.0-only")
        Spacer(Modifier.height(6.dp))
        Text(
            "A Linux desktop application built with Compose Multiplatform. One process, your " +
                "own servers, no telemetry and no update check.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
