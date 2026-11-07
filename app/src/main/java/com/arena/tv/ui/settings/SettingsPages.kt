package com.arena.tv.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.data.Prefs
import com.arena.tv.ui.components.DropChip
import com.arena.tv.ui.components.QrCode
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline

/** Settings sections, shown as a side menu on TVs and tablets and as a list on phones. */
enum class SettingsPage(val label: String, val icon: ImageVector, val hint: String) {
    PLAYLISTS("Playlists", Icons.Rounded.PlaylistPlay, "Providers, loading and categories"),
    PLAYBACK("Playback", Icons.Rounded.PlayCircle, "Buffer, languages, subtitles, screen"),
    BROWSING("Browsing", Icons.Rounded.ViewQuilt, "Start screen, views, clock"),
    PROTECTION("Protection", Icons.Rounded.Lock, "Adult channels and PIN"),
    SPORTS("Sports", Icons.Rounded.SportsSoccer, "Sports, teams, leagues, odds"),
    ACCOUNTS("Trakt", Icons.Rounded.Person, "Sync what you watch"),
    BACKUP("Backup & restore", Icons.Rounded.Backup, "Move your setup to another device"),
    UPDATES("Updates & about", Icons.Rounded.SystemUpdate, "App version and updates"),
}

@Composable
fun SettingsMenu(selected: SettingsPage?, wide: Boolean, modifier: Modifier = Modifier, onSelect: (SettingsPage) -> Unit) {
    LazyColumn(modifier.padding(horizontal = 12.dp)) {
        items(SettingsPage.entries) { p ->
            val sel = p == selected
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (sel) ArenaColors.SurfaceHi else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(p) }.padding(horizontal = 16.dp, vertical = if (wide) 12.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(p.icon, null, tint = if (sel) ArenaColors.Accent else ArenaColors.Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(p.label, fontSize = 16.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                    if (!wide) Text(p.hint, color = ArenaColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SetCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
        .background(ArenaColors.Surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
        title?.let { Text(it.uppercase(), color = ArenaColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) }
        content()
    }
}

@Composable
private fun <T> ChoiceRow(title: String, desc: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            desc?.let { Text(it, color = ArenaColors.Muted, fontSize = 12.sp) }
        }
        DropChip(options.firstOrNull { it.first == selected }?.second ?: "", options, selected, onSelect)
    }
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            desc?.let { Text(it, color = ArenaColors.Muted, fontSize = 12.sp) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

private val LANGS = listOf("en" to "English", "ar" to "Arabic", "fr" to "French", "es" to "Spanish", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "tr" to "Turkish", "nl" to "Dutch", "hi" to "Hindi", "ur" to "Urdu")

fun LazyListScope.playbackItems(p: Prefs, set: ((Prefs) -> Prefs) -> Unit) {
    item {
        SetCard("Stream") {
            ChoiceRow("Buffer size", "How much video is loaded ahead. Pick High if channels stutter.",
                listOf("low" to "Low · starts fastest", "balanced" to "Balanced", "high" to "High · smoothest"), p.buffer) { v -> set { it.copy(buffer = v) } }
            SwitchRow("Match frame rate", "Switches the TV to the video's refresh rate (50 Hz for most sport, 24 Hz for films). Smoother motion; the screen may blink once.",
                p.matchFrameRate) { v -> set { it.copy(matchFrameRate = v) } }
        }
    }
    item {
        SetCard("Sound and subtitles") {
            ChoiceRow("Preferred audio language", "Used when a stream has more than one.", listOf("" to "Stream default") + LANGS, p.audioLang) { v -> set { it.copy(audioLang = v) } }
            ChoiceRow("Subtitles", "Turned on automatically when available in this language.", listOf("" to "Off") + LANGS, p.subtitleLang) { v -> set { it.copy(subtitleLang = v) } }
            ChoiceRow("Subtitle size", null, listOf("small" to "Small", "normal" to "Normal", "large" to "Large", "huge" to "Extra large"), p.subtitleSize) { v -> set { it.copy(subtitleSize = v) } }
        }
    }
    item {
        SetCard("Screen") {
            SwitchRow("Keep the screen on while playing", "Stops the TV or phone from going to sleep during playback.", p.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
            SwitchRow("Clock always visible", "Shows the time in the corner while you watch, not only with the controls.", p.clockAlways) { v -> set { it.copy(clockAlways = v) } }
        }
    }
}

fun LazyListScope.browsingItems(p: Prefs, set: ((Prefs) -> Prefs) -> Unit) {
    item {
        SetCard("Start") {
            ChoiceRow("Open the app on", "Takes effect next time the app starts.",
                listOf("home" to "Home", "sports" to "Sports", "live" to "Live TV", "movies" to "Movies", "series" to "Series"), p.startScreen) { v -> set { it.copy(startScreen = v) } }
            ChoiceRow("Live TV opens in", null, listOf("library" to "Library", "guide" to "TV Guide"), p.liveView) { v -> set { it.copy(liveView = v) } }
        }
    }
    item {
        SetCard("Clock") {
            ChoiceRow("Time format", null, listOf("system" to "Device setting", "12" to "12-hour (4:32 PM)", "24" to "24-hour (16:32)"), p.clock) { v -> set { it.copy(clock = v) } }
        }
    }
}

fun LazyListScope.backupItems(vm: SettingsViewModel) {
    item { BackupCard(vm) }
}

@Composable
private fun BackupCard(vm: SettingsViewModel) {
    val context = LocalContext.current
    val message by vm.backupMessage.collectAsState()
    val pending by vm.pendingRestore.collectAsState()
    val url by vm.backupUrl.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { u -> vm.exportTo(context, u) } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.importFrom(context, u) } }
    fun tryLaunch(f: () -> Unit) = runCatching(f).onFailure { vm.backupMessage.value = "This device has no file picker. Use \"Transfer with your phone\" below." }

    SetCard("Backup & restore") {
        Text("Save a copy of your playlists, settings and favorites, or load one from another device. Your PIN and Trakt login aren't included.",
            color = ArenaColors.Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            Button(onClick = { tryLaunch { save.launch("tutu-backup.json") } }, modifier = Modifier.focusOutline()) { Text("Export to file") }
            OutlinedButton(onClick = { tryLaunch { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) } },
                modifier = Modifier.focusOutline()) { Text("Import from file") }
        }
        message?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            color = if (it.startsWith("✓")) ArenaColors.Accent else ArenaColors.Live) }
    }
    SetCard("Transfer with your phone") {
        Text("Best for TVs: scan a code with your phone (same Wi-Fi) to download this TV's backup, or send a backup from your phone to this TV.",
            color = ArenaColors.Muted, fontSize = 13.sp)
        OutlinedButton(onClick = vm::startBackupServer, modifier = Modifier.padding(top = 10.dp).focusOutline()) { Text("Show QR code") }
    }

    url?.let { u ->
        AlertDialog(onDismissRequest = vm::stopBackupServer, containerColor = ArenaColors.Surface,
            title = { Text("Scan with your phone") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (u.isEmpty()) Text("This device isn't on a Wi-Fi or wired network.", color = ArenaColors.Live)
                    else {
                        QrCode(u, 200.dp)
                        Text(u, color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Text("Keep this open while you use the page on your phone.", color = ArenaColors.Muted, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::stopBackupServer, modifier = Modifier.focusOutline()) { Text("Done") } })
    }

    pending?.let { b ->
        AlertDialog(onDismissRequest = { vm.pendingRestore.value = null }, containerColor = ArenaColors.Surface,
            title = { Text("Restore this backup?") },
            text = {
                Text("It has ${b.playlists.size} playlist(s): ${b.playlists.joinToString { it.name }}. " +
                    "They replace the playlists on this device, then the channels reload. Favorites are added to yours.")
            },
            confirmButton = { Button(onClick = vm::confirmRestore, modifier = Modifier.focusOutline()) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { vm.pendingRestore.value = null }, modifier = Modifier.focusOutline()) { Text("Cancel") } })
    }
}
