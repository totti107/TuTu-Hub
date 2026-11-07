package com.arena.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.arena.tv.ui.theme.ArenaColors
import kotlinx.coroutines.launch

/**
 * 4-digit PIN pad that works with a TV remote (D-pad + OK) as well as touch.
 * When no PIN exists yet, the first one entered becomes the PIN.
 */
@Composable
fun PinDialog(title: String, creating: Boolean, onDismiss: () -> Unit, check: suspend (String) -> Boolean, onSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    fun press(d: String) {
        if (pin.length >= 4) return
        pin += d; error = null
        if (pin.length == 4) {
            val entered = pin
            if (creating && first == null) { first = entered; pin = ""; return }
            if (creating && first != entered) { error = "PINs didn't match. Try again."; first = null; pin = ""; return }
            scope.launch { if (check(entered)) onSuccess() else { error = "Wrong PIN"; pin = "" } }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(ArenaColors.Surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒  $title", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(when {
                creating && first == null -> "Create a 4-digit PIN for adult channels"
                creating -> "Enter the same PIN again"
                else -> "Enter your PIN"
            }, color = ArenaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < pin.length) Color.White else ArenaColors.SurfaceHi)) }
            }
            error?.let { Text(it, color = ArenaColors.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(16.dp))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    row.forEach { k ->
                        if (k.isEmpty()) Spacer(Modifier.width(64.dp)) else Box(
                            Modifier.size(64.dp).clip(CircleShape).background(ArenaColors.SurfaceHi)
                                .then(if (k == "1") Modifier.focusRequester(firstKey) else Modifier)
                                .clickable { if (k == "⌫") pin = pin.dropLast(1) else press(k) },
                            contentAlignment = Alignment.Center,
                        ) { Text(k, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
                    }
                }
            }
            Text("Cancel", color = ArenaColors.Muted, modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss).padding(10.dp))
        }
    }
}
