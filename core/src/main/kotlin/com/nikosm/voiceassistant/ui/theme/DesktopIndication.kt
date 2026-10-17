package com.nikosm.voiceassistant.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * M17: the desktop's feedback for a clickable row.
 *
 * Material's ripple answers "did my finger land?", a question a mouse does not ask. What a pointer
 * asks instead is "is this a target?" — before the press, not during it. So the indication this
 * theme installs for every plain `Modifier.clickable` draws, over the row's own content:
 *
 *  - a faint tint while the pointer is over it (hover),
 *  - the same tint while it is held (press),
 *  - a 2 dp focus ring when it holds keyboard focus (focus traversal).
 *
 * It is installed once in [VoiceAssistantTheme], which is why "every clickable row reacts to
 * hover" is one change rather than an edit to every row. Material components that call `ripple()`
 * themselves (buttons, icon buttons, list items) keep their own indication — this replaces only
 * the bare `clickable` that the ported screens use for rows and tiles.
 *
 * An `IndicationNodeFactory` rather than the older `Indication`/`IndicationInstance` pair: that API
 * is gone in this Compose version, and the node collects the interaction stream in a coroutine
 * bound to the node's own lifetime, so no collector outlives the row.
 */
class DesktopIndication(
    private val hoverColor: Color,
    private val focusColor: Color,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        Node(interactionSource, hoverColor, focusColor)

    // Required by IndicationNodeFactory's contract: equal values share a cached node.
    override fun equals(other: Any?): Boolean =
        other is DesktopIndication && other.hoverColor == hoverColor && other.focusColor == focusColor

    override fun hashCode(): Int = 31 * hoverColor.hashCode() + focusColor.hashCode()

    private class Node(
        private val interactionSource: InteractionSource,
        private val hoverColor: Color,
        private val focusColor: Color,
    ) : Modifier.Node(), DrawModifierNode {

        private var hovered = false
        private var pressed = false
        private var focused = false

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is HoverInteraction.Enter -> hovered = true
                        is HoverInteraction.Exit -> hovered = false
                        is FocusInteraction.Focus -> focused = true
                        is FocusInteraction.Unfocus -> focused = false
                        is PressInteraction.Press -> pressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (hovered || pressed) drawRect(hoverColor)
            if (focused) {
                val inset = 1.dp.toPx()
                drawRect(
                    color = focusColor,
                    topLeft = Offset(inset, inset),
                    size = Size(
                        (size.width - inset * 2).coerceAtLeast(0f),
                        (size.height - inset * 2).coerceAtLeast(0f)
                    ),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
