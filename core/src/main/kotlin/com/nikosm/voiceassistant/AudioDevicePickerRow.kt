package com.nikosm.voiceassistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A label, the chosen device, and a dropdown of what this machine has — the picker both device
 * selections use (PLAN.md §9's source/sink selection).
 *
 * **Its own file rather than [UIComponents]**, which is a COPY file and must stay byte-identical
 * to upstream (PLAN.md §7): a new file is the only shape in which new UI can arrive here, and it
 * is also the honest one — this is ported code that upstream has no counterpart for.
 *
 * One composable for the two call sites rather than two that drift: the persona editor's row and
 * the Settings row differ only in their surrounding text. It follows the idiom already used for
 * the editor's model picker — a read-only field with a dropdown arrow — because Settings has no
 * picker of its own to borrow.
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
            // An invisible overlay makes the whole field open the menu; the arrow above stays the
            // keyboard-reachable control.
            Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
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
