package com.phrag.tvspeed

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
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
    private lateinit var gauge: GaugeView
    private lateinit var button: TextView
    private lateinit var networkLine: TextView
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
            textSize = 22f
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
        right.addView(text("TV Speed", 28f, Colors.TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
        networkLine = text("", 15f, Colors.TEXT2)
        right.addView(networkLine, lp().apply { bottomMargin = dp(12) })

        down = tile("DOWNLOAD", Colors.DOWN)
        up = tile("UPLOAD", Colors.UP)
        ping = tile("PING", Colors.PING)
        jitter = tile("JITTER", Colors.PING)
        loaded = tile("UNDER LOAD", Colors.WARN)
        server = tile("SERVER", Colors.TEXT2)
        right.addView(tileRow(down, up, server))
        right.addView(tileRow(ping, jitter, loaded), lp().apply { topMargin = dp(10) })

        verdict = text("Measures the speed this TV actually gets, using Cloudflare's speed test servers.",
            16f, Colors.TEXT).apply { minLines = 3 }
        right.addView(verdict, lp().apply { topMargin = dp(14) })

        right.addView(text("RECENT", 13f, Colors.TEXT2, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.15f
        }, lp().apply { topMargin = dp(10); bottomMargin = dp(4) })
        historyRows = column()
        right.addView(historyRows)
        root.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.25f))

        setContentView(root)
        resetTiles()
        renderHistory()
        button.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        net = NetInfo.read(this).also { networkLine.text = it.summary }
        updater.check()   // offers a newer GitHub build, if any
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
        test = SpeedTest(this).also { it.start() }
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
        this.server.value.text = server.colo.ifEmpty { "?" }
        this.server.sub.text = listOf(server.country, server.ip).filter { it.isNotEmpty() }.joinToString(" · ")
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

        history.add(History.Entry(
            time = System.currentTimeMillis(),
            downMbps = result.downMbps, upMbps = result.upMbps,
            pingMs = result.pingMs, loadedMs = maxOf(result.downLoadedMs, result.upLoadedMs),
            vpn = n.vpn, network = n.transport, colo = result.server.colo,
        ))
        renderHistory()
    }

    override fun onError(message: String) {
        finishRun()
        verdict.setTextColor(Colors.WARN)
        verdict.text = "Test failed: $message. Check the connection and try again."
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
        server.value.text = "—"
        server.sub.text = "Cloudflare"
        gauge.setColor(Colors.DOWN)
        gauge.setSpeed(0.0)
        gauge.setReading("—", "", "ready")
    }

    private fun renderHistory() {
        historyRows.removeAllViews()
        val runs = history.entries()
        if (runs.isEmpty()) {
            historyRows.addView(text("No runs yet. Try one with your VPN on and one with it off to compare.", 15f, Colors.TEXT3))
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
                row.addView(text(s, 13f, color).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, weight))
            cell(whenText, 1f, Colors.TEXT2)
            cell("↓ ${fmtMbps(r.downMbps)}", 1f, Colors.DOWN)
            cell("↑ ${fmtMbps(r.upMbps)}", 0.9f, Colors.UP)
            cell("${fmtMs(r.pingMs)} / ${fmtMs(r.loadedMs)} ms", 1.3f, Colors.TEXT2)
            cell(listOf(r.network, if (r.vpn) "VPN" else "", r.colo).filter { it.isNotEmpty() }.joinToString(" · "),
                1.7f, if (r.vpn) Colors.WARN else Colors.TEXT2)
            historyRows.addView(row)
        }
    }

    private fun tile(label: String, accent: Int): Tile {
        val value = text("—", 28f, Colors.TEXT, Typeface.create("sans-serif-light", Typeface.NORMAL))
        val sub = text("", 13f, Colors.TEXT2).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END   // IPv6 exit addresses are long
        }
        val box = column().apply {
            background = rounded(Colors.CARD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(text(label, 11f, accent, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply { letterSpacing = 0.12f })
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
        fun fmtMbps(v: Double) = if (v < 10) "%.1f".format(v) else "%.0f".format(v)
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
            if (net.vpn) lines += "Measured through a VPN (exit ${r.server.colo.ifEmpty { "unknown" }}). " +
                "Run again with it off to compare."
            return lines.joinToString("\n")
        }
    }
}
