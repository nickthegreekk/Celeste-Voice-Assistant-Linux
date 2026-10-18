package com.nikosm.voiceassistant

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The About window's content (M17).
 *
 * Deliberately small and factual: the application's name, what it is, the version, the upstream
 * commit the ported files were copied from, and the licence. The pin is the same resource
 * `--selftest` prints, so the window and the smoke test can never disagree about it.
 *
 * 2026-10-18: a GitHub row and the update path. [PROJECT_URL] is blank until the project is
 * published, so the row says so and the releases link is disabled rather than pretending. When
 * it is filled, both open in the desktop's browser through [onOpenUrl]; the application itself
 * still never checks for updates or fetches anything (PLAN.md §8 and §5).
 */
@Composable
fun AboutContent(
    version: String,
    upstreamCommit: String,
    projectUrl: String = PROJECT_URL,
    onOpenUrl: (String) -> Unit = {},
) {
    val releasesUrl = projectReleasesUrl()
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
        AboutRow("GitHub") {
            if (projectUrl.isBlank()) {
                Text(
                    "Not published yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    projectUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenUrl(projectUrl) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Updates", style = MaterialTheme.typography.labelLarge)
        // The update path, in its whole: the user opens the releases page and installs the new
        // version with the package manager. Nothing is polled, nothing is downloaded here.
        TextButton(
            onClick = { releasesUrl?.let(onOpenUrl) },
            enabled = releasesUrl != null,
        ) {
            Text("Open the releases page")
        }
        Text(
            if (releasesUrl != null) {
                "Opens the releases page in your browser. Install the new version with the " +
                    "package manager you used for this one."
            } else {
                "Available once the project has a public page."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "A Linux desktop application built with Compose Multiplatform. One process, your " +
                "own servers, no telemetry and no automatic update check.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    AboutRow(label) { Text(value, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun AboutRow(label: String, value: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(130.dp))
        value()
    }
}
