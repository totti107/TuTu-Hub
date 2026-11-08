package com.arena.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.tv.BuildConfig
import com.arena.tv.data.AppSettings
import com.arena.tv.data.Playlist
import com.arena.tv.data.SourceType
import com.arena.tv.sports.Leagues
import com.arena.tv.sports.Region
import com.arena.tv.sports.Sport
import com.arena.tv.ui.appViewModel
import com.arena.tv.ui.components.ArenaTopBar
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.PillTabs
import com.arena.tv.ui.components.QrCode
import com.arena.tv.ui.components.SectionHeader
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import com.arena.tv.app
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val channels by vm.channelCount.collectAsStateWithLifecycle()
    val programs by vm.programCount.collectAsStateWithLifecycle()
    val teams by vm.teams.collectAsStateWithLifecycle()
    val settings = s ?: return
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var categories by remember { mutableStateOf(false) }
    var addTeam by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var showAllLeagues by rememberSaveable { mutableStateOf(false) }
    val wide = LocalWide.current
    val followed = settings.followedSports.ifEmpty { Sport.entries.map { it.espn }.toSet() }

    // Android 13+ asks before an app can show notifications (team reminders, final scores).
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    val askNotifications: () -> Unit = { if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(if (wide) SettingsPage.PLAYLISTS else null) }
    if (wide && page == null) page = SettingsPage.PLAYLISTS
    val pageRequest = androidx.compose.ui.platform.LocalContext.current.app.settingsPage
    val requested by pageRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(requested) {
        requested?.let { r -> SettingsPage.entries.firstOrNull { it.name == r }?.let { page = it }; pageRequest.value = null }
    }
    androidx.activity.compose.BackHandler(enabled = !wide && page != null) { page = null }

    Column(Modifier.fillMaxSize()) {
        ArenaTopBar(left = {
            if (!wide && page != null) PillTabs(listOf("‹  ${page!!.label}"), 0) { page = null }
            else PillTabs(listOf("Settings"), 0) {}
        })
        Row(Modifier.fillMaxSize()) {
        if (wide || page == null) SettingsMenu(page, wide, if (wide) Modifier.width(270.dp).fillMaxHeight() else Modifier.fillMaxSize()) { page = it }
        val pg = page
        if (pg != null) LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 0.dp, end = if (wide) 32.dp else 0.dp, bottom = 40.dp)) {
            if (pg == SettingsPage.PLAYLISTS) {
            // ---------------- Playlists ----------------
            item { SectionHeader("Playlists") }
            if (settings.playlists.size > 1) item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Show", color = ArenaColors.Muted, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(modifier = Modifier.focusOutline(), selected = settings.activePlaylist.isEmpty(), onClick = { vm.setActive("") }, label = { Text("All combined") })
                        settings.playlists.filter { it.enabled }.forEach { p ->
                            FilterChip(modifier = Modifier.focusOutline(), selected = settings.activePlaylist == p.id, onClick = { vm.setActive(p.id) }, label = { Text(p.name) })
                        }
                    }
                }
            }
            items(settings.playlists, key = { it.id }) { p ->
                PlaylistCard(p, settings.activePlaylist, refresh.running, onEdit = { editing = p }, onReload = { vm.reload(p.id) },
                    onEnabled = { vm.setEnabled(p.id, it) }, onDelete = { deleting = p })
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = Playlist(id = "", name = "Playlist ${settings.playlists.size + 1}") }) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add playlist")
                    }
                    if (settings.playlists.isNotEmpty()) {
                        OutlinedButton(onClick = { vm.reload(null) }, enabled = !refresh.running) { Text("Reload all") }
                        OutlinedButton(onClick = vm::refreshGuide, enabled = !refresh.running) { Text("Reload guide") }
                    }
                }
            }
            item {
                Card {
                    if (refresh.steps.isNotEmpty()) LoadChecklist(refresh.steps)
                    if (refresh.running) {
                        Text(refresh.message ?: "Working…", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        refresh.error?.let { Text("⚠ $it", color = ArenaColors.Live, fontSize = 13.sp); Spacer(Modifier.height(6.dp)) }
                        Text("Showing ${"%,d".format(channels)} channels · ${"%,d".format(programs)} guide entries", fontWeight = FontWeight.SemiBold)
                        if (settings.playlists.isNotEmpty() && !settings.anyGuide) Text("No TV guide URL — matching uses channel names only", color = ArenaColors.Muted, fontSize = 12.sp)
                    }
                }
            }

            // ---------------- Categories ----------------
            item { SectionHeader("Channel categories") }
            item {
                Card {
                    Text("Turn off categories you never watch. They disappear from Live TV and from channel matching.", color = ArenaColors.Muted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("${settings.hiddenGroups.size} hidden", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { categories = true }, enabled = settings.playlists.isNotEmpty()) { Text("Choose categories") }
                    }
                }
            }

            }
            if (pg == SettingsPage.UPDATES) {
            item { SectionHeader("App updates") }
            item { UpdateCard() }
            }

            if (pg == SettingsPage.PROTECTION) {
            // ---------------- Parental control ----------------
            item { SectionHeader("Adult channels") }
            item { ParentalCard(vm, settings) }

            }
            if (pg == SettingsPage.SPORTS) {
            item { SectionHeader("Sports") }
            sportsWatchItems(settings.prefs, vm::updatePrefs, askNotifications)
            // ---------------- Sports & teams ----------------
            item { SectionHeader("My sports & teams") }
            item {
                Card {
                    Text("Sports shown in the Sports section", color = ArenaColors.Muted, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Sport.entries.forEach { sp ->
                            FilterChip(modifier = Modifier.focusOutline(), selected = sp.espn in followed, onClick = { vm.toggleSport(sp) }, label = { Text("${sp.emoji} ${sp.label}") })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Odds format", Modifier.weight(1f), color = ArenaColors.Muted, fontSize = 13.sp)
                        FilterChip(modifier = Modifier.focusOutline(), selected = settings.decimalOdds, onClick = { vm.setDecimalOdds(true) }, label = { Text("Decimal 2.50") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(modifier = Modifier.focusOutline(), selected = !settings.decimalOdds, onClick = { vm.setDecimalOdds(false) }, label = { Text("American +150") })
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Teams you follow", Modifier.weight(1f), color = ArenaColors.Muted, fontSize = 13.sp)
                        TextButton(onClick = { addTeam = true }) { Icon(Icons.Rounded.Add, null); Text("Add team") }
                    }
                    if (teams.isEmpty()) Text("Follow teams to see their games first and in the Following tab. You can also tap ☆ on any game.",
                        color = ArenaColors.Muted, fontSize = 12.sp)
                    teams.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Logo(t.logo, t.name, 28.dp); Spacer(Modifier.width(12.dp))
                            Text(t.name, Modifier.weight(1f))
                            IconButton(onClick = { vm.removeTeam(t.key) }) { Icon(Icons.Rounded.Close, "Unfollow") }
                        }
                    }
                }
            }
            item { SectionHeader("Leagues") }
            Sport.entries.filter { it.espn in followed }.forEach { sport ->
                val list = Leagues.all.filter { it.sport == sport && (showAllLeagues || it.defaultOn || it.key in settings.enabledLeagues) }
                if (list.isEmpty()) return@forEach
                item(key = "sport-${sport.name}") {
                    Text("${sport.emoji}  ${sport.label}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 2.dp))
                }
                items(list, key = { it.key }) { l ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleLeague(l.key) }.padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, Modifier.weight(1f))
                        Switch(checked = l.key in settings.enabledLeagues, onCheckedChange = { vm.toggleLeague(l.key) })
                    }
                }
            }
            if (!showAllLeagues) item { TextButton(onClick = { showAllLeagues = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Show more leagues") } }

            item { SectionHeader("Where are your channels from?") }
            item {
                Text("Helps guess channels when the TV guide is missing (e.g. Premier League → Sky Sports in the UK, beIN in MENA). Leave empty for all.",
                    color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Region.entries.forEach { r -> FilterChip(modifier = Modifier.focusOutline(), selected = r in settings.regions, onClick = { vm.toggleRegion(r) }, label = { Text(r.label) }) }
                }
            }

            }
            if (pg == SettingsPage.ACCOUNTS) {
            item { SectionHeader("Trakt") }
            item { TraktCard(vm, settings) }

            }
            if (pg == SettingsPage.UPDATES) {
            item { SectionHeader("About") }
            item {
                Text("TuTu v${BuildConfig.VERSION_NAME} · Scores & stats: ESPN public data. This app contains no channels; it plays the playlists you add.",
                    color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            }
            if (pg == SettingsPage.PLAYBACK) { item { SectionHeader("Playback") }; playbackItems(settings.prefs, vm::updatePrefs) }
            if (pg == SettingsPage.BROWSING) { item { SectionHeader("Browsing") }; browsingItems(settings.prefs, vm::updatePrefs) }
            if (pg == SettingsPage.RECORDING) { item { SectionHeader("Recording") }; recordingItems(settings.prefs, vm::updatePrefs) }
            if (pg == SettingsPage.PLANNER) { item { SectionHeader("Planner") }; plannerItems(settings.prefs, vm::updatePrefs, askNotifications) }
            if (pg == SettingsPage.TITLES) { item { SectionHeader("Title Optimizer") }; titleItems(settings.prefs) { f -> vm.updateTitles(f) } }
            if (pg == SettingsPage.GUIDE) { item { SectionHeader("TV guide") }; guideItems(vm, settings.playlists, refresh.running) }
            if (pg == SettingsPage.BACKUP) { item { SectionHeader("Backup & restore") }; backupItems(vm) }
        }
        }
    }

    editing?.let { p -> PlaylistEditor(vm, p, onDismiss = { editing = null; vm.stopPhoneEntry() }) { vm.savePlaylist(it); editing = null; vm.stopPhoneEntry() } }
    if (categories) CategoriesDialog(vm, settings) { categories = false }
    if (addTeam) TeamSearchDialog(vm) { addTeam = false }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = ArenaColors.Surface,
            title = { Text("Remove ${p.name}?") }, text = { Text("Its channels, movies and series are removed from the app.") },
            confirmButton = { TextButton(onClick = { vm.removePlaylist(p.id); deleting = null }) { Text("Remove", color = ArenaColors.Live) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

/** 1 Sports center · 2 Live TV · 3 Movies · 4 Series, each ticked off as it becomes usable. */
@Composable
private fun LoadChecklist(steps: List<com.arena.tv.data.LoadStep>) {
    Column(Modifier.padding(bottom = 4.dp)) {
        steps.forEachIndexed { i, st ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when (st.status) {
                        com.arena.tv.data.StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        com.arena.tv.data.StepStatus.DONE -> Text("✓", color = ArenaColors.Accent, fontWeight = FontWeight.Bold)
                        com.arena.tv.data.StepStatus.FAILED -> Text("!", color = ArenaColors.Live, fontWeight = FontWeight.Bold)
                        com.arena.tv.data.StepStatus.SKIPPED -> Text("–", color = ArenaColors.Muted)
                        com.arena.tv.data.StepStatus.WAITING -> Text("${i + 1}", color = ArenaColors.Muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(st.label, fontWeight = if (st.status == com.arena.tv.data.StepStatus.RUNNING) FontWeight.Bold else FontWeight.Medium,
                    color = if (st.status == com.arena.tv.data.StepStatus.WAITING) ArenaColors.Muted else ArenaColors.Text)
                st.detail?.let { Text("  ·  $it", color = ArenaColors.Muted, fontSize = 13.sp) }
                if (st.status == com.arena.tv.data.StepStatus.DONE && i <= 1) Text("  ready", color = ArenaColors.Accent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PlaylistCard(p: Playlist, active: String, busy: Boolean, onEdit: () -> Unit, onReload: () -> Unit, onEnabled: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (active == p.id) Text("  SHOWING", color = ArenaColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(when (p.type) {
                    SourceType.XTREAM -> "Xtream · ${p.server.removePrefix("http://").removePrefix("https://")}"
                    SourceType.MAC -> "MAC portal · ${com.arena.tv.data.StalkerClient.normalizeMac(p.mac)}"
                    else -> "M3U link"
                },
                    color = ArenaColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(p.enabled, onEnabled)
        }
        if (p.lastRefresh > 0) Text(
            "${"%,d".format(p.channelCount)} channels · " + (if (p.loadMovies) "${"%,d".format(p.movieCount)} movies" else "movies off") + " · " +
                (if (p.loadSeries) "${"%,d".format(p.seriesCount)} series" else "series off") + " · updated ${ago(p.lastRefresh)}",
            fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (p.account.isNotBlank()) Text(p.account, color = ArenaColors.Muted, fontSize = 12.sp)
        p.error?.let { Text("⚠ $it", color = ArenaColors.Live, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onReload, enabled = !busy && p.enabled) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Text(" Reload") }
            TextButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Text(" Edit") }
            TextButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = ArenaColors.Live); Text(" Remove", color = ArenaColors.Live) }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
        .background(ArenaColors.Surface).padding(14.dp)) { content() }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t))
    }
}

// ------------------------------------------------------------ playlist editor

@Composable
private fun PlaylistEditor(vm: SettingsViewModel, start: Playlist, onDismiss: () -> Unit, onSave: (Playlist) -> Unit) {
    var p by remember { mutableStateOf(if (start.type == SourceType.NONE) start.copy(type = SourceType.XTREAM) else start) }
    var showPass by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(start.epgUrl.isNotBlank() || start.userAgent.isNotBlank()) }
    var phone by remember { mutableStateOf(false) }
    val url by vm.phoneUrl.collectAsStateWithLifecycle()
    val draft by vm.phoneDraft.collectAsStateWithLifecycle()
    val wide = LocalWide.current
    // Details typed on the phone fill the form.
    LaunchedEffect(draft) { draft?.let { d -> p = p.copy(name = d.name, type = d.type, m3uUrl = d.m3uUrl, server = d.server, user = d.user, pass = d.pass,
        mac = d.mac, epgUrl = d.epgUrl.ifBlank { p.epgUrl }, userAgent = d.userAgent.ifBlank { p.userAgent }); phone = false; vm.phoneDraft.value = null } }
    DisposableEffect(phone) { if (phone) vm.startPhoneEntry() else vm.stopPhoneEntry(); onDispose { } }

    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.94f).dp
    fun save() { if (p.valid) onSave(p.copy(m3uUrl = p.m3uUrl.trim(), server = p.server.trim(), user = p.user.trim(), mac = p.mac.trim())) }
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(0.94f).heightIn(max = maxH).clip(RoundedCornerShape(22.dp)).background(ArenaColors.Surface).padding(20.dp)) {
            // Header with Save always visible, so it's reachable with a TV remote even when the form is long.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (start.id.isEmpty()) "Add playlist" else "Edit playlist", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!phone) Button(onClick = ::save, enabled = p.valid) { Text("Save") }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            if (phone) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        url == null -> CircularProgressIndicator()
                        url!!.isEmpty() -> Text("No Wi-Fi / network address found. Connect the TV to your home network and try again.", color = ArenaColors.Live)
                        else -> {
                            Text("Scan with your phone's camera", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text("Your phone must be on the same Wi-Fi as this device.", color = ArenaColors.Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            QrCode(url!!, if (wide) 240.dp else 200.dp)
                            Spacer(Modifier.height(10.dp))
                            Text(url!!, color = ArenaColors.Muted, fontSize = 13.sp)
                            Text("Waiting for your phone…", color = ArenaColors.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    TextButton(onClick = { phone = false }) { Text("Type here instead") }
                }
                return@Column
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                OutlinedButton(onClick = { phone = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.QrCode2, null); Spacer(Modifier.width(8.dp)); Text("Type it on my phone (QR code)")
                }
                Spacer(Modifier.height(8.dp))
                Field(p.name, { p = p.copy(name = it) }, "Name", actions = next)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    val types = listOf(SourceType.XTREAM to "Xtream Codes", SourceType.M3U to "M3U link", SourceType.MAC to "MAC portal")
                    types.forEachIndexed { i, (t, label) ->
                        SegmentedButton(selected = p.type == t, onClick = { p = p.copy(type = t) }, shape = SegmentedButtonDefaults.itemShape(i, types.size)) { Text(label, maxLines = 1) }
                    }
                }
                when (p.type) {
                    SourceType.M3U -> {
                        Field(p.m3uUrl, { p = p.copy(m3uUrl = it) }, "Playlist URL (http://…)", KeyboardType.Uri, done = ::save)
                        parseXtreamLink(p.m3uUrl)?.let { (sv, u, pw) ->
                            TextButton(onClick = { p = p.copy(type = SourceType.XTREAM, server = sv, user = u, pass = pw) }) {
                                Text("This is an Xtream link — use Xtream mode (faster, includes TV guide, movies & series)")
                            }
                        }
                    }
                    SourceType.MAC -> {
                        Field(p.server, { p = p.copy(server = it) }, "Portal URL (http://host:port/c/)", KeyboardType.Uri, actions = next)
                        Field(p.mac, { p = p.copy(mac = it) }, "MAC address (00:1A:79:XX:XX:XX)", KeyboardType.Ascii, done = ::save)
                        Text("Use the MAC address your provider registered. Live TV and the portal's TV guide are loaded; movies & series from MAC portals aren't supported yet.",
                            color = ArenaColors.Muted, fontSize = 12.sp)
                    }
                    else -> {
                        Field(p.server, { p = p.copy(server = it) }, "Server (http://host:port)", KeyboardType.Uri, actions = next)
                        Field(p.user, { p = p.copy(user = it) }, "Username", actions = next)
                        OutlinedTextField(p.pass, { p = p.copy(pass = it) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Password") }, singleLine = true,
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); save() }),
                            trailingIcon = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show password") } })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Stream type", Modifier.weight(1f), color = ArenaColors.Muted, fontSize = 13.sp)
                            listOf("auto" to "Auto", "ts" to "MPEG-TS", "m3u8" to "HLS").forEach { (f, l) ->
                                FilterChip(modifier = Modifier.padding(start = 6.dp).focusOutline(), selected = p.format == f, onClick = { p = p.copy(format = f) }, label = { Text(l) })
                            }
                        }
                        if (p.format == "auto") Text("Auto tries MPEG-TS first and switches to HLS if a channel won't play.", color = ArenaColors.Muted, fontSize = 12.sp)
                    }
                }
                if (p.type != SourceType.MAC) {
                    Text("Load", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(modifier = Modifier.focusOutline(), selected = true, onClick = {}, label = { Text("Live TV") }, enabled = false)
                        FilterChip(modifier = Modifier.focusOutline(), selected = p.loadMovies, onClick = { p = p.copy(loadMovies = !p.loadMovies) }, label = { Text("Movies") })
                        FilterChip(modifier = Modifier.focusOutline(), selected = p.loadSeries, onClick = { p = p.copy(loadSeries = !p.loadSeries) }, label = { Text("Series") })
                    }
                    Text("Big providers list hundreds of thousands of movies and series. Turning them off makes loading much faster; they always load after Sports and Live TV.",
                        color = ArenaColors.Muted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { p = p.copy(guideSportsOnly = !p.guideSportsOnly) }.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("TV guide for sports categories only")
                        Text("Fastest. The Sports center works the same; other channels won't show what's on.", color = ArenaColors.Muted, fontSize = 12.sp)
                    }
                    Switch(p.guideSportsOnly, { p = p.copy(guideSportsOnly = it) })
                }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced: TV guide URL, user agent") }
                if (advanced) {
                    Field(p.epgUrl, { p = p.copy(epgUrl = it) }, "TV guide (XMLTV) URL — optional", KeyboardType.Uri, actions = next)
                    Field(p.userAgent, { p = p.copy(userAgent = it) }, "User-Agent — optional", done = ::save)
                }
            }
            Button(onClick = ::save, enabled = p.valid, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Save & load") }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, kb: KeyboardType = KeyboardType.Text,
                  actions: KeyboardActions? = null, done: (() -> Unit)? = null) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb, autoCorrectEnabled = false, imeAction = if (done != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = actions ?: KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus(); done?.invoke() }))
}

/** http://host:port/get.php?username=u&password=p&type=m3u_plus -> (http://host:port, u, p) */
private fun parseXtreamLink(url: String): Triple<String, String, String>? {
    if (!url.contains("get.php", true)) return null
    val q = url.substringAfter('?', "").split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
    val u = q["username"] ?: return null
    val p = q["password"] ?: return null
    return Triple(url.substringBefore("/get.php"), u, p)
}

// ------------------------------------------------------------ categories

@Composable
private fun CategoriesDialog(vm: SettingsViewModel, settings: AppSettings, onDismiss: () -> Unit) {
    val groups by vm.allGroups.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val multi = settings.playlists.count { it.enabled } > 1
    val shown = groups.filter { q.isBlank() || it.groupTitle.contains(q, true) }
    val keys = shown.map { "${it.playlistId}|${it.groupTitle}" }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.94f).fillMaxSize(0.9f).clip(RoundedCornerShape(22.dp)).background(ArenaColors.Surface).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Channel categories", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search categories") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                OutlinedButton(onClick = { vm.setAllHidden(keys, false) }) { Text("Show all") }
                OutlinedButton(onClick = { vm.setAllHidden(keys, true) }) { Text("Hide all") }
            }
            if (groups.isEmpty()) Text("Load a playlist first.", color = ArenaColors.Muted)
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.playlistId + "|" + it.groupTitle }) { g ->
                    val key = "${g.playlistId}|${g.groupTitle}"
                    val on = key !in settings.hiddenGroups
                    Row(Modifier.fillMaxWidth().clickable { vm.setGroupHidden(key, on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.groupTitle, color = if (on) ArenaColors.Text else ArenaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${g.count} channels" + if (multi) " · ${settings.playlist(g.playlistId)?.name ?: ""}" else "", color = ArenaColors.Muted, fontSize = 12.sp)
                        }
                        Switch(on, { vm.setGroupHidden(key, !it) })
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ teams

@Composable
private fun TeamSearchDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by vm.teamResults.collectAsStateWithLifecycle()
    val loading by vm.teamLoading.collectAsStateWithLifecycle()
    val followed by vm.teams.collectAsStateWithLifecycle()
    val keys = followed.map { it.key }.toSet()
    LaunchedEffect(q) { kotlinx.coroutines.delay(250); vm.searchTeams(q) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(0.94f).fillMaxSize(0.9f).clip(RoundedCornerShape(22.dp)).background(ArenaColors.Surface).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Follow a team", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Team name") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(14.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            Text("Teams from the leagues you turned on.", color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            LazyColumn {
                items(results, key = { it.team.key(it.league.sport) }) { r ->
                    val key = r.team.key(r.league.sport)
                    val on = key in keys
                    Row(Modifier.fillMaxWidth().clickable { if (on) vm.removeTeam(key) else vm.followTeam(r) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Logo(r.team.logo, r.team.displayName, 32.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.team.displayName)
                            Text("${r.league.sport.emoji} ${r.league.name}", color = ArenaColors.Muted, fontSize = 12.sp)
                        }
                        Icon(if (on) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (on) "Following" else "Follow",
                            tint = if (on) ArenaColors.Gold else ArenaColors.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun TraktCard(vm: SettingsViewModel, settings: AppSettings) {
    val t by vm.trakt.collectAsStateWithLifecycle()
    var id by rememberSaveable { mutableStateOf(settings.trakt.clientId) }
    var secret by rememberSaveable { mutableStateOf(settings.trakt.clientSecret) }
    Card {
        when {
            t.connected -> {
                Text("Connected as ${t.username.ifBlank { "your Trakt account" }}", fontWeight = FontWeight.SemiBold)
                Text("Movies and episodes you watch are scrobbled automatically. Your watchlist shows up in Movies and Series.",
                    color = ArenaColors.Muted, fontSize = 13.sp)
                if (t.lastSync > 0) Text("Synced ${ago(t.lastSync)} · ${t.watchlist.size} on your watchlist",
                    color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { vm.traktSync() }) { Text("Sync now") }
                    OutlinedButton(onClick = { vm.traktDisconnect() }) { Text("Disconnect") }
                }
            }
            t.login != null -> {
                val l = t.login!!
                Text("On your phone or computer, go to", color = ArenaColors.Muted)
                Text(l.url.removePrefix("https://"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("and enter this code:", color = ArenaColors.Muted, modifier = Modifier.padding(top = 6.dp))
                Text(l.userCode, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = ArenaColors.Accent)
                Text("Waiting for you to approve…", color = ArenaColors.Muted, fontSize = 12.sp)
                TextButton(onClick = { vm.traktCancel() }) { Text("Cancel") }
            }
            else -> {
                Text("Connect Trakt to track what you watch, sync watched badges, and see your watchlist.", fontSize = 14.sp)
                Text("One-time setup: on trakt.tv go to Settings → Your API Apps → New application. Use any name and the redirect URI " +
                    "urn:ietf:wg:oauth:2.0:oob, then paste the Client ID and Client Secret here.",
                    color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                Field(id, { id = it }, "Client ID")
                Field(secret, { secret = it }, "Client Secret")
                t.error?.let { Text("⚠ $it", color = ArenaColors.Live, fontSize = 13.sp) }
                Button(onClick = { vm.traktConnect(id, secret) }, enabled = id.isNotBlank() && secret.isNotBlank() && !t.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (t.busy) "Contacting Trakt…" else "Connect Trakt") }
            }
        }
    }
}


@Composable
private fun ParentalCard(vm: SettingsViewModel, settings: AppSettings) {
    var step by remember { mutableStateOf(0) } // 1 = confirm current PIN, 2 = create new PIN
    Card {
        Text("Adult categories are locked with a PIN and kept out of sports matching.", color = ArenaColors.Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().clickable { vm.setHideAdult(!settings.hideAdult) }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hide adult categories completely")
                Text("Instead of showing them locked", color = ArenaColors.Muted, fontSize = 12.sp)
            }
            Switch(settings.hideAdult, { vm.setHideAdult(it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { step = if (settings.parentalPin.isEmpty()) 2 else 1 }) { Text(if (settings.parentalPin.isEmpty()) "Set PIN" else "Change PIN") }
            OutlinedButton(onClick = vm::lockNow) { Text("Lock now") }
        }
    }
    if (step == 1) com.arena.tv.ui.components.PinDialog("Current PIN", creating = false, onDismiss = { step = 0 }, check = vm::checkPin) { step = 2 }
    if (step == 2) com.arena.tv.ui.components.PinDialog("New PIN", creating = true, onDismiss = { step = 0 },
        check = { pin -> vm.resetPin(pin); true }) { step = 0 }
}

@Composable
private fun UpdateCard() {
    val updater = androidx.compose.ui.platform.LocalContext.current.app.updater
    val state by updater.state.collectAsState()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TuTu ${updater.currentVersion}", fontWeight = FontWeight.SemiBold)
                Text(when (state) {
                    com.arena.tv.data.Updater.State.Checking -> "Checking…"
                    com.arena.tv.data.Updater.State.UpToDate -> "You have the latest version"
                    is com.arena.tv.data.Updater.State.Available, is com.arena.tv.data.Updater.State.Ready,
                    is com.arena.tv.data.Updater.State.NeedsPermission -> "A new version is available"
                    is com.arena.tv.data.Updater.State.Downloading -> "Downloading…"
                    else -> "Checks automatically when the app opens"
                }, color = ArenaColors.Muted, fontSize = 13.sp)
            }
            OutlinedButton(onClick = { updater.check(manual = true) }, modifier = Modifier.focusOutline()) { Text("Check for updates") }
        }
    }
}
