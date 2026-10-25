package com.phrag.tvspeed

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update from this app's rolling GitHub release.
 *
 * CI publishes the APK and a small `version.json` (commit SHA and time, APK
 * name, sha256 and size) to a fixed release tag ([BuildConfig.UPDATE_URL]).
 * [check] fetches that file; if it describes a newer commit than this build
 * ([BuildConfig.GIT_SHA] / [BuildConfig.GIT_TIME]) the user is offered the
 * update. The APK is downloaded, its size and sha256 verified, and handed to
 * PackageInstaller. Android then shows its own confirmation: without
 * device-owner rights an app can't update itself silently.
 *
 * Needs REQUEST_INSTALL_PACKAGES plus the user allowing "Install unknown apps"
 * for this app once. [UpdateReceiver] relays PackageInstaller's result.
 */
class Updater(private val activity: Activity) {

    /** The contents of the release's version.json. */
    data class Remote(val sha: String, val time: Long, val apk: String, val sha256: String, val size: Long) {
        /** A different commit that is also newer, so a local dev build is never "updated" backwards. */
        fun isNewerThan(sha: String, time: Long) = this.sha != sha && this.time > time
    }

    private val app = activity.applicationContext
    private val prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val label: String get() = app.applicationInfo.loadLabel(app.packageManager).toString()

    /** Look for a newer build in the background; prompts on the UI thread if there is one. */
    fun check() {
        Thread({
            val remote = try {
                parse(fetch(BuildConfig.UPDATE_URL + VERSION_FILE))
            } catch (e: Exception) {
                Log.i(TAG, "update check failed: $e")   // offline, no release yet, … try next launch
                return@Thread
            }
            if (!remote.isNewerThan(BuildConfig.GIT_SHA, BuildConfig.GIT_TIME)) return@Thread
            if (snoozed(remote.sha)) return@Thread
            activity.runOnUiThread { if (alive()) offer(remote) }
        }, "updater-check").start()
    }

    private fun offer(remote: Remote) {
        AlertDialog.Builder(activity, THEME)
            .setTitle("Update available")
            .setMessage("A newer build of $label is available.\n\n" +
                "Installed: ${short(BuildConfig.GIT_SHA)} · ${date(BuildConfig.GIT_TIME)}\n" +
                "New: ${short(remote.sha)} · ${date(remote.time)} · ${"%.1f".format(remote.size / 1e6)} MB")
            .setPositiveButton("Install") { _, _ -> install(remote) }
            .setNegativeButton("Later") { _, _ -> snooze(remote.sha) }
            .show()
    }

    private fun install(remote: Remote) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            askForInstallPermission()
            return
        }
        val cancelled = AtomicBoolean(false)
        val progress = AlertDialog.Builder(activity, THEME)
            .setTitle("Updating $label")
            .setMessage("Downloading…")
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .show()
        Thread({
            val file = File(app.cacheDir, "update.apk")
            val error = try {
                download(remote, file, cancelled) { pct ->
                    activity.runOnUiThread { if (alive()) progress.setMessage("Downloading… $pct%") }
                }
                if (cancelled.get()) null else { commit(file); null }
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                e.message ?: e.javaClass.simpleName
            }
            activity.runOnUiThread {
                if (!alive()) return@runOnUiThread
                progress.dismiss()
                if (error != null) Toast.makeText(activity, "Update failed: $error", Toast.LENGTH_LONG).show()
            }
        }, "updater-download").start()
    }

    /** Download to [file], checking size and sha256 against [remote]. */
    private fun download(remote: Remote, file: File, cancelled: AtomicBoolean, onProgress: (Int) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        var lastPct = -1
        open(BuildConfig.UPDATE_URL + remote.apk).let { c ->
            try {
                c.inputStream.use { ins ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (!cancelled.get()) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            val pct = (read * 100 / remote.size.coerceAtLeast(1)).toInt().coerceAtMost(100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        }
        if (cancelled.get()) return
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        // A mismatch usually means CI is replacing the APK right now; the next launch retries.
        if (read != remote.size || !hash.equals(remote.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException("download didn't match the release (try again in a minute)")
        }
    }

    /** Hand the verified APK to PackageInstaller; [UpdateReceiver] gets the outcome. */
    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            // Android 12+: no prompt once this app is the installer of record.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable: PackageInstaller fills in the status extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
        file.delete()
    }

    private fun askForInstallPermission() {
        AlertDialog.Builder(activity, THEME)
            .setTitle("Allow updates")
            .setMessage("To install updates, $label needs permission to install apps.\n\n" +
                "Settings → Apps → Special app access → Install unknown apps → $label → Allow")
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${app.packageName}")))
                } catch (e: ActivityNotFoundException) {
                    // Many TV builds don't have this screen; the path above still works.
                    Toast.makeText(activity, "Open it from the system Settings app", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun snooze(sha: String) =
        prefs.edit().putString("snoozed_sha", sha).putLong("snoozed_at", System.currentTimeMillis()).apply()

    private fun snoozed(sha: String) = prefs.getString("snoozed_sha", null) == sha &&
        System.currentTimeMillis() - prefs.getLong("snoozed_at", 0) < SNOOZE_MS

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    private fun fetch(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        useCaches = false
        setRequestProperty("User-Agent", "${app.packageName}/${BuildConfig.VERSION_NAME} (${short(BuildConfig.GIT_SHA)})")
    }

    companion object {
        private const val TAG = "Updater"
        private const val VERSION_FILE = "version.json"
        private const val SNOOZE_MS = 24 * 60 * 60 * 1000L
        private const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

        /** Parse and sanity-check version.json. */
        fun parse(json: String): Remote {
            val o = JSONObject(json)
            val r = Remote(
                sha = o.getString("sha"),
                time = o.getLong("time"),
                apk = o.getString("apk"),
                sha256 = o.getString("sha256"),
                size = o.getLong("size"),
            )
            require(r.sha.matches(Regex("[0-9a-f]{40}"))) { "bad sha" }
            require(r.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "bad sha256" }
            require(r.apk.matches(Regex("[A-Za-z0-9._-]+\\.apk"))) { "bad apk name" }
            require(r.size > 0 && r.time > 0) { "bad size/time" }
            return r
        }

        private fun short(sha: String) = sha.take(7)
        private fun date(epochSec: Long) =
            if (epochSec <= 0) "unknown" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSec * 1000))
    }
}

/** Receives PackageInstaller's status for an update session started by [Updater]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "Do you want to update this app?" screen.
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Updater", "update installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w("Updater", "update failed: $msg")
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
