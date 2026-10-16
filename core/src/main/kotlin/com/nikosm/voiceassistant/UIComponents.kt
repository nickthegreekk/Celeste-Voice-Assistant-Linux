package com.nikosm.voiceassistant

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun ResponseTimeBadge(
    responseTimeMs: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.2f)),
        modifier = modifier
    ) {
        Text(
            text = "${String.format(java.util.Locale.US, "%.1f", responseTimeMs / 1000.0)}s",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun ProviderLogo(
    icon: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    // persona icon was 28 changed to 36 dp
    isCloud: Boolean = false,
    // Optional absolute path to an app-owned artwork file (Persona.iconImageUri).
    // When present it REPLACES the built-in provider glyph. When it is null — or the
    // file has since been removed on disk, which Coil reports as an error and simply
    // draws nothing for — the original letter/icon badge is used, so a persona always
    // has a visible logo rather than an empty circle.
    imagePath: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(
                width = 1.5.dp, 
                color = if (isCloud) Color.Red else Color(0xFF4ADE80), 
                shape = CircleShape
            )
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (!imagePath.isNullOrBlank()) {
            // Custom artwork wins over the built-in glyph. Coil decodes off the main
            // thread and caches by path key, so recomposition (and the persona list's
            // frequent rebuilds) never re-reads the file.
            AsyncImage(
                model = File(imagePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else when (icon) {
            "O" -> Icon(Icons.Default.Memory, null, tint = Color.Black, modifier = Modifier.size(size * 0.6f))
            "G" -> Icon(Icons.Default.Language, null, tint = Color(0xFF4285F4), modifier = Modifier.size(size * 0.6f))
            "A" -> Icon(Icons.Default.Architecture, null, tint = Color(0xFFD97755), modifier = Modifier.size(size * 0.6f))
            "D" -> Icon(Icons.Default.Cyclone, null, tint = Color(0xFF4D6BFE), modifier = Modifier.size(size * 0.6f))
            "T" -> Icon(Icons.Default.Translate, null, tint = Color(0xFF06B6D4), modifier = Modifier.size(size * 0.6f))
            else -> {
                Text(
                    text = icon,
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MicRing(
    state: AssistantState,
    muted: Boolean,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseDurationMs = when (state) {
        AssistantState.SPEAKING -> 800
        AssistantState.THINKING -> 900
        AssistantState.LISTENING -> 1200
        else -> 3000
    }

    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseDurationMs, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    Box(
        modifier = Modifier
            .size(size * 1.6f)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (!muted && state != AssistantState.IDLE) {
            // Increased to 5 rings for a smoother "trail" effect
            listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f).forEach { delay ->
                val rippleProgress = (progress + delay) % 1f
                val rippleScale = 1f + (rippleProgress * 0.9f)
                
                // alpha starts at 0.8 (close to mic brightness) and fades to 0 as it expands
                val rippleAlpha = (1f - rippleProgress).let { it * it } * 0.7f

                Box(
                    modifier = Modifier
                        .size(size)
                        .scale(rippleScale)
                        .clip(CircleShape)
                        .border(
                            // Border gets thinner as it expands to emphasize the "fading trail"
                            width = (1.0.dp * (1f - rippleProgress)).coerceAtLeast(0.5.dp),
                            color = color.copy(alpha = rippleAlpha), 
                            shape = CircleShape
                        )
                )
            }
        }

        val coreScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = if (state != AssistantState.IDLE) 1.12f else 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = pulseDurationMs / 2, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "coreScale"
        )

        Box(
            modifier = Modifier
                .size(size)
                .scale(coreScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            color.copy(alpha = if (state != AssistantState.IDLE) 0.4f else 0.2f),
                            color.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .border(
                    width = 2.5.dp,
                    color = color.copy(alpha = if (state != AssistantState.IDLE) 0.8f else 0.5f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(size * 0.72f)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (muted) "Microphone muted" else "Microphone active",
                    tint = if (muted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else color,
                    modifier = Modifier.size(size * 0.28f)
                )
            }
        }
    }
}

@Composable
fun RoundIconButton(
    modifier: Modifier = Modifier,
    active: Boolean,
    activeColor: Color,
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) activeColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * A label, the chosen device, and a dropdown of what this machine has — the picker both device
 * selections use (PLAN.md §9's source/sink selection).
 *
 * One composable for the two call sites rather than two that drift: the editor's row and the
 * Settings row differ only in their surrounding text. It follows the idiom already used for the
 * persona editor's model picker — a read-only field with a dropdown arrow — because Settings has
 * no picker of its own to borrow and the editor's is the closest thing to a house style.
 *
 * [devices] is passed in rather than fetched here on purpose: `AudioDevices.outputs()` shells out
 * to `pactl` or `wpctl` and takes up to three seconds, so the caller fetches **once**, when the
 * picker is opened, and hands the snapshot over. A composable that enumerated during composition
 * would spawn a process per recomposition.
 *
 * The first entry is always the default: it is what an unset [selectedId] means, so it has to be
 * selectable — a picker whose empty state cannot be returned to is a one-way door. A stored device
 * that is no longer present is *not* listed (there is nothing to point at) and the field simply
 * shows the default; the callers' resolvers log the disappearance rather than hiding it.
 */
@Composable
internal fun AudioDevicePickerRow(
    title: String,
    subtitle: String,
    devices: List<AudioDevice>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    defaultLabel: String = "System default",
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = devices.firstOrNull { it.id == selectedId }?.label ?: defaultLabel

    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Box {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose $title")
                    }
                },
            )
            // An invisible overlay makes the whole field clickable without fighting the read-only
            // TextField's own focus handling; the arrow above stays the keyboard-reachable control.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(defaultLabel) },
                    onClick = { onSelect(null); expanded = false },
                )
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.label) },
                        onClick = { onSelect(device.id); expanded = false },
                    )
                }
            }
        }
    }
}
