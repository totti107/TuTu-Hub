package com.arena.tv.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arena.tv.MainActivity
import com.arena.tv.R
import com.arena.tv.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Movies & series update on their own schedule (Settings → Playlists → Movies & Series):
 * overnight at about 4 AM, or when the app opens if the device was off then. Runs in the background with a notification.
 */
object LibraryUpdates {
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 4004,
        Intent(context, LibraryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sets the next overnight check (4 AM tomorrow or today). */
    fun arm(context: Context) = runCatching {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 4); set(Calendar.MINUTE, 5 + (0..20).random()); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    fun start(context: Context, onlyId: String? = null, onlyDue: Boolean = false) = runCatching {
        val i = Intent(context, LibraryService::class.java).putExtra("id", onlyId).putExtra("due", onlyDue)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }
}

class LibraryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LibraryUpdates.arm(context)
        LibraryUpdates.start(context, onlyDue = true)
    }
}

class LibraryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun note(text: String) = run {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("library", "Movies & series updates", NotificationManager.IMPORTANCE_MIN))
        NotificationCompat.Builder(this, "library").setSmallIcon(R.drawable.ic_notify).setContentTitle("Updating movies & series")
            .setContentText(text).setOngoing(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = note("Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(43, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(43, n)
        if (job?.isActive == true) return START_NOT_STICKY
        val onlyId = intent?.getStringExtra("id")
        val onlyDue = intent?.getBooleanExtra("due", false) ?: false
        job = scope.launch {
            val progress = launch {
                app.iptv.library.collectLatest { s -> s.message?.let { getSystemService(NotificationManager::class.java).notify(43, note(it)) } }
            }
            try { app.iptv.refreshLibrary(onlyId, onlyDue) } finally {
                progress.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
