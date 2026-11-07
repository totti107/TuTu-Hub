package com.arena.tv.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/**
 * What a focused item looks like with a TV remote: a bright white outline and a light highlight,
 * so it's obvious where the "cursor" is. Pressing (touch or OK) adds a quick flash.
 */
object FocusRing : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7

    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var focused = false
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
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
            val r = CornerRadius(14.dp.toPx())
            if (pressed) drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = r)
            if (focused) {
                val w = 3.dp.toPx()
                drawRoundRect(Color.White.copy(alpha = 0.16f), cornerRadius = r)
                drawRoundRect(Color.White, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                    cornerRadius = r, style = Stroke(w))
            }
        }
    }
}

/** For Material buttons/chips that draw their own (faint) focus state: add the same white outline. */
fun Modifier.focusOutline(shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    this.onFocusChanged { focused = it.hasFocus || it.isFocused }
        .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
}
