package com.arena.tv.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Remote control: a short OK press calls [onClick], holding OK for half a second calls [onHold].
 * Works on remotes that repeat OK while it's held and on those that don't (a timer is used).
 * Touch screens are not affected (use combinedClickable's long press there).
 */
fun Modifier.holdOk(onClick: () -> Unit, onHold: (() -> Unit)?): Modifier = if (onHold == null) this else composed {
    val scope = rememberCoroutineScope()
    var down by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf<Job?>(null) }
    onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (!ok) return@onPreviewKeyEvent false
        when (e.type) {
            KeyEventType.KeyDown -> {
                if (e.nativeKeyEvent.repeatCount == 0) {
                    down = true; fired = false
                    timer?.cancel()
                    timer = scope.launch { delay(500); if (down && !fired) { fired = true; onHold() } }
                } else if (!fired && e.nativeKeyEvent.isLongPress) { fired = true; timer?.cancel(); onHold() }
                true
            }
            KeyEventType.KeyUp -> {
                timer?.cancel()
                if (down && !fired) onClick()
                down = false; fired = false
                true
            }
            else -> false
        }
    }
}
