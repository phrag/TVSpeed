package com.phrag.tvspeed

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/**
 * The whole app: gauge + Start on the left, results, verdict and recent runs
 * on the right. One focusable control, so the D-pad can't get lost. Plain
 * framework Views, built in code (same approach as ArtDream).
 */
class MainActivity : Activity(), SpeedTest.Listener {

    private class Tile(val view: View, val value: TextView, val sub: TextView)

    private lateinit var history: History
    private val picks by lazy { getSharedPreferences("server", Context.MODE_PRIVATE) }
    /** The server picked in the UI, by name; null = automatic (nearest). */
    private var chosen: String?
        get() = picks.getString("name", null)?.takeIf { n -> SpeedTest.SERVERS.any { it.name == n } }
        set(v) = picks.edit().putString("name", v).apply()
    private val tags by lazy { getSharedPreferences("tag", Context.MODE_PRIVATE) }
    /** What the user says this run's VPN server or protocol is; saved with each run. Empty = none. */
    private var tag: String
        get() = tags.getString("tag", "") ?: ""
        set(v) = tags.edit().putString("tag", v).apply()
    private lateinit var tagLink: TextView
    private lateinit var gauge: GaugeView
    private lateinit var button: TextView
    private lateinit var networkLine: TextView
    private lateinit var privacyLine: TextView
    @Volatile private var privacyCheck = 0   // bumped per check; a stale answer is dropped
    private lateinit var down: Tile
    private lateinit var up: Tile
    private lateinit var ping: Tile
    private lateinit var jitter: Tile
    private lateinit var loaded: Tile
    private lateinit var server: Tile
    private lateinit var verdict: TextView
    private lateinit var historyRows: LinearLayout

    private var test: SpeedTest? = null
    private var net: NetInfo? = null
    private var phase: SpeedTest.Phase? = null
    private val idleSamples = ArrayList<Double>()
    private val loadedSamples = ArrayList<Double>()
    private val updater by lazy { Updater(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = History(this)
        window.decorView.setBackgroundColor(Colors.BG)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(28), dp(48), dp(24))
            clipChildren = false
        }

        // Left: gauge + the one button.
        val left = column().apply { clipChildren = false; clipToPadding = false }
        gauge = GaugeView(this)
        left.addView(gauge, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        button = TextView(this).apply {
            text = "Start test"
            textSize = 26f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            minWidth = dp(260)
            setPadding(dp(36), dp(16), dp(36), dp(16))
            background = buttonBackground()
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(Colors.BG, Colors.TEXT)))
            setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120) }
            setOnClickListener { if (test == null) startTest() else stopTest("Test stopped") }
        }
        left.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
            bottomMargin = dp(6)   // room for the focus scale-up
        })
        root.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))

        // Right: title, network, tiles, verdict, history.
        val right = column().apply { setPadding(dp(40), 0, 0, 0) }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text("TV Speed", 32f, Colors.TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL)),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tagLink = link("") { if (test == null) pickTag() }
        titleRow.addView(tagLink, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) })
        titleRow.addView(link("All runs") { if (test == null) showAllRuns() })
        titleRow.addView(link("Updates") { if (test == null) updater.showSettings() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        right.addView(titleRow)
        networkLine = text("", 18f, Colors.TEXT2)
        right.addView(networkLine)
        privacyLine = text("", 18f, Colors.TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        right.addView(privacyLine, lp().apply { bottomMargin = dp(12) })

        down = tile("DOWNLOAD", Colors.DOWN)
        up = tile("UPLOAD", Colors.UP)
        ping = tile("PING", Colors.PING)
        jitter = tile("JITTER", Colors.PING)
        loaded = tile("UNDER LOAD", Colors.WARN)
        server = tile("SERVER", Colors.TEXT2)
        server.view.apply {
            isFocusable = true
            isClickable = true
            background = tileBackground()
            setOnClickListener { if (test == null) pickServer() }
        }
        right.addView(tileRow(down, up, server))
        right.addView(tileRow(ping, jitter, loaded), lp().apply { topMargin = dp(10) })

        verdict = text("Measures the speed this TV actually gets, using non-profit European speed test servers.",
            19f, Colors.TEXT).apply { minLines = 4 }
        right.addView(verdict, lp().apply { topMargin = dp(14) })

        right.addView(text("RECENT", 15f, Colors.TEXT2, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.15f
        }, lp().apply { topMargin = dp(10); bottomMargin = dp(4) })
        historyRows = column()
        right.addView(historyRows)
        root.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.25f))

        setContentView(root)
        resetTiles()
        showTag()
        renderHistory()
        button.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        net = NetInfo.read(this).also { networkLine.text = it.summary }
        checkPrivacy()
        updater.check()   // offers a newer GitHub build, if any (asks GitHub at most daily)
    }

    /** VPN state plus the public IP the internet sees this TV at, refreshed on every resume. */
    private fun checkPrivacy() {
        val n = net ?: return
        val id = ++privacyCheck
        showPrivacy(n, null, checking = true)
        Thread({
            val ip = SpeedTest.exitIp()?.second
            runOnUiThread { if (id == privacyCheck && !isDestroyed) showPrivacy(n, ip, checking = false) }
        }, "tvspeed-exit-ip").start()
    }

    private fun showPrivacy(n: NetInfo, ip: String?, checking: Boolean) {
        val (msg, color) = privacyText(n, ip, checking)
        privacyLine.text = msg
        privacyLine.setTextColor(color)
    }

    /** Remote Play/Pause starts a test, or stops the running one, like the button. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (test == null) startTest() else stopTest("Test stopped"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (test == null) startTest(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> { stopTest("Test stopped"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        stopTest("Test stopped")
        super.onStop()
    }

    // ---- test lifecycle -------------------------------------------------------

    private fun startTest() {
        net = NetInfo.read(this).also { networkLine.text = it.summary }
        resetTiles()
        idleSamples.clear()
        loadedSamples.clear()
        verdict.setTextColor(Colors.TEXT)
        verdict.text = if (net?.vpn == true) "A VPN is on, so this measures the VPN tunnel, not your line." else "Testing…"
        button.text = "Stop"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // adb: am start -n com.phrag.tvspeed/.MainActivity --es server GARR (see README)
        test = SpeedTest(this, intent.getStringExtra("server") ?: chosen).also { it.start() }
    }

    private fun stopTest(message: String) {
        val t = test ?: return
        t.cancel()
        finishRun()
        verdict.setTextColor(Colors.TEXT2)
        verdict.text = message
        gauge.setSpeed(0.0)
        gauge.setReading("—", "", "stopped")
    }

    private fun finishRun() {
        test = null
        phase = null
        button.text = "Run again"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- SpeedTest.Listener (main thread) -----------------------------------

    override fun onServer(server: SpeedTest.Server) {
        // Also called when a run falls back to the next server: start the figures over.
        resetTiles()
        idleSamples.clear()
        loadedSamples.clear()
        this.server.value.text = server.city
        this.server.sub.text = "${server.country} · ${server.name}"
    }

    override fun onPhase(phase: SpeedTest.Phase) {
        this.phase = phase
        val color = when (phase) {
            SpeedTest.Phase.DOWNLOAD -> Colors.DOWN
            SpeedTest.Phase.UPLOAD -> Colors.UP
            else -> Colors.PING
        }
        gauge.setColor(color)
        gauge.setSpeed(0.0)
        gauge.setReading("…", if (phase == SpeedTest.Phase.LATENCY) "ms" else "Mbps", phase.label)
        if (phase == SpeedTest.Phase.UPLOAD) loadedSamples.clear()
    }

    override fun onLatency(ms: Double, loaded: Boolean) {
        if (!loaded) {
            idleSamples.add(ms)
            gauge.setReading(ms.toInt().toString(), "ms", "Latency")
            ping.value.text = fmtMs(SpeedTest.median(idleSamples))
            jitter.value.text = fmtMs(SpeedTest.jitter(idleSamples))
        } else {
            loadedSamples.add(ms)
            this.loaded.value.text = fmtMs(SpeedTest.median(loadedSamples))
            this.loaded.sub.text = "while ${phase?.label?.lowercase() ?: "loading"}ing"
        }
    }

    override fun onSpeed(phase: SpeedTest.Phase, mbps: Double) {
        gauge.setSpeed(mbps)
        gauge.setReading(fmtMbps(mbps), "Mbps", phase.label)
        val tile = if (phase == SpeedTest.Phase.DOWNLOAD) down else up
        tile.value.text = fmtMbps(mbps)
    }

    override fun onDone(result: SpeedTest.Result) {
        val n = net ?: NetInfo.read(this)
        finishRun()
        down.value.text = fmtMbps(result.downMbps)
        up.value.text = fmtMbps(result.upMbps)
        ping.value.text = fmtMs(result.pingMs)
        jitter.value.text = fmtMs(result.jitterMs)
        loaded.value.text = fmtMs(result.downLoadedMs)
        loaded.sub.text = "↓ ${fmtMs(result.downLoadedMs)} · ↑ ${fmtMs(result.upLoadedMs)} ms"
        gauge.setColor(Colors.DOWN)
        gauge.setSpeed(result.downMbps)
        gauge.setReading(fmtMbps(result.downMbps), "Mbps down", "Done")
        verdict.setTextColor(Colors.TEXT)
        verdict.text = verdictFor(result, n)
        if (result.server.ip.isNotEmpty()) { privacyCheck++; showPrivacy(n, result.server.ip, checking = false) }

        history.add(History.Entry(
            time = System.currentTimeMillis(),
            downMbps = result.downMbps, upMbps = result.upMbps,
            pingMs = result.pingMs, loadedMs = maxOf(result.downLoadedMs, result.upLoadedMs),
            vpn = n.vpn, network = n.transport, place = result.server.place,
            server = result.server.name, tag = tag,
        ))
        renderHistory()
    }

    override fun onError(message: String) {
        finishRun()
        verdict.setTextColor(Colors.WARN)
        verdict.text = plainError(message)
        gauge.setSpeed(0.0)
        gauge.setReading("—", "", "error")
    }

    // ---- views ------------------------------------------------------------------

    private fun resetTiles() {
        for (t in listOf(down, up, ping, jitter, loaded)) t.value.text = "—"
        down.sub.text = "Mbps"
        up.sub.text = "Mbps"
        ping.sub.text = "ms, idle"
        jitter.sub.text = "ms"
        loaded.sub.text = "ms"
        showChoice()
        gauge.setColor(Colors.DOWN)
        gauge.setSpeed(0.0)
        gauge.setReading("—", "", "ready")
    }

    private fun renderHistory() {
        historyRows.removeAllViews()
        val runs = history.entries().take(History.SHOWN)
        if (runs.isEmpty()) {
            historyRows.addView(text("No runs yet. Run one per VPN server (or protocol) to see which streams best.", 18f, Colors.TEXT3))
            return
        }
        val timeFmt = DateFormat.getTimeFormat(this)
        val dateFmt = DateFormat.getMediumDateFormat(this)
        val today = dateFmt.format(Date())
        for (r in runs) {
            val d = Date(r.time)
            val whenText = if (dateFmt.format(d) == today) timeFmt.format(d) else dateFmt.format(d)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2)) }
            fun cell(s: String, weight: Float, color: Int = Colors.TEXT) =
                row.addView(text(s, 16f, color).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, weight))
            cell(whenText, 0.9f, Colors.TEXT2)
            cell("↓ ${fmtMbps(r.downMbps)}", 0.8f, Colors.DOWN)
            cell("↑ ${fmtMbps(r.upMbps)}", 0.7f, Colors.UP)
            cell("${fmtMs(r.pingMs)} / ${fmtMs(r.loadedMs)} ms", 1.2f, Colors.TEXT2)
            cell(listOf(r.network, if (r.vpn) "VPN" else "", r.tag, r.place).filter { it.isNotEmpty() }.joinToString(" · "),
                2.4f, if (r.vpn) Colors.WARN else Colors.TEXT2)
            historyRows.addView(row)
        }
    }

    /** Every stored run, plus medians per way out (line, VPN state and server), best first. */
    private fun showAllRuns() {
        val runs = history.entries()
        if (runs.isEmpty()) {
            android.widget.Toast.makeText(this, "No runs yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val lines = ArrayList<String>()
        lines += "BEST WAY OUT (median)"
        History.groups(runs).forEach { g ->
            lines += "${g.label}\n   ↓ ${fmtMbps(g.downMbps)} · ↑ ${fmtMbps(g.upMbps)} Mbps · ${fmtMs(g.pingMs)} ms · " +
                "${g.runs} run${if (g.runs == 1) "" else "s"}"
        }
        lines += "ALL RUNS"
        runs.forEach { r ->
            lines += "${fmt.format(Date(r.time))} · ↓ ${fmtMbps(r.downMbps)} ↑ ${fmtMbps(r.upMbps)} · ${fmtMs(r.pingMs)}/${fmtMs(r.loadedMs)} ms\n   " +
                listOf(r.network, if (r.vpn) "VPN" else "no VPN", r.tag, History.serverLabel(r)).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Recent runs (last ${History.MAX})")
            .setItems(lines.toTypedArray(), null)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear history") { _, _ -> confirmClearHistory() }
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Clear history?")
            .setMessage("Deletes all ${history.entries().size} stored runs from this TV.")
            .setPositiveButton("Clear") { _, _ ->
                history.clear()
                renderHistory()
                android.widget.Toast.makeText(this, "History cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
            // The D-pad lands on Clear by default, so one stray OK would wipe every run.
            .getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    /** A small focusable text button for the title row. */
    private fun link(label: String, onClick: () -> Unit) = text(label, 18f, Colors.TEXT2).apply {
        isFocusable = true
        isClickable = true
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = tileBackground()
        setOnClickListener { onClick() }
    }

    /** D-pad list: Automatic, then each server as "City, Country · operator". */
    private fun pickServer() {
        val names = listOf<String?>(null) + SpeedTest.SERVERS.map { it.name }
        val labels = arrayOf("Automatic (nearest)") + SpeedTest.SERVERS.map { "${it.place} · ${it.name}" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Test server")
            .setSingleChoiceItems(labels, names.indexOf(chosen)) { d, i ->
                chosen = names[i]
                showChoice()
                d.dismiss()
            }
            .show()
    }

    /** D-pad list of common VPN protocols, or type your own; the pick is saved with every run until changed. */
    private fun pickTag() {
        val labels = arrayOf("None") + TAGS + "Custom…"
        val current = TAGS.indexOf(tag).let { if (tag.isEmpty()) 0 else if (it >= 0) it + 1 else labels.size - 1 }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Tag runs with the VPN server or protocol")
            .setSingleChoiceItems(labels, current) { d, i ->
                d.dismiss()
                when (i) {
                    0 -> { tag = ""; showTag() }
                    labels.size - 1 -> typeTag()
                    else -> { tag = labels[i]; showTag() }
                }
            }
            .show()
    }

    private fun typeTag() {
        val current = tag   // inside apply, "tag" would be View.getTag()
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_TAG))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("VPN server or protocol, e.g. Prague #12")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> tag = input.text.toString().trim(); showTag() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTag() {
        tagLink.text = if (tag.isEmpty()) "Tag" else "Tag: $tag"
    }

    /** The idle Server tile: what the next run will use. */
    private fun showChoice() {
        val b = SpeedTest.SERVERS.find { it.name == chosen }
        server.value.text = b?.city ?: "Auto"
        server.sub.text = b?.let { "${it.country} · ${it.name}" } ?: "nearest of ${SpeedTest.SERVERS.size}"
    }

    private fun tileBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Colors.CARD).apply { setStroke(dp(3), Colors.CARD_FOCUS) })
        addState(intArrayOf(), rounded(Colors.CARD))
    }

    private fun tile(label: String, accent: Int): Tile {
        val value = text("—", 32f, Colors.TEXT, Typeface.create("sans-serif-light", Typeface.NORMAL))
        val sub = text("", 16f, Colors.TEXT2).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END   // IPv6 exit addresses are long
        }
        val box = column().apply {
            background = rounded(Colors.CARD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(text(label, 14f, accent, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply { letterSpacing = 0.12f })
            addView(value)
            addView(sub)
        }
        return Tile(box, value, sub)
    }

    private fun tileRow(vararg tiles: Tile) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        tiles.forEachIndexed { i, t ->
            addView(t.view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(14) })
        }
    }

    private fun buttonBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Colors.CARD_FOCUS, 40))
        addState(intArrayOf(), rounded(Colors.CARD, 40))
    }

    private fun rounded(color: Int, radiusDp: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun text(s: String, sp: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (face != null) typeface = face
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_TAG = 30
        private val TAGS = listOf("WireGuard", "OpenVPN UDP", "OpenVPN TCP", "IKEv2")

        fun fmtMbps(v: Double) = if (v < 10) "%.1f".format(v) else "%.0f".format(v)
        fun fmtData(bytes: Long) = if (bytes < 1_000_000) "%.1f MB".format(bytes / 1e6) else "%.0f MB".format(bytes / 1e6)

        /** Turns a technical failure into something a person on a sofa can act on. */
        fun plainError(message: String): String {
            val m = message.lowercase()
            val why = when {
                "refused" in m || "http 429" in m || "http 403" in m ->
                    "The test server turned us away, probably because too many tests came from this connection or VPN. " +
                        (Regex("retry in about (\\d+) min").find(m)?.let { "Try again in about ${it.groupValues[1]} min." } ?: "Wait a few minutes, or pick another server.")
                "no speed-test server reachable" in m || "no reply" in m || "every server failed" in m ->
                    "Couldn't reach any test server. Check that the TV is online, then try again."
                "no data transferred" in m ->
                    "The connection opened but no data came through. It may have dropped; try again."
                "unknown server" in m -> "That test server isn't available. Pick another one."
                else -> "Something went wrong with the connection. Try again."
            }
            return "Test failed. $why"
        }

        fun fmtMs(v: Double) = if (v <= 0) "—" else "%.0f".format(v)

        /** What the numbers mean for streaming on this TV. */
        fun verdictFor(r: SpeedTest.Result, net: NetInfo): String {
            val lines = ArrayList<String>()
            lines += when {
                r.downMbps >= 80 -> "Plenty for 4K60 streaming, even at the highest bitrates."
                r.downMbps >= 25 -> "Good for 4K streaming. Very high-bitrate 4K60 may need more."
                r.downMbps >= 15 -> "Enough for 4K on most services; high-bitrate 4K60 may buffer."
                r.downMbps >= 5 -> "Good for 1080p. 4K will likely buffer."
                r.downMbps >= 3 -> "OK for 720p / SD video only."
                else -> "Too slow for smooth HD video."
            }
            val bloat = maxOf(r.downLoadedMs, r.upLoadedMs) - r.pingMs
            if (bloat > 100) lines += "Latency rises by ${bloat.toInt()} ms under load, so expect slow starts and " +
                "stutter on live streams and calls."
            if (net.vpn) lines += "Measured through the VPN (exit IP ${r.server.ip.ifEmpty { "unknown" }}). " +
                "Try another VPN server or protocol to compare; All runs ranks them."
            else lines += "No VPN: this ran over your own connection, so sites see your real IP."
            lines += "Data used: ${fmtData(r.downBytes + r.upBytes)} (↓ ${fmtData(r.downBytes)} · ↑ ${fmtData(r.upBytes)})."
            return lines.joinToString("\n")
        }

        /** The privacy line: VPN state and the exit IP, colored by whether it's private. */
        fun privacyText(net: NetInfo, ip: String?, checking: Boolean): Pair<String, Int> = when {
            net.transport == "Offline" -> "Offline: no network (if a VPN is set to block without it, it may be down)" to Colors.WARN
            !net.vpn -> "Not private: no VPN. Sites see your real IP${ip?.let { " $it" } ?: ""}" to Colors.WARN
            checking -> "VPN on · checking exit IP…" to Colors.TEXT2
            ip == null -> "VPN on · couldn't check the exit IP" to Colors.TEXT2
            else -> "Private: VPN on · sites see $ip" to Colors.PING
        }
    }
}
