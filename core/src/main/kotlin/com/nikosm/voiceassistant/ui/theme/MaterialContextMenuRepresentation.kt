package com.nikosm.voiceassistant.ui.theme

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * The text-field context menu, drawn like the rest of the application (2026-10-18).
 *
 * Compose Desktop's default representation is a light, Compose-drawn box; beside the message
 * actions menu it read as a second, foreign surface — white Cut/Copy/Paste/Select all against the
 * dark Speak/Copy/Edit/Delete. This representation is the same Material surface and item style as
 * that menu, installed once in [VoiceAssistantTheme] as `LocalContextMenuRepresentation`, so every
 * text field follows the application's palette — including the GTK style, because the colours are
 * read from `MaterialTheme` at composition.
 *
 * The anchor contract is Compose's: `ContextMenuState.Status.Open.rect` is in WINDOW coordinates
 * (its own default representation calls `rememberPopupPositionProviderAtPosition(rect.center)`),
 * so the popup is placed at the rect's top-left and clamped to the window. Message bubbles are
 * unaffected: they override this representation with a no-op, because their own actions menu is
 * the one answer to a right-click there.
 */
class MaterialContextMenuRepresentation : ContextMenuRepresentation {

    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val anchor = status.rect

        Popup(
            popupPositionProvider = object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupSize: IntSize,
                ): IntOffset {
                    val x = anchor.left.roundToInt()
                        .coerceIn(0, (windowSize.width - popupSize.width).coerceAtLeast(0))
                    val y = anchor.top.roundToInt()
                        .coerceIn(0, (windowSize.height - popupSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            },
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    items().forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = {
                                item.onClick()
                                state.status = ContextMenuState.Status.Closed
                            },
                        )
                    }
                }
            }
        }
    }
}
