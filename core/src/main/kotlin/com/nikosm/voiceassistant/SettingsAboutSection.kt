package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The About section at the very bottom of the Settings list (asked for 2026-10-17).
 *
 * The same four facts the About window shows — name, version, upstream pin, licence — in the
 * list's own visual language rather than a second copy of that window's layout. Both read
 * [APP_VERSION] and [upstreamPin], so the two places cannot disagree about what this build is.
 *
 * It sits after the last category, which puts it at the bottom of the scrolling list in the
 * one-pane layout and at the bottom of the category rail in the two-pane one.
 */
@Composable
internal fun SettingsAboutSection(accentColor: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp)) {
        Text(
            text = "About",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.2f)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Celeste",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = accentColor
                )
                Text(
                    text = "Version $APP_VERSION",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Upstream commit ${upstreamPin()}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "GPL-3.0-only · no telemetry, no update check",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
