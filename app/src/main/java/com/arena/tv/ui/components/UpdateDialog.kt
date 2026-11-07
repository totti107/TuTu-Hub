package com.arena.tv.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.data.Updater
import com.arena.tv.data.Updater.State
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline

/** "A new version is available" → download with progress → Android's installer. */
@Composable
fun UpdateDialog(updater: Updater) {
    val state by updater.state.collectAsState()
    val s = state
    val release = when (s) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Ready -> s.release
        is State.NeedsPermission -> s.release
        else -> null
    }
    if (release == null && s !is State.Failed) return
    AlertDialog(
        onDismissRequest = { if (s is State.Available) updater.later() else updater.close() },
        containerColor = ArenaColors.Surface,
        title = { Text(if (s is State.Failed) "Update" else "TuTu ${release!!.version} is available") },
        text = {
            Column {
                when (s) {
                    is State.Failed -> Text(s.message, color = ArenaColors.Muted)
                    is State.Downloading -> {
                        Text("Downloading… ${(s.progress * 100).toInt()}%", color = ArenaColors.Muted)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                    is State.NeedsPermission -> Text(
                        "Android needs your OK first: switch on \"Allow from this source\" for TuTu, come back, then press Install.",
                        color = ArenaColors.Muted)
                    is State.Ready -> Text("Downloaded. Press Install, then confirm on Android's screen. Your playlists and settings stay.", color = ArenaColors.Muted)
                    else -> {
                        Text("You have ${updater.currentVersion}.", color = ArenaColors.Muted)
                        if (release!!.notes.isNotBlank()) Text(release.notes, fontSize = 13.sp, color = ArenaColors.Muted,
                            modifier = Modifier.padding(top = 8.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        },
        confirmButton = {
            when (s) {
                is State.Available -> Button(onClick = { updater.download(s.release) }, modifier = Modifier.focusOutline()) { Text("Update") }
                is State.Ready -> Button(onClick = { updater.install(s.release) }, modifier = Modifier.focusOutline()) { Text("Install") }
                is State.NeedsPermission -> Button(onClick = { updater.install(s.release) }, modifier = Modifier.focusOutline()) { Text("Install") }
                is State.Failed -> Button(onClick = { updater.close() }, modifier = Modifier.focusOutline()) { Text("OK") }
                else -> {}
            }
        },
        dismissButton = {
            when (s) {
                is State.Available -> TextButton(onClick = { updater.later() }, modifier = Modifier.focusOutline()) { Text("Later") }
                is State.Downloading -> {}
                is State.Failed -> {}
                else -> TextButton(onClick = { updater.close() }, modifier = Modifier.focusOutline()) { Text("Close") }
            }
        },
    )
}
