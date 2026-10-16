package com.nikosm.voiceassistant

// PARTIALLY PORTED IN M1. What is here is the shared half: the model-list widgets the M1 screens
// call — LoadingRow, StaleNote, EmptyNote, ModelRowBody and FavoriteStar (all used by ModelPicker)
// and ModelsEntryRow (used by SettingsScreens). What is NOT here is `ModelsScreen` itself and its
// private helpers `ModelsHeader`, `filterEmptyMessage` and `ModelRow`, which are the whole-screen
// feature and belong with the milestone that draws it. PLAN.md §7's row for this file says
// "partially ported in M1" for the same reason, and docs/PROVENANCE.md repeats it.
//
// The rule that decided the split is PLAN.md §5's: a shared building block arrives with its first
// caller, and only whole features are deferred. `ModelsScreen` is reached from Settings, so its
// call site carries the `DEFERRED TO M3` marker rather than this file carrying a stub.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp




@Composable
internal fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Loading models…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun StaleNote(reason: String?, unreachable: Boolean) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        text = staleModelNote(reason, unreachable),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

@Composable
internal fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}


/**
 * The name/chips/spec body of a model row, shared by the models screen and the persona
 * picker.
 *
 * Extracted so the two render a model identically BY CONSTRUCTION rather than by two
 * copies agreeing: the split name, the quiet tag, the full name on its own line, the
 * pending placeholder and the spec line are all decided here once. The picker's requirement
 * is "the same capability chips and the spec line as the models screen", and this is what
 * makes that a property instead of a promise.
 */
@Composable
internal fun ModelRowBody(
    entry: ModelEntry,
    showState: ModelShowState,
    accentColor: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = displayModelName(entry.name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            val tag = modelTag(entry.name)
            if (tag.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1
                )
            }
            trailing?.invoke()
        }
        // The full name is always available, never clipped.
        Text(
            text = entry.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // While /api/show is in flight, a placeholder holds the chips' place so they
        // do not flip from tags values to show values under the reader.
        if (showState == ModelShowState.PENDING) {
            PendingBadge()
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (entry.isEmbeddingOnly) {
                    // "Embedding only" IS the embedding statement; repeating it as a
                    // plain "Embedding" chip beside it says the same thing twice.
                    CapabilityChip("Embedding only", accentColor)
                }
                deriveCapabilityBadges(entry)
                    .filterNot { it == ModelCapability.EMBEDDING && entry.isEmbeddingOnly }
                    .forEach { capability ->
                        CapabilityChip(capabilityLabel(capability), accentColor)
                    }
            }
        }

        val spec = modelSpecLine(entry)
        if (spec.isNotEmpty()) {
            Text(
                text = spec,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The star control, shared by both surfaces.
 *
 * Labelled with its own action rather than left null: a bare `StarBorder` icon is
 * indistinguishable from decoration to a screen reader, and this is the one control in a
 * row that changes saved state.
 */
@Composable
internal fun FavoriteStar(
    isFavorite: Boolean,
    entryName: String,
    serverName: String,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onToggleFavorite, modifier = modifier.size(40.dp)) {
        Icon(
            if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
            // The row itself carries the full spoken label; the star adds only what
            // its own action is, so TalkBack never reads the same sentence twice.
            contentDescription = "Toggle favorite $entryName on $serverName",
            tint = if (isFavorite) Color.Yellow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CapabilityChip(label: String, accentColor: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accentColor.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PendingBadge() {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) {
        Text(
            text = "checking capabilities…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            // Deliberately NOT cleared: "checking capabilities…" is a real state, and a
            // screen-reader user is entitled to hear that the chips are still on their way.
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * The per-server "Models" row inside the Ollama servers list.
 *
 * A full-width tappable row rather than another icon button: the server row already carries
 * four icon buttons, and this one opens a screen.
 */
@Composable
internal fun ModelsEntryRow(
    server: ServerConfig,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f))
    ) {
        Text(
            text = "Models",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}
