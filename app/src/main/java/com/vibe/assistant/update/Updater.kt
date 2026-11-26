package com.vibe.assistant.update

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.vibe.assistant.R
import com.vibe.assistant.VibeApp
import com.vibe.assistant.data.Http
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.tools.DeviceActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Self-update from GitHub Releases.
 *
 *   1. ask GitHub for the latest release of the Vibe-Assistant repo
 *   2. if its version is newer, download the APK with Android's download manager
 *      into the phone's Download folder (so it is also in the file manager)
 *   3. when the download finishes, open the system package installer on it
 *
 * Android always asks the user to confirm an install; the app cannot skip that.
 */
object Updater {
    const val REPO = "jhfarhanyt20-coder/Vibe-Assistant"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val NOTIF_ID = 40
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L

    data class Release(val version: String, val apkUrl: String, val fileName: String, val notes: String, val sizeMb: Int)

    /** A short status line for Settings. */
    val status = MutableStateFlow("")
    val available = MutableStateFlow<Release?>(null)

    enum class Stage { IDLE, DOWNLOADING, READY, FAILED }

    /** Where the update is, for the card on the home screen. */
    val stage = MutableStateFlow(Stage.IDLE)
    /** 0..100 while downloading. */
    val progress = MutableStateFlow(0)

    /** The download the installer was already opened for — never twice for one file. */
    @Volatile private var offeredFor = -1L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /** True if version a is newer than b ("0.3.0" > "0.2.9"). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Vibe-Updater")
            .build()
        Http.rest.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) throw IllegalStateException("GitHub ${r.code}")
            val o = JSONObject(r.body?.string().orEmpty())
            val tag = o.optString("tag_name").removePrefix("v")
            val assets = o.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return@withContext Release(
                        tag, a.optString("browser_download_url"), name,
                        o.optString("body"), (a.optLong("size") / 1_000_000L).toInt()
                    )
                }
            }
            null
        }
    }

    /** Manual check from Settings. Downloads straight away if something newer exists. */
    fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        status.value = "দেখছি…"
        scope.launch {
            try {
                val rel = fetchLatest()
                Prefs.lastUpdateCheck = System.currentTimeMillis()
                val cur = currentVersion(app)
                if (rel == null || !isNewer(rel.version, cur)) {
                    available.value = null
                    status.value = "সবচেয়ে নতুন ভার্সনই চলছে ($cur)"
                } else {
                    available.value = rel
                    download(app, rel)
                }
            } catch (e: Exception) {
                status.value = "আপডেট দেখা গেল না: ${e.message ?: "ইন্টারনেট দেখো"}"
            }
        }
    }

    /**
     * Quiet check. When the app is opened ([onOpen]) it looks every time (at most
     * once a minute) so a new version shows up at once; from the background service
     * only every few hours.
     */
    fun autoCheck(ctx: Context, onOpen: Boolean = false) {
        if (!Prefs.autoUpdate) return
        val since = System.currentTimeMillis() - Prefs.lastUpdateCheck
        if (since < (if (onOpen) 60_000L else CHECK_EVERY_MS)) {
            // Nothing new to ask GitHub, but a finished download may still be waiting to install.
            if (onOpen) resume(ctx.applicationContext)
            return
        }
        val app = ctx.applicationContext
        scope.launch {
            try {
                val rel = fetchLatest()
                Prefs.lastUpdateCheck = System.currentTimeMillis()
                if (rel != null && isNewer(rel.version, currentVersion(app))) {
                    available.value = rel
                    // Already downloading / downloaded this version? Then just follow it / offer to install.
                    if (Prefs.updateVersion == rel.version && Prefs.updateDownloadId >= 0 &&
                        downloadStatus(app, Prefs.updateDownloadId) != DownloadManager.STATUS_FAILED
                    ) {
                        resume(app)
                        return@launch
                    }
                    download(app, rel)
                } else if (rel != null) {
                    available.value = null
                    stage.value = Stage.IDLE
                }
            } catch (_: Exception) {}
        }
    }

    private fun downloadStatus(ctx: Context, id: Long): Int {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        return DownloadManager.STATUS_FAILED
    }

    fun download(ctx: Context, rel: Release) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        // Forget an older download of ours, so the folder does not fill with copies.
        val old = Prefs.updateDownloadId
        if (old >= 0) try { dm.remove(old) } catch (_: Exception) {}
        val req = DownloadManager.Request(Uri.parse(rel.apkUrl))
            .setTitle("Vibe ${rel.version}")
            .setDescription("নতুন আপডেট নামছে")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, rel.fileName)
        val id = dm.enqueue(req)
        Prefs.updateDownloadId = id
        Prefs.updateVersion = rel.version
        status.value = "নতুন ভার্সন ${rel.version} নামছে (Download ফোল্ডারে)…"
        watch(ctx.applicationContext, id)
    }

    /** Picks up a download already in progress / finished (e.g. after the app was closed). */
    private fun resume(ctx: Context) {
        val id = Prefs.updateDownloadId
        if (id < 0 || !isNewer(Prefs.updateVersion, currentVersion(ctx))) return
        when (downloadStatus(ctx, id)) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                stage.value = Stage.READY
                progress.value = 100
            }
            DownloadManager.STATUS_FAILED -> Unit
            else -> watch(ctx, id)
        }
    }

    private var watching = -1L

    /** Follows the download's progress for the home-screen card; opens the installer when it is done. */
    private fun watch(ctx: Context, id: Long) {
        if (watching == id) return
        watching = id
        stage.value = Stage.DOWNLOADING
        progress.value = 0
        scope.launch {
            val dm = ctx.getSystemService(DownloadManager::class.java)
            while (true) {
                var st = DownloadManager.STATUS_FAILED
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total > 0) progress.value = ((done * 100) / total).toInt().coerceIn(0, 100)
                    }
                }
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        progress.value = 100
                        stage.value = Stage.READY
                        withContext(Dispatchers.Main) { install(ctx, id) }
                        break
                    }
                    DownloadManager.STATUS_FAILED -> {
                        stage.value = Stage.FAILED
                        status.value = "আপডেট নামানো যায়নি — ইন্টারনেট দেখে আবার চেষ্টা করো"
                        break
                    }
                }
                kotlinx.coroutines.delay(500)
            }
            watching = -1L
        }
    }

    /** "Install" button: open the installer for the downloaded file again. */
    fun installNow(ctx: Context) {
        val id = Prefs.updateDownloadId
        if (id >= 0) {
            offeredFor = -1L
            install(ctx.applicationContext, id)
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        DeviceActions.launch(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
    }

    /** Opens the system installer for a finished download. */
    fun install(ctx: Context, id: Long) {
        // The download-complete broadcast and the progress watcher both arrive here: open the installer once.
        if (offeredFor == id) return
        offeredFor = id
        stage.value = Stage.READY
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(id) ?: run {
            status.value = "নামানো ফাইল পাওয়া যায়নি — আবার চেষ্টা করো"
            return
        }
        val version = Prefs.updateVersion
        status.value = "ভার্সন $version নামানো শেষ — install করো"

        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // Android needs a one-time "allow from this source" for Vibe.
            val allow = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            DeviceActions.launch(ctx, allow)
            notify(ctx, "আপডেট install করতে অনুমতি দাও",
                "Vibe-কে “এই উৎস থেকে অ্যাপ install” করার অনুমতি দাও, তারপর এই নোটিফিকেশনে ট্যাপ করো।",
                installIntent(uri))
            return
        }
        val i = installIntent(uri)
        DeviceActions.launch(ctx, i)
        notify(ctx, "Vibe $version আপডেট প্রস্তুত", "install করতে ট্যাপ করো", i)
    }

    private fun installIntent(uri: Uri) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, APK_MIME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun notify(ctx: Context, title: String, text: String, intent: Intent) {
        val pi = PendingIntent.getActivity(
            ctx, 41, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, VibeApp.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_vibe)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (_: SecurityException) {}
    }
}

/** Android tells us here when the APK download has finished. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0 || id != Prefs.updateDownloadId) return
        val dm = context.getSystemService(DownloadManager::class.java)
        var ok = false
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                ok = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            }
        }
        if (ok) Updater.install(context, id)
        else Updater.status.value = "আপডেট নামানো যায়নি — ইন্টারনেট দেখে আবার চেষ্টা করো"
    }
}
