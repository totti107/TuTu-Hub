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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.FiberManualRecord
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.data.Prefs
import com.arena.tv.app
import kotlinx.coroutines.launch
import com.arena.tv.ui.components.DropChip
import com.arena.tv.ui.components.QrCode
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline

/** Settings sections, shown as a side menu on TVs and tablets and as a list on phones. */
enum class SettingsPage(val label: String, val icon: ImageVector, val hint: String) {
    PLAYLISTS("Playlists", Icons.Rounded.PlaylistPlay, "Providers, loading and categories"),
    GUIDE("TV guide", Icons.Rounded.CalendarMonth, "Guide coverage and extra guide links"),
    PLAYBACK("Playback", Icons.Rounded.PlayCircle, "Buffer, languages, subtitles, screen"),
    BROWSING("Browsing", Icons.Rounded.ViewQuilt, "Start screen, views, clock"),
    TITLES("Title Optimizer", Icons.Rounded.AutoFixHigh, "Clean up channel and movie names"),
    PROTECTION("Protection", Icons.Rounded.Lock, "Adult channels and PIN"),
    SPORTS("Sports", Icons.Rounded.SportsSoccer, "Sports, teams, leagues, odds"),
    ACCOUNTS("Trakt", Icons.Rounded.Person, "Sync what you watch"),
    RECORDING("Recording", Icons.Rounded.FiberManualRecord, "Your recordings, storage and limits"),
    PLANNER("Planner", Icons.Rounded.EventNote, "Planned recordings, reminders and timing"),
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

fun LazyListScope.sportsWatchItems(p: Prefs, set: ((Prefs) -> Prefs) -> Unit, askNotifications: () -> Unit) {
    item {
        SetCard("Scores while watching") {
            Text("A small scoreboard in the corner while you watch any channel. A goal lights it up.", color = ArenaColors.Muted, fontSize = 13.sp)
            SwitchRow("Pinned matches", "Matches you pinned from a match page (\"Pin score while you watch\").", p.scorePinned) { v -> set { it.copy(scorePinned = v) } }
            SwitchRow("Your teams", "Live games of the teams you follow, on any channel.", p.scoreTeams) { v -> set { it.copy(scoreTeams = v) } }
            SwitchRow("The match on this channel", "When the channel you watch shows a live game.", p.scoreThisChannel) { v -> set { it.copy(scoreThisChannel = v) } }
            ChoiceRow("Style", null, listOf("rows" to "Rows · all at once", "compact" to "Compact · one at a time"), p.scoreStyle) { v -> set { it.copy(scoreStyle = v) } }
        }
    }
    item {
        SetCard("Your teams") {
            SwitchRow("Remind me before their matches", "10 minutes before they play, with the channel that shows it.", p.remindTeams) { v ->
                set { it.copy(remindTeams = v) }; if (v) askNotifications()
            }
            SwitchRow("Final scores", "Your team's result, once the match ends.", p.finalScores) { v ->
                set { it.copy(finalScores = v) }; if (v) askNotifications()
            }
        }
    }
}

// ------------------------------------------------------------ Title Optimizer

fun LazyListScope.titleItems(p: Prefs, set: ((Prefs) -> Prefs) -> Unit) {
    item { TitleCard(p, set) }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun TitleCard(p: Prefs, set: ((Prefs) -> Prefs) -> Unit) {
    val words = p.titleWords ?: com.arena.tv.data.TitleOptimizer.DEFAULT_WORDS
    val cfg = com.arena.tv.data.TitleOptimizer.configOf(p).copy(enabled = true)
    var input by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    SetCard {
        Text("Your playlist, cleaned. Strips clutter like 4K, FHD, HEVC and country tags from every channel, movie and series name.",
            color = ArenaColors.Muted, fontSize = 13.sp)
        SwitchRow("Enable Title Optimizer", "Off: names show exactly as your playlist sends them.", p.titleOptimizer) { v -> set { it.copy(titleOptimizer = v) } }
    }
    SetCard("Live preview") {
        listOf("|UK| Sky Sports Main Event FHD HEVC", "beIN SPORTS 1 (4K) ⚽", "AR: MBC 2 [HD] VIP").forEach { sample ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sample, color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("→  ", color = ArenaColors.Muted)
                Text(com.arena.tv.data.TitleOptimizer.clean(sample, cfg), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
        }
    }
    SetCard("Also remove") {
        SwitchRow("Country prefixes", "UK:, |AR|, [EN] at the start of a name.", p.titleCountry) { v -> set { it.copy(titleCountry = v) } }
        SwitchRow("Emojis and symbols", "⚽, ★, flags and similar.", p.titleEmoji) { v -> set { it.copy(titleEmoji = v) } }
    }
    SetCard("Words to remove") {
        Text("Select a word to remove it from the list. Matching ignores case and catches (HD), [4K] or |FHD|.", color = ArenaColors.Muted, fontSize = 12.sp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            words.forEach { w ->
                androidx.compose.material3.InputChip(selected = false, onClick = { set { it.copy(titleWords = words - w) } }, label = { Text(w) },
                    trailingIcon = { Text("✕", fontSize = 12.sp) }, modifier = Modifier.focusOutline())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            androidx.compose.material3.OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Add a word, tag or emoji") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { val w = input.trim(); if (w.isNotEmpty() && w !in words) set { it.copy(titleWords = words + w) }; input = "" },
                modifier = Modifier.focusOutline()) { Text("Add") }
        }
        TextButton(onClick = { set { it.copy(titleWords = null) } }, modifier = Modifier.focusOutline()) { Text("Reset to the default list") }
        Text("Channels update right away. Movies and series update on the next reload.", color = ArenaColors.Muted, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------ TV guide

fun LazyListScope.guideItems(vm: SettingsViewModel, playlists: List<com.arena.tv.data.Playlist>, running: Boolean) {
    item { GuideStats(vm, running) }
    playlists.filter { it.enabled }.forEach { pl -> item(key = "guide-${pl.id}") { GuideLinks(vm, pl) } }
    item { NoGuideList(vm) }
}

@Composable
private fun GuideStats(vm: SettingsViewModel, running: Boolean) {
    val with by vm.guideChannels.collectAsState()
    val total by vm.guideTotal.collectAsState()
    val without = (total - with).coerceAtLeast(0)
    val share = if (total > 0) with.toFloat() / total else 0f
    SetCard("Overview") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Stat("%,d".format(with), "With a guide", "${(share * 100).toInt()}% of channels", Modifier.weight(1f))
            Stat("%,d".format(without), "No guide yet", "Matched by name only", Modifier.weight(1f))
        }
        androidx.compose.material3.LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).size(height = 6.dp, width = 0.dp)
            .clip(RoundedCornerShape(50)), color = ArenaColors.Accent, trackColor = ArenaColors.SurfaceHi)
        Text("Channels with a guide are matched to games much more reliably. Adult channels aren't counted.", color = ArenaColors.Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            Button(onClick = vm::refreshGuide, enabled = !running, modifier = Modifier.focusOutline()) { Text(if (running) "Updating…" else "Reload whole guide") }
            OutlinedButton(onClick = { vm.refreshSportsGuide() }, enabled = !running, modifier = Modifier.focusOutline()) { Text("Sports channels only") }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, sub: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(ArenaColors.SurfaceHi).padding(12.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 14.sp)
        Text(sub, color = ArenaColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun GuideLinks(vm: SettingsViewModel, pl: com.arena.tv.data.Playlist) {
    var text by androidx.compose.runtime.remember(pl.id, pl.epgUrl) { androidx.compose.runtime.mutableStateOf(pl.epgUrl) }
    SetCard("Extra guide links · ${pl.name}") {
        Text(if (pl.type == com.arena.tv.data.SourceType.MAC) "XMLTV links used instead of the portal's guide. Separate several with commas."
            else "XMLTV links added to the provider's own guide, for channels it doesn't cover. Separate several with commas.",
            color = ArenaColors.Muted, fontSize = 12.sp)
        androidx.compose.material3.OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text("https://…/guide.xml.gz") },
            shape = RoundedCornerShape(12.dp), maxLines = 3)
        if (text.trim() != pl.epgUrl.trim()) Button(onClick = { vm.saveGuideLinks(pl.id, text) }, modifier = Modifier.padding(top = 8.dp).focusOutline()) { Text("Save and reload guide") }
    }
}

@Composable
private fun NoGuideList(vm: SettingsViewModel) {
    val with by vm.guideChannels.collectAsState()
    val list by androidx.compose.runtime.produceState<List<com.arena.tv.data.ChannelEntity>?>(null, with) { value = vm.withoutGuide() }
    var sportsOnly by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    SetCard("Channels without a guide") {
        SwitchRow("Sports channels only", null, sportsOnly) { sportsOnly = it }
        val shown = list.orEmpty().filter { !sportsOnly || com.arena.tv.matching.SportyGroups.isSporty(it.groupTitle) || com.arena.tv.matching.SportyGroups.isSporty(it.name) }
        when {
            list == null -> Text("Loading…", color = ArenaColors.Muted)
            shown.isEmpty() -> Text("Every channel here has a guide. 🎉", color = ArenaColors.Muted)
            else -> shown.take(60).forEach { ch ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.arena.tv.ui.components.Logo(ch.logo, ch.name, 28.dp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, fontSize = 14.sp, maxLines = 1)
                        Text(ch.groupTitle, color = ArenaColors.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text("No guide", color = ArenaColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------ Recording

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

private fun whenText(r: com.arena.tv.data.Recording): String {
    val d = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(r.start))
    return "$d · ${com.arena.tv.ui.components.Time.range(r.start, r.end)}"
}

fun LazyListScope.recordingItems(p: Prefs, set: ((Prefs) -> Prefs) -> Unit) {
    item { RecordingList() }
    item { RecordingStorage(p, set) }
    item {
        SetCard("Naming and limits") {
            ChoiceRow("File names", null, listOf("channel_date_title" to "Channel · Date · Title", "date_channel_title" to "Date · Channel · Title",
                "title_date" to "Title · Date"), p.recPattern) { v -> set { it.copy(recPattern = v) } }
            ChoiceRow("Keep recordings", null, listOf(0 to "Until I delete them", 7 to "7 days", 30 to "30 days", 90 to "90 days"), p.recKeepDays) { v -> set { it.copy(recKeepDays = v) } }
            ChoiceRow("Recordings at the same time", "Many providers allow one connection only: recording while watching another channel may not work.",
                listOf(1 to "1", 2 to "2", 3 to "3"), p.recMax) { v -> set { it.copy(recMax = v) } }
        }
    }
}

@Composable
private fun RecordingList() {
    val context = LocalContext.current
    val recs = context.app.recordings
    val all by recs.items.collectAsState()
    val list = all.filter { it.kind == "record" && it.status != "scheduled" }.sortedByDescending { it.start }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SetCard("Your recordings") {
        if (list.isEmpty()) Text("Nothing recorded yet. Press ● Record while watching a channel, or pick a show in the player's TV guide.",
            color = ArenaColors.Muted, fontSize = 13.sp)
        list.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (r.status == "recording") Text("● ", color = ArenaColors.Live, fontWeight = FontWeight.Bold)
                        Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Text("${r.channelName} · ${whenText(r)} · ${size(r.sizeBytes)}" + (r.error?.let { " · $it" } ?: ""),
                        color = if (r.status == "failed") ArenaColors.Live else ArenaColors.Muted, fontSize = 12.sp, maxLines = 2)
                }
                when (r.status) {
                    "recording" -> TextButton(onClick = { scope.launch { recs.cancel(r.id) } }, modifier = Modifier.focusOutline()) { Text("Stop") }
                    "done" -> r.path?.let { path -> TextButton(onClick = { com.arena.tv.ui.player.VodPlayerActivity.startFile(context, path, r.title, r.channelName) },
                        modifier = Modifier.focusOutline()) { Text("Play") } }
                }
                if (r.status != "recording") TextButton(onClick = { scope.launch { recs.delete(r.id) } }, modifier = Modifier.focusOutline()) { Text("Delete", color = ArenaColors.Live) }
            }
        }
    }
}

@Composable
private fun RecordingStorage(p: Prefs, set: ((Prefs) -> Prefs) -> Unit) {
    val recs = LocalContext.current.app.recordings
    val free = androidx.compose.runtime.remember(p.recFolder) { recs.freeBytes(p) }
    val volumes = androidx.compose.runtime.remember { recs.volumes() }
    SetCard("Storage") {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Free space", Modifier.weight(1f), fontSize = 16.sp)
            Text(size(free), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (volumes.size > 1) ChoiceRow("Save recordings to", "Plug in a USB drive or SD card to see it here.", volumes, p.recFolder) { v -> set { it.copy(recFolder = v) } }
        else Text("Saved in the app's storage. A USB drive or SD card shows up here when plugged in.", color = ArenaColors.Muted, fontSize = 12.sp)
        Text("About 1–3 GB per hour in HD.", color = ArenaColors.Muted, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------ Planner

fun LazyListScope.plannerItems(p: Prefs, set: ((Prefs) -> Prefs) -> Unit, askNotifications: () -> Unit) {
    item { PlannerPermissions(askNotifications) }
    item {
        SetCard("Timing") {
            ChoiceRow("Recording start", "Start recording before the scheduled time.", listOf(0 to "On time", 1 to "1 min early", 2 to "2 min early",
                5 to "5 min early", 10 to "10 min early"), p.planEarly) { v -> set { it.copy(planEarly = v) } }
            ChoiceRow("Recording end", "Keep recording after the scheduled end (extra time, delays).", listOf(0 to "Stop on time", 5 to "+5 min", 10 to "+10 min",
                15 to "+15 min", 30 to "+30 min"), p.planLate) { v -> set { it.copy(planLate = v) } }
            ChoiceRow("Reminder notification", "How early to notify you before a show starts.", listOf(1 to "1 minute early", 2 to "2 minutes early",
                5 to "5 minutes early", 10 to "10 minutes early", 15 to "15 minutes early"), p.planRemind) { v -> set { it.copy(planRemind = v) } }
        }
    }
    item { PlannedList() }
}

@Composable
private fun PlannerPermissions(askNotifications: () -> Unit) {
    val context = LocalContext.current
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Re-check when coming back from Android's settings.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val notifications = androidx.compose.runtime.remember(tick) { androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val battery = androidx.compose.runtime.remember(tick) {
        android.os.Build.VERSION.SDK_INT < 23 || context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    fun open(action: String, withPackage: Boolean) = runCatching {
        val i = android.content.Intent(action)
        if (withPackage) i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    SetCard("Permissions") {
        StatusRow("Notifications", if (notifications) "Reminders and final scores can be shown" else "Notifications are off — select to allow",
            if (notifications) "Allowed" else "Not allowed", notifications) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !notifications) askNotifications() else open(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, true)
        }
        StatusRow("Battery", if (battery) "Planned recordings and reminders run on time" else "Planned recordings may not start — select and set TuTu to \"Unrestricted\"",
            if (battery) "Unrestricted" else "Restricted", battery) { open(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, false) }
    }
}

@Composable
private fun StatusRow(title: String, desc: String, value: String, ok: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().focusOutline(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(desc, color = ArenaColors.Muted, fontSize = 12.sp)
        }
        Text(value, color = if (ok) ArenaColors.Text else ArenaColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlannedList() {
    val recs = LocalContext.current.app.recordings
    val all by recs.items.collectAsState()
    val list = all.filter { it.status == "scheduled" }.sortedBy { it.start }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SetCard("Planned") {
        if (list.isEmpty()) Text("Nothing planned. While watching, open the TV guide in the player and select a show to record it or get a reminder.",
            color = ArenaColors.Muted, fontSize = 13.sp)
        list.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.kind == "record") "● " else "⏰ ", color = if (r.kind == "record") ArenaColors.Live else ArenaColors.Accent)
                Column(Modifier.weight(1f)) {
                    Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${r.channelName} · ${whenText(r)}", color = ArenaColors.Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { scope.launch { recs.cancel(r.id) } }, modifier = Modifier.focusOutline()) { Text("Cancel") }
            }
        }
    }
}
