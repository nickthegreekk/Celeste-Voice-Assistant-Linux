package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The one control the update check owns: a button, a spinner-state sentence, and the result.
 *
 * Used by both About surfaces (the M17 window and the Settings About section). It performs no work
 * until the button is pressed, which is the whole point of the manual shape — there is no
 * `LaunchedEffect`, no timer and no call in composition.
 */
@Composable
fun UpdateCheckControl(
    modifier: Modifier = Modifier,
    currentVersion: String = APP_VERSION,
) {
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                if (!checking) {
                    checking = true
                    result = null
                    scope.launch {
                        result = checkForLatestRelease(currentVersion)
                        checking = false
                    }
                }
            },
            enabled = !checking,
        ) {
            Text(if (checking) "Checking…" else "Check for updates")
        }

        val status = when (val current = result) {
            null -> null
            is UpdateCheckResult.UpToDate -> "You're up to date — latest release ${current.latest}."
            is UpdateCheckResult.Available -> "Version ${current.latest} is available."
            UpdateCheckResult.NoRelease -> "No published release was found."
            UpdateCheckResult.RateLimited -> "GitHub is rate-limiting update checks; try again later."
            is UpdateCheckResult.Failed -> current.reason
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
