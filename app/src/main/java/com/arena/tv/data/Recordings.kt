package com.arena.tv.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.arena.tv.MainActivity
import com.arena.tv.R
import com.arena.tv.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/** A recording (or a reminder) planned, running or finished. */
@Serializable
data class Recording(
    val id: String,
    /** "record" or "remind" */
    val kind: String = "record",
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val end: Long,
    /** scheduled, recording, done, failed, cancelled */
    val status: String = "scheduled",
    val path: String? = null,
    val sizeBytes: Long = 0,
    val error: String? = null,
)

/**
 * Settings → Recording and Planner: saves live channels to files and plans recordings / reminders from the TV guide.
 * The list is a small JSON file; recordings run in [RecordingService] so they continue in the background.
 */
class Recordings(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "recordings.json")
    private val lock = Mutex()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Recording>> = _items

    private fun load(): List<Recording> = runCatching { json.decodeFromString(ListSerializer(Recording.serializer()), file.readText()) }.getOrDefault(emptyList())

    suspend fun update(f: (List<Recording>) -> List<Recording>) = lock.withLock {
        val next = f(_items.value)
        _items.value = next
        runCatching { file.writeText(json.encodeToString(ListSerializer(Recording.serializer()), next)) }
    }
    suspend fun set(id: String, f: (Recording) -> Recording) = update { l -> l.map { if (it.id == id) f(it) else it } }
    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    // ---------------- storage ----------------

    /** Places recordings can go: the app's storage plus any SD card / USB drive. */
    fun volumes(): List<Pair<String, String>> {
        val dirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull()
        val list = dirs.mapIndexed { i, d -> d.absolutePath to if (i == 0) "App storage" else "External drive ${if (dirs.size > 2) i else ""}".trim() }
        return listOf("" to "App storage") + list.drop(1)
    }

    fun folder(p: Prefs): File {
        val f = if (p.recFolder.isNotBlank()) File(p.recFolder) else (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "recordings"))
        return File(f, "Recordings").apply { mkdirs() }
    }

    fun freeBytes(p: Prefs): Long = runCatching { StatFs(folder(p).absolutePath).availableBytes }.getOrDefault(0)

    private fun fileName(p: Prefs, r: Recording, ext: String): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date(r.start))
        val name = when (p.recPattern) {
            "date_channel_title" -> "$date - ${r.channelName} - ${r.title}"
            "title_date" -> "${r.title} - $date"
            else -> "${r.channelName} - $date - ${r.title}"
        }
        return name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("""\s+"""), " ").trim().take(150) + ".$ext"
    }

    // ---------------- planning ----------------

    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(r: Recording): PendingIntent = PendingIntent.getBroadcast(context, r.id.hashCode(),
        Intent(context, RecordingReceiver::class.java).putExtra("id", r.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun setAlarm(r: Recording, at: Long) = runCatching {
        val pi = alarmIntent(r)
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else if (Build.VERSION.SDK_INT >= 23) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setExact(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Plans a recording or a reminder for a show from the guide. */
    suspend fun plan(ch: ChannelEntity, title: String, start: Long, stop: Long, kind: String): Recording {
        val p = context.app.settings.current().prefs
        val r = Recording(
            id = java.util.UUID.randomUUID().toString(), kind = kind, channelId = ch.id, channelName = ch.name, title = title,
            start = if (kind == "record") start - p.planEarly * 60_000L else start, end = if (kind == "record") stop + p.planLate * 60_000L else stop,
        )
        update { it + r }
        arm(r, p)
        return r
    }

    private fun arm(r: Recording, p: Prefs) {
        val now = System.currentTimeMillis()
        when {
            r.kind == "remind" -> setAlarm(r, maxOf(now + 2_000, r.start - p.planRemind * 60_000L))
            r.start <= now -> RecordingService.start(context, r.id)
            else -> setAlarm(r, r.start)
        }
    }

    /** Records a channel right now, until [end]. */
    suspend fun recordNow(ch: ChannelEntity, title: String, end: Long): Recording {
        val r = Recording(java.util.UUID.randomUUID().toString(), "record", ch.id, ch.name, title, System.currentTimeMillis(), end)
        update { it + r }
        RecordingService.start(context, r.id)
        return r
    }

    suspend fun cancel(id: String) {
        val r = get(id) ?: return
        runCatching { alarms.cancel(alarmIntent(r)) }
        if (r.status == "recording") RecordingService.stop(context, id)
        else update { l -> l.filter { it.id != id } }
    }

    suspend fun delete(id: String) {
        val r = get(id) ?: return
        if (r.status == "recording") RecordingService.stop(context, id)
        r.path?.let { runCatching { File(it).delete() } }
        update { l -> l.filter { it.id != id } }
    }

    /** After a reboot: alarms are gone, so set them again. Also drops expired recordings. */
    suspend fun rearm() {
        val p = context.app.settings.current().prefs
        val now = System.currentTimeMillis()
        _items.value.filter { it.status == "scheduled" && it.end > now }.forEach { arm(it, p) }
        // A recording cut off by a reboot or crash is kept as it is.
        update { l -> l.map { if (it.status == "recording" && !RecordingService.isRunning(it.id)) it.copy(status = "done") else it } }
        if (p.recKeepDays > 0) {
            val old = _items.value.filter { it.status == "done" && it.end < now - p.recKeepDays * 86_400_000L }
            old.forEach { r -> r.path?.let { runCatching { File(it).delete() } } }
            if (old.isNotEmpty()) update { l -> l.filter { it !in old } }
        }
        // Planned items whose time passed while the device was off.
        update { l -> l.filter { !(it.status == "scheduled" && it.end <= now) } }
    }

    // ---------------- the recorder ----------------

    /** Copies the stream to a file until the end time (reconnecting if the stream drops). */
    suspend fun run(id: String, job: Job, progress: (Long) -> Unit) {
        val c = context.app
        val r = get(id) ?: return
        val p = c.settings.current().prefs
        val ch = c.db.channels().byId(r.channelId) ?: run { set(id) { it.copy(status = "failed", error = "Channel not found") }; return }
        val stream = runCatching { c.streams.resolve(ch, alternate = false) }.getOrElse { e -> set(id) { it.copy(status = "failed", error = e.message) }; return }
        val hls = stream.forceHls || stream.url.contains(".m3u8", true)
        val out = File(folder(p), fileName(p, r, "ts"))
        set(id) { it.copy(status = "recording", path = out.absolutePath, error = null) }
        var written = 0L
        var failures = 0
        FileOutputStream(out, true).use { fos ->
            while (job.isActive && System.currentTimeMillis() < (get(id)?.end ?: 0)) {
                try {
                    if (hls) written = recordHls(stream.url, stream.userAgent, fos, id, written, job, progress)
                    else written = recordTs(stream.url, stream.userAgent, fos, id, written, job, progress)
                    failures = 0
                } catch (e: Exception) {
                    if (!job.isActive) break
                    failures++
                    if (failures > 20) { set(id) { it.copy(error = "Stream kept dropping: ${e.message}") }; break }
                    delay(3_000)
                }
            }
        }
        val size = out.length()
        set(id) { it.copy(status = if (size > 0) "done" else "failed", sizeBytes = size, error = if (size > 0) it.error else (it.error ?: "Nothing was recorded")) }
        if (size == 0L) out.delete()
    }

    private fun recordTs(url: String, ua: String?, fos: FileOutputStream, id: String, start: Long, job: Job, progress: (Long) -> Unit): Long {
        var written = start
        val req = Request.Builder().url(url).header("User-Agent", ua ?: "Mozilla/5.0 (Linux; Android) TuTuTV/1.0").build()
        context.app.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val input = r.body!!.byteStream()
            val buf = ByteArray(64 * 1024)
            var last = 0L
            while (job.isActive && System.currentTimeMillis() < (get(id)?.end ?: 0)) {
                val n = input.read(buf); if (n < 0) break
                fos.write(buf, 0, n); written += n
                if (written - last > 4_000_000) { last = written; progress(written) }
            }
        }
        return written
    }

    /** HLS: follows the live playlist and appends each new segment (highest quality variant). */
    private suspend fun recordHls(url: String, ua: String?, fos: FileOutputStream, id: String, start: Long, job: Job, progress: (Long) -> Unit): Long {
        val http = context.app.http
        fun get(u: String): okhttp3.Response = http.newCall(Request.Builder().url(u).header("User-Agent", ua ?: "Mozilla/5.0 (Linux; Android) TuTuTV/1.0").build()).execute()
        fun text(u: String): Pair<String, String> = get(u).use { r -> if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}"); r.request.url.toString() to r.body!!.string() }
        var (base, body) = text(url)
        if ("#EXT-X-STREAM-INF" in body) {
            // Master playlist: take the best variant.
            val lines = body.lines()
            val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
                .maxByOrNull { Regex("""BANDWIDTH=(\d+)""").find(lines[it])?.groupValues?.get(1)?.toLongOrNull() ?: 0 }
                ?.let { i -> lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } } ?: throw java.io.IOException("No stream in playlist")
            val variant = base.toHttpUrl().resolve(best.trim()).toString()
            val t = text(variant); base = t.first; body = t.second
        }
        var written = start
        val seen = LinkedHashSet<String>()
        var initDone = false
        var last = 0L
        while (job.isActive && System.currentTimeMillis() < (getRec(id)?.end ?: 0)) {
            val lines = body.lines()
            if (!initDone) Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(body)?.let { m ->
                get(base.toHttpUrl().resolve(m.groupValues[1]).toString()).use { r -> r.body?.bytes()?.let { fos.write(it); written += it.size } }
            }
            initDone = true
            val target = Regex("""#EXT-X-TARGETDURATION:(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 6
            for (seg in lines.filter { it.isNotBlank() && !it.startsWith("#") }) {
                val u = base.toHttpUrl().resolve(seg.trim()).toString()
                if (!seen.add(u)) continue
                get(u).use { r -> if (r.isSuccessful) r.body?.byteStream()?.use { written += it.copyTo(fos, 64 * 1024) } }
                if (written - last > 4_000_000) { last = written; progress(written) }
            }
            while (seen.size > 500) seen.remove(seen.first())
            if ("#EXT-X-ENDLIST" in body) break
            delay(target * 500)
            val t = text(base); body = t.second
        }
        return written
    }
    private fun getRec(id: String) = get(id)
}

/** Keeps recordings going with the screen off or the app closed. */
class RecordingService : Service() {
    companion object {
        private const val CHANNEL = "recordings"
        private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()
        fun isRunning(id: String) = running.containsKey(id)
        fun start(context: Context, id: String) {
            val i = Intent(context, RecordingService::class.java).putExtra("id", id)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context, id: String) { running[id]?.cancel() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle("TuTu is recording")
            .setContentText(text).setOngoing(true).setContentIntent(pi).build()
    }

    private fun refresh() {
        val recs = running.keys.mapNotNull { app.recordings.get(it) }
        if (recs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        getSystemService(NotificationManager::class.java).notify(42, notification(recs.joinToString(" · ") { "● ${it.channelName}: ${it.title}" }))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        val first = notification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, first, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(42, first)
        if (id == null || running.containsKey(id)) { refresh(); return START_NOT_STICKY }
        val recs = app.recordings
        scope.launch {
            val max = app.settings.current().prefs.recMax
            if (running.size >= max) {
                recs.set(id) { it.copy(status = "failed", error = "Already recording $max at once (Settings → Recording)") }
                refresh(); return@launch
            }
            val job = coroutineContext[Job]!!
            running[id] = job
            refresh()
            try { recs.run(id, job) { size -> launch { recs.set(id) { it.copy(sizeBytes = size) } } } }
            finally {
                running.remove(id)
                if (recs.get(id)?.status == "recording") recs.set(id) { it.copy(status = "done") }
                refresh()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}

/** Planned recording start, or a reminder. */
class RecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val r = context.app.recordings.get(id) ?: return
        if (r.kind == "record") { RecordingService.start(context, id); return }
        // Reminder: "Starting in 5 min · Match of the Day on BBC One", tap to watch.
        val mins = ((r.start - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("planner", "Planner reminders", NotificationManager.IMPORTANCE_HIGH))
        val tap = com.arena.tv.ui.player.PlayerActivity.intent(context, r.channelId, r.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, id.hashCode(), tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, "planner").setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (mins > 0) "Starting in $mins min: ${r.title}" else "Starting now: ${r.title}")
            .setContentText("On ${r.channelName} · tap to watch").setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        runCatching { nm.notify(id.hashCode(), n) }
        val scope = CoroutineScope(Dispatchers.IO)
        val done = goAsync()
        scope.launch { try { context.app.recordings.update { l -> l.filter { it.id != id } } } finally { done.finish() } }
    }
}
