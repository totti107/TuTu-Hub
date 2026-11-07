package com.arena.tv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.arena.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Checks GitHub for a newer TuTu build and installs it.
 * Every build publishes a release tagged "v<version>-<build number>" with the APK attached.
 * Android always asks the user to confirm an install; the first time it also asks to allow TuTu to install apps.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    data class Release(val build: Int, val version: String, val notes: String, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release) : State
        /** Android needs "allow TuTu to install apps" switched on first. */
        data class NeedsPermission(val release: Release) : State
        data class Failed(val message: String) : State
    }

    companion object {
        const val REPO = "totti107/TuTu-hub"
        private val TAG = Regex("""^v(.+)-(\d+)$""")

        fun parse(body: String): Release? {
            val o = Json.parseToJsonElement(body) as? JsonObject ?: return null
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
            val m = TAG.find(o.s("tag_name") ?: return null) ?: return null
            val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { it.s("name")?.endsWith(".apk") == true }?.s("browser_download_url") ?: return null
            return Release(m.groupValues[2].toInt(), m.groupValues[1], o.s("body").orEmpty().trim(), apk)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    /** Builds the user said "Later" to; not offered again until the app restarts. */
    private var dismissed = 0
    private val file get() = File(context.cacheDir, "updates/TuTu-update.apk")

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun check(manual: Boolean = false) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            _state.value = runCatching {
                val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "TuTu-updater").build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 404) return@use State.UpToDate // nothing published yet
                    if (!r.isSuccessful) error("GitHub answered ${r.code}")
                    val rel = parse(r.body?.string().orEmpty())
                    when {
                        rel == null || rel.build <= BuildConfig.VERSION_CODE -> State.UpToDate
                        !manual && rel.build == dismissed -> State.Idle
                        else -> State.Available(rel)
                    }
                }
            }.getOrElse { if (manual) State.Failed("Couldn't check for updates: ${it.message}") else State.Idle }
        }
    }

    fun later() {
        (_state.value as? State.Available)?.let { dismissed = it.release.build }
        _state.value = State.Idle
    }

    fun download(release: Release) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(release, 0f)
        scope.launch {
            _state.value = runCatching {
                file.parentFile?.mkdirs()
                val req = Request.Builder().url(release.apkUrl).header("User-Agent", "TuTu-updater").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("download failed (${r.code})")
                    val body = r.body ?: error("empty download")
                    val total = body.contentLength().takeIf { it > 0 }
                    val tmp = File(file.path + ".part")
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var last = 0L
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (total != null && done - last > 256 * 1024) { last = done; _state.value = State.Downloading(release, done.toFloat() / total) }
                            }
                        }
                    }
                    if (!tmp.renameTo(file)) error("couldn't save the update")
                }
                State.Ready(release)
            }.getOrElse { State.Failed("Update failed: ${it.message}") }
        }
    }

    /** Opens Android's installer (or the permission screen first). */
    fun install(release: Release) {
        if (!file.exists()) { download(release); return }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(settings); return } catch (_: ActivityNotFoundException) { /* some TVs: try the installer anyway */ }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent); _state.value = State.Ready(release) }
        catch (e: ActivityNotFoundException) { _state.value = State.Failed("This device can't open the installer.") }
    }

    fun close() { if (_state.value !is State.Downloading) _state.value = State.Idle }
}
