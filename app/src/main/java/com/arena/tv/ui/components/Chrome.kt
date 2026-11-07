package com.arena.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import kotlinx.coroutines.delay

/** Wide = tablet / TV layout (multi-pane). */
val LocalWide = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Medium screens (phones in landscape, small tablets): tighter header so every control fits. */
val LocalCompactChrome = androidx.compose.runtime.staticCompositionLocalOf { false }

/** App sections, reachable from the brand pill on wide screens (there's no side bar, so panes use the full width). */
class SectionNav(val labels: List<Pair<String, ImageVector>>, val current: Int, val go: (Int) -> Unit)
val LocalSectionNav = androidx.compose.runtime.staticCompositionLocalOf<SectionNav?> { null }

/** The "tutu • 9:56 PM ▾" pill that hangs from the top edge. Tap it to switch section. */
@Composable
fun BrandPill(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var open by remember { mutableStateOf(false) }
    val nav = LocalSectionNav.current
    val compact = LocalCompactChrome.current
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(Color(0xFF1C1D20)).clickable(enabled = nav != null) { open = true }.padding(horizontal = if (compact) 12.dp else 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("tutu", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = (-0.5).sp)
            Box(Modifier.padding(start = 2.dp, bottom = 12.dp).size(6.dp).clip(CircleShape).background(ArenaColors.Accent))
            Box(Modifier.padding(horizontal = if (compact) 8.dp else 12.dp).width(1.dp).height(18.dp).background(ArenaColors.Outline))
            // The clock is always shown; the section name only when there's room.
            if (!compact) nav?.let {
                Icon(it.labels[it.current].second, it.labels[it.current].first, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(" ${it.labels[it.current].first}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(18.dp).background(ArenaColors.Outline))
            }
            Text(Time.clock(now), color = Color.White, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (nav != null) Icon(Icons.Rounded.ArrowDropDown, null, tint = ArenaColors.Muted, modifier = Modifier.size(20.dp))
        }
        if (nav != null) DropdownMenu(open, { open = false }, containerColor = ArenaColors.Surface) {
            nav.labels.forEachIndexed { i, (label, icon) ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 17.sp, fontWeight = if (i == nav.current) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = { Icon(icon, null, tint = if (i == nav.current) ArenaColors.Accent else ArenaColors.Text) },
                    onClick = { open = false; nav.go(i) },
                )
            }
        }
    }
}

/**
 * Screen header: tabs on the left, brand pill in the middle, actions on the right.
 * The pill has its own slot (it never covers buttons); each side scrolls sideways if the screen is narrow.
 */
@Composable
fun ArenaTopBar(left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit = {}) {
    val wide = LocalWide.current
    if (wide) {
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = left)
            }
            BrandPill()
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, content = right)
            }
        }
    } else {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically, content = left)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically, content = right)
        }
    }
}

/** Slim strip above multi-pane screens holding the brand pill, so it never sits on top of a pane's buttons. */
@Composable
fun PillStrip() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { BrandPill() }
}

/** "For you | Scores | Following" — selected tab is a white pill. */
@Composable
fun PillTabs(tabs: List<String>, selected: Int, compact: Boolean = false, onSelect: (Int) -> Unit) {
    val wide = LocalWide.current && !compact && !LocalCompactChrome.current
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color(0xFFF1F1F1) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = if (wide) 18.dp else if (compact || LocalCompactChrome.current) 11.dp else 14.dp, vertical = 8.dp),
            ) {
                Text(label, color = if (sel) Color(0xFF111111) else ArenaColors.Muted,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, fontSize = if (wide) 19.sp else 16.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun TabDivider() = Box(Modifier.padding(horizontal = if (LocalCompactChrome.current) 4.dp else 10.dp).width(1.dp).height(24.dp).background(ArenaColors.Outline))

@Composable
fun ToggleLabel(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalCompactChrome.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }.padding(horizontal = 6.dp)) {
        Text(label, color = ArenaColors.Text, fontSize = if (c) 15.sp else 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(if (c) 2.dp else 8.dp))
        Switch(checked, onChange, modifier = if (c) Modifier.scale(0.8f) else Modifier, colors = SwitchDefaults.colors(
            checkedTrackColor = ArenaColors.Accent, uncheckedTrackColor = ArenaColors.SurfaceHi, uncheckedBorderColor = ArenaColors.SurfaceHi,
            uncheckedThumbColor = Color.White))
    }
}

/** "Soccer ▾" style dropdown. */
@Composable
fun <T> DropChip(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val c = LocalCompactChrome.current
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = if (c) 4.dp else 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ArenaColors.Text, fontSize = if (c) 15.sp else 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, null, tint = ArenaColors.Muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = ArenaColors.Surface) {
            options.forEach { (v, l) ->
                DropdownMenuItem(
                    text = { Text(l) }, onClick = { onSelect(v); open = false },
                    trailingIcon = if (v == selected) ({ Icon(Icons.Rounded.Check, null, tint = ArenaColors.Accent) }) else null,
                )
            }
        }
    }
}

@Composable
fun IconAction(icon: ImageVector, desc: String, tint: Color = ArenaColors.Text, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** White primary / outlined secondary buttons, like the screenshots. */
@Composable
fun WhiteButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = Color.Black, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, iconTint: Color = Color.White) {
    OutlinedButton(onClick = onClick, modifier = modifier.focusOutline(RoundedCornerShape(10.dp)), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0x40FFFFFF)),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Color(0x22000000), contentColor = Color.White)) {
        icon?.let { Icon(it, null, tint = iconTint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Left-pane list item: "Favorites            12". */
@Composable
fun PaneItem(label: String, count: Int?, selected: Boolean, onClick: () -> Unit, dim: Boolean = false) {
    val c = LocalCompactChrome.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) ArenaColors.SurfaceHi else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = if (c) 14.dp else 18.dp, vertical = if (c) 9.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = if (dim) ArenaColors.Muted else ArenaColors.Text, fontSize = if (c) 15.sp else 17.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        count?.let { Text("%,d".format(it), color = ArenaColors.Muted, fontSize = 14.sp) }
    }
}

@Composable
fun PaneLabel(text: String) =
    Text(text.uppercase(), color = ArenaColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp))

/** Rounded dark panel used for the panes on wide screens. */
fun Modifier.panel(): Modifier = this.clip(RoundedCornerShape(22.dp)).background(ArenaColors.Panel)
    .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(22.dp))

@Composable
fun WideAware(content: @Composable (wide: Boolean) -> Unit) = BoxWithConstraints { content(maxWidth >= 840.dp) }
