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
 * 2026-10-18: the GitHub repository and releases URLs are shown in place and open in the desktop
 * browser through [onOpenUrl]. [PROJECT_URL] is set since the project was published; were it ever
 * blank again, the row would say "Not published yet" and the releases line would say so too, rather
 * than pretending. The application never checks for updates on its own (PLAN.md §8 and §5); the
 * **Check for updates** button is the one user-initiated check, and it downloads and installs
 * nothing. Opening the releases page remains the update path.
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
                LinkText(projectUrl, onOpenUrl)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Updates", style = MaterialTheme.typography.labelLarge)
        // The update path, in full view: the link itself is shown and clickable, and the new
        // version is installed with the package manager. Nothing is polled, nothing downloaded
        // here. Both this link and the GitHub row carry the real URL (2026-10-18).
        if (releasesUrl != null) {
            LinkText(releasesUrl, onOpenUrl)
            Text(
                "Opens the releases page in your browser. Install the new version with the " +
                    "package manager you used for this one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            // The one network action in About, and only on this button. It reports whether a newer
            // release exists; the link above is where the user goes, and the package manager does
            // the upgrade.
            UpdateCheckControl(currentVersion = version)
        } else {
            Text(
                "Available once the project has a public page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

/** A URL the user can click: the address is the control, not hidden behind a label. */
@Composable
private fun LinkText(url: String, onOpenUrl: (String) -> Unit) {
    Text(
        url,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { onOpenUrl(url) }
    )
}

@Composable
private fun AboutRow(label: String, value: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(130.dp))
        value()
    }
}
