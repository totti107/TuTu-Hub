package com.arena.tv.ui.components

import android.view.KeyEvent as AndroidKey
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arena.tv.ui.theme.ArenaColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 4-digit PIN entry. Type it with the remote's number buttons, the on-screen keypad (D-pad + OK), or touch.
 * When no PIN exists yet, the first one entered (twice) becomes the PIN.
 */
@Composable
fun PinDialog(title: String, creating: Boolean, onDismiss: () -> Unit, check: suspend (String) -> Boolean, onSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    fun fail(msg: String) {
        error = msg; pin = ""
        scope.launch { for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, spring(stiffness = 4000f)) }
    }
    fun press(d: String) {
        if (busy || pin.length >= 4) return
        pin += d; error = null
        if (pin.length == 4) {
            val entered = pin
            if (creating && first == null) { first = entered; pin = ""; return }
            if (creating && first != entered) { first = null; fail("The PINs didn't match. Start again."); return }
            busy = true
            scope.launch { val ok = check(entered); busy = false; if (ok) onSuccess() else fail("Wrong PIN. Try again.") }
        }
    }
    fun back() { pin = pin.dropLast(1); error = null }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC000000))
                // The remote's number buttons type the PIN directly, wherever the focus is.
                .onPreviewKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    val digit = when (code) {
                        in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9 -> code - AndroidKey.KEYCODE_0
                        in AndroidKey.KEYCODE_NUMPAD_0..AndroidKey.KEYCODE_NUMPAD_9 -> code - AndroidKey.KEYCODE_NUMPAD_0
                        else -> -1
                    }
                    val del = code == AndroidKey.KEYCODE_DEL || code == AndroidKey.KEYCODE_FORWARD_DEL
                    if (digit < 0 && !del) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) { if (del) back() else press(digit.toString()) }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 380.dp).clip(RoundedCornerShape(28.dp)).background(ArenaColors.Surface)
                    .border(1.dp, ArenaColors.Outline, RoundedCornerShape(28.dp)).padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(ArenaColors.SurfaceHi), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, null, tint = ArenaColors.Gold, modifier = Modifier.size(26.dp))
                }
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(when {
                    creating && first == null -> "Create a 4-digit parental PIN"
                    creating -> "Enter the same PIN again"
                    else -> "Enter your parental PIN"
                }, color = ArenaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))

                // Four boxes; the next one to fill is outlined.
                Row(Modifier.padding(top = 18.dp).offset { IntOffset(shake.value.roundToInt(), 0) }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { i ->
                        val filled = i < pin.length
                        Box(
                            Modifier.size(width = 50.dp, height = 58.dp).clip(RoundedCornerShape(14.dp)).background(ArenaColors.Bg)
                                .border(2.dp, when { error != null -> ArenaColors.Live; i == pin.length -> ArenaColors.Accent; else -> ArenaColors.Outline }, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { if (filled) Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White)) }
                    }
                }
                Text(error ?: "Tip: you can use the number buttons on your remote", color = if (error != null) ArenaColors.Live else ArenaColors.Muted,
                    fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))

                Spacer(Modifier.height(14.dp))
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("✕", "0", "⌫")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                        row.forEach { k ->
                            PadKey(k, if (k == "1") Modifier.focusRequester(firstKey) else Modifier) {
                                when (k) { "⌫" -> back(); "✕" -> onDismiss(); else -> press(k) }
                            }
                        }
                    }
                }
                Text("✕ Cancel   ·   ⌫ Delete", color = ArenaColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun PadKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val digit = label.length == 1 && label[0].isDigit()
    Box(
        modifier.size(width = 84.dp, height = 54.dp).onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) Color.White else if (digit) ArenaColors.SurfaceHi else ArenaColors.Panel)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = if (digit) 24.sp else 20.sp, fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.Black else if (digit) ArenaColors.Text else ArenaColors.Muted)
    }
}
