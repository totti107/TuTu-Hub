package com.arena.tv.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arena.tv.MainActivity
import com.arena.tv.R
import com.arena.tv.app
import com.arena.tv.sports.DayOffset
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Leagues
import com.arena.tv.sports.Sport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings → Sports → Your teams: a reminder 10 minutes before your teams play (with a channel showing it)
 * and the final score when the game ends. Alarms are set whenever scores load, plus a check every few hours.
 */
class Reminders(private val context: Context) {
    companion object {
        const val CHANNEL = "teams"
        private const val ACTION = "com.arena.tv.REMINDER"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Roughly how long each sport's games last, to know when to look for the final score. */
        fun expectedMinutes(sport: Sport): Int = when (sport) {
            Sport.SOCCER -> 115; Sport.BASKETBALL -> 150; Sport.FOOTBALL -> 200; Sport.HOCKEY -> 160
            Sport.BASEBALL -> 190; Sport.RUGBY -> 110; Sport.AUSSIE -> 140; else -> 150
        }
    }

    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private fun pending(kind: String, game: Game?, extra: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("kind", kind).apply {
            game?.let { putExtra("id", it.id); putExtra("league", it.league.key); putExtra("start", it.startMillis); putExtra("title", it.headline) }
            extra()
        }
        val code = (kind + (game?.id ?: "")).hashCode()
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun at(time: Long, pi: PendingIntent) = runCatching {
        if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi) else alarms.set(AlarmManager.RTC_WAKEUP, time, pi)
    }

    /** A background check every ~4 hours so reminders are set even on days the app isn't opened. */
    fun startDailyCheck() = runCatching {
        alarms.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 60_000, 4 * AlarmManager.INTERVAL_HOUR, pending("check", null))
    }

    /** Sets alarms for today's games of the teams you follow. */
    suspend fun schedule(games: List<Game>) {
        val c = context.app
        val p = c.settings.current().prefs
        if (!p.remindTeams && !p.finalScores) return
        val teams = c.db.user().teams().first().map { it.key }.toSet()
        if (teams.isEmpty()) return
        val now = System.currentTimeMillis()
        for (g in games) {
            if (g.home.key(g.league.sport) !in teams && g.away.key(g.league.sport) !in teams) continue
            if (p.remindTeams && g.state == GameState.PRE && g.startMillis > now + 60_000)
                at(maxOf(now + 5_000, g.startMillis - 10 * 60_000), pending("remind", g))
            if (p.finalScores && g.state != GameState.POST)
                at(maxOf(now + 60_000, g.startMillis + Reminders.expectedMinutes(g.league.sport) * 60_000L), pending("final", g) { putExtra("tries", 0) })
        }
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Your teams", NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders and final scores for the teams you follow" })
    }

    private fun notify(id: Int, title: String, text: String, tap: Intent) {
        ensureChannel()
        val pi = PendingIntent.getActivity(context, id, tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT).build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /** What an alarm does when it goes off. */
    suspend fun handle(i: Intent) {
        val c = context.app
        val p = c.settings.current().prefs
        when (i.getStringExtra("kind")) {
            "check" -> {
                val s = c.settings.current()
                val sports = c.db.user().teams().first().map { it.key.substringBefore(':') }.toSet()
                if (sports.isEmpty() || (!p.remindTeams && !p.finalScores)) return
                val leagues = Leagues.all.filter { it.key in s.enabledLeagues && it.sport.espn in sports }
                schedule(c.sports.games(leagues, DayOffset.TODAY).first)
            }
            "remind" -> {
                if (!p.remindTeams) return
                val id = i.getStringExtra("id") ?: return
                val g = c.sports.cache[id] ?: c.sports.fetchGame(i.getStringExtra("league") ?: return, id, i.getLongExtra("start", 0)) ?: return
                if (g.state == GameState.POST) return
                val best = runCatching { c.finder.find(g, emptyList()) }.getOrDefault(emptyList()).firstOrNull { it.score >= 40 }
                val ch = best?.let { c.db.channels().byId(it.channelId) }
                val text = (ch?.let { "On ${it.name} · tap to watch" } ?: "Tap to see where to watch")
                val tap = if (ch != null) com.arena.tv.ui.player.PlayerActivity.intent(context, ch.id, g.headline) else Intent(context, MainActivity::class.java)
                notify(("r" + id).hashCode(), "${g.league.sport.emoji} ${g.headline} starts soon", text, tap)
            }
            "final" -> {
                if (!p.finalScores) return
                val id = i.getStringExtra("id") ?: return
                val g = c.sports.fetchGame(i.getStringExtra("league") ?: return, id, i.getLongExtra("start", 0)) ?: return
                val tries = i.getIntExtra("tries", 0)
                if (g.state != GameState.POST) {
                    // Still going (extra time, delays): look again in 15 minutes, for up to 3 hours.
                    if (tries < 12) at(System.currentTimeMillis() + 15 * 60_000, pending("final", g) { putExtra("tries", tries + 1) })
                    return
                }
                val score = if (g.headToHead) "${g.home.displayName} ${g.home.score ?: 0} – ${g.away.score ?: 0} ${g.away.displayName}" else g.headline
                notify(("f" + id).hashCode(), "${g.league.sport.emoji} Final · ${g.league.shortName}", score, Intent(context, MainActivity::class.java))
            }
        }
    }

    fun handleAsync(i: Intent, done: () -> Unit) { scope.launch { try { runCatching { handle(i) } } finally { done() } } }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = context.app.reminders
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { r.startDailyCheck(); return }
        val pending = goAsync()
        r.handleAsync(intent) { pending.finish() }
    }
}
