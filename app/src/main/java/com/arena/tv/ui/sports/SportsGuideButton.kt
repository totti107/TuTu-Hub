package com.arena.tv.ui.sports

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.tv.app
import com.arena.tv.data.StepStatus
import com.arena.tv.ui.components.LocalCompactChrome
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import kotlinx.coroutines.launch

/**
 * "Sports guide" button: reloads the TV guide for sports channels only (much faster than the whole guide),
 * so the "where to watch" channel lists are up to date.
 */
@Composable
fun SportsGuideButton() {
    val context = LocalContext.current
    val c = context.app
    val state by c.iptv.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val compact = LocalCompactChrome.current
    Row(
        Modifier.focusOutline(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).clickable(enabled = !state.running) {
            if (!c.settings.flowHasSource) { Toast.makeText(context, "Add a playlist in Settings first", Toast.LENGTH_SHORT).show(); return@clickable }
            Toast.makeText(context, "Refreshing the sports TV guide…", Toast.LENGTH_SHORT).show()
            scope.launch {
                c.iptv.refreshSportsGuide()
                val st = c.iptv.state.value
                val msg = st.error?.let { "Sports guide: $it" }
                    ?: st.steps.firstOrNull()?.takeIf { it.status == StepStatus.DONE }?.detail?.let { "✓ Sports guide updated · $it" }
                    ?: "✓ Sports guide updated"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.EventRepeat, "Refresh sports guide", tint = ArenaColors.Text, modifier = Modifier.size(22.dp))
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(if (state.running) "Updating…" else "Sports guide", fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
