package com.phrag.tvspeed

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
 * update. The APK is downloaded, its size and sha256 verified, its signing
 * certificate checked against this app's own ([signerAccepted]), and handed to
 * PackageInstaller. Android then shows its own confirmation: without
 * device-owner rights an app can't update itself silently.
 *
 * Needs REQUEST_INSTALL_PACKAGES plus the user allowing "Install unknown apps"
 * for this app once. [UpdateReceiver] relays PackageInstaller's result.
 *
 * GitHub is asked at most once a day ([CHECK_EVERY_MS]); in between, the last
 * answer is reused. [autoCheck] turns the automatic check off entirely, and
 * [showSettings] offers "check now" for when it is.
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
    private var offering = false   // update dialog showing; UI thread only

    /** Whether [check] looks for updates on its own. Off: only [showSettings] → "Check now". */
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    /**
     * Look for a newer build in the background; prompts on the UI thread if
     * there is one. Call from onResume: that also re-offers the update when
     * the user comes back from granting "Install unknown apps" (from the
     * cached answer, without asking GitHub again). Does nothing while a check
     * or download is already running, or when [autoCheck] is off.
     *
     * [manual]: the user asked. Always asks GitHub, ignores the snooze, and
     * says so when there is nothing new or the check fails.
     */
    fun check(manual: Boolean = false) {
        if (!manual && !autoCheck) return
        if (!busy.compareAndSet(false, true)) {
            if (manual) toast("An update check is already running")
            return
        }
        Thread({
            try {
                val now = System.currentTimeMillis()
                val cached = prefs.getString("remote", null)
                val fresh = now - prefs.getLong("checked_at", 0) in 0 until CHECK_EVERY_MS
                val remote = try {
                    if (!manual && fresh) {
                        cached?.let(::parse) ?: return@Thread   // checked recently, nothing published
                    } else {
                        val json = fetch(BuildConfig.UPDATE_URL + VERSION_FILE)
                        parse(json).also { prefs.edit().putString("remote", json).putLong("checked_at", now).apply() }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "update check failed: $e")   // offline, no release yet, … try next time
                    if (manual) toast("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}")
                    return@Thread
                }
                if (!remote.isNewerThan(BuildConfig.GIT_SHA, BuildConfig.GIT_TIME)) {
                    if (manual) toast("$label is up to date")
                    return@Thread
                }
                if (!manual && snoozed(remote.sha)) return@Thread
                activity.runOnUiThread { if (alive() && !offering) offer(remote) }
            } finally {
                busy.set(false)
            }
        }, "updater-check").start()
    }

    /** "Updates" dialog: installed build, check now, automatic checks on/off. */
    fun showSettings() {
        val items = arrayOf("Check for updates now",
            "Automatic update checks: ${if (autoCheck) "On" else "Off"}")
        AlertDialog.Builder(activity, THEME)
            .setTitle("Updates · ${short(BuildConfig.GIT_SHA)} · ${date(BuildConfig.GIT_TIME)}")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> check(manual = true)
                    1 -> {
                        autoCheck = !autoCheck
                        toast(if (autoCheck) "Checks GitHub for updates at most once a day"
                            else "Automatic update checks are off")
                    }
                }
            }
            .show()
    }

    private fun toast(msg: String) = activity.runOnUiThread {
        if (alive()) Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    private fun offer(remote: Remote) {
        offering = true
        AlertDialog.Builder(activity, THEME)
            .setTitle("Update available")
            .setMessage("A newer build of $label is available.\n\n" +
                "Installed: ${short(BuildConfig.GIT_SHA)} · ${date(BuildConfig.GIT_TIME)}\n" +
                "New: ${short(remote.sha)} · ${date(remote.time)} · ${"%.1f".format(remote.size / 1e6)} MB")
            .setPositiveButton("Install") { _, _ -> install(remote) }
            .setNegativeButton("Later") { _, _ -> snooze(remote.sha) }
            .setOnDismissListener { offering = false }
            .show()
    }

    private fun install(remote: Remote) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            askForInstallPermission()
            return
        }
        // A download from an earlier screen may still be finishing (or cancelling).
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(activity, "An update is already in progress", Toast.LENGTH_SHORT).show()
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
            var file: File? = null
            val error = try {
                file = File.createTempFile("update", ".apk", app.cacheDir)
                download(remote, file, cancelled) { pct ->
                    activity.runOnUiThread { if (alive()) progress.setMessage("Downloading… $pct%") }
                }
                if (cancelled.get()) null else { verifySigner(file); commit(file); null }
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                e.message ?: e.javaClass.simpleName
            } finally {
                file?.delete()   // PackageInstaller has its own copy by now
                busy.set(false)
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
            throw IOException("download didn't match the release (try again in a minute)")
        }
    }

    /**
     * Refuse an APK that isn't this app or isn't signed by this app's key (or
     * a key it was rotated to). Android checks this too at install time; doing
     * it here fails early with a clear message and never hands a foreign APK
     * to PackageInstaller.
     */
    private fun verifySigner(file: File) {
        val pm = app.packageManager
        val archive = archiveInfo(pm, file) ?: throw IOException("the download isn't a valid app")
        if (archive.packageName != app.packageName) throw IOException("the download is a different app")
        val installed = signers(installedInfo(pm)) ?: throw IOException("can't read this app's signature")
        val incoming = signers(archive) ?: throw IOException("the download isn't signed")
        if (!signerAccepted(installed.first, incoming.first, incoming.second)) {
            throw IOException("the download isn't signed with this app's key")
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        pm.getPackageArchiveInfo(file.path, SIGNATURE_FLAGS)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo = pm.getPackageInfo(app.packageName, SIGNATURE_FLAGS)

    /** SHA-256 of (current signers, every signer in the rotation history incl. current); null if unreadable. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Pair<Set<String>, Set<String>>? {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            if (si != null) {
                val current = si.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()
                val history = if (si.hasMultipleSigners()) current
                    else si.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }.toSet() + current
                if (current.isNotEmpty()) return current to history
            }
        }
        val legacy = info.signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        return if (legacy.isEmpty()) null else legacy to legacy
    }

    /** Hand the verified APK to PackageInstaller; [UpdateReceiver] gets the outcome. */
    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            // Android 12+: no prompt once this app is the installer of record
            // (needs UPDATE_PACKAGES_WITHOUT_USER_ACTION in the manifest).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
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
        } catch (e: Exception) {
            // Not handed off: free the session and its staged copy rather than leak them.
            try { installer.abandonSession(id) } catch (s: Exception) { e.addSuppressed(s) }
            throw e
        }
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
        private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L
        @Suppress("DEPRECATION")
        private val SIGNATURE_FLAGS = PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        private const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

        /** Process-wide, so a recreated activity can't start a second check or download. */
        private val busy = AtomicBoolean(false)

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

        /**
         * An update is accepted when it's signed by exactly the installed app's
         * signers, or (key rotation) its signing history includes all of them.
         */
        fun signerAccepted(installed: Set<String>, incoming: Set<String>, incomingHistory: Set<String>) =
            installed.isNotEmpty() && (incoming == installed || incomingHistory.containsAll(installed))

        private fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
