package com.phrag.tvspeed

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/**
 * One speed test run against Cloudflare's public speed-test endpoints
 * (speed.cloudflare.com: `__down`, `__up`, `cdn-cgi/trace`; free, no key).
 *
 * Phases: idle latency → download → upload. Latency is TCP connect time to
 * the edge (exactly one round trip, no server processing), sampled again
 * while each transfer runs to show how much the connection's latency grows
 * under load — the usual cause of buffering on a "fast" line or a VPN.
 *
 * Throughput uses several parallel streams (one TCP stream under-reports,
 * badly so through a VPN), drops the first seconds of TCP slow start and
 * reports the average over the rest. Packet loss can't be seen over HTTP.
 *
 * [Listener] callbacks arrive on the main thread and stop after [cancel].
 */
class SpeedTest(private val listener: Listener) {

    enum class Phase(val label: String) {
        CONNECTING("Connecting"), LATENCY("Latency"), DOWNLOAD("Download"), UPLOAD("Upload")
    }

    data class Result(
        val pingMs: Double,
        val jitterMs: Double,
        val downLoadedMs: Double,
        val upLoadedMs: Double,
        val downMbps: Double,
        val upMbps: Double,
        val server: Server,
    )

    /** Where Cloudflare sees us: edge location (IATA code), country and public IP. */
    data class Server(val colo: String, val country: String, val ip: String)

    interface Listener {
        fun onServer(server: Server) {}
        fun onPhase(phase: Phase) {}
        /** Live throughput while a transfer runs (about 5× a second). */
        fun onSpeed(phase: Phase, mbps: Double) {}
        /** A latency sample; [loaded] while a transfer runs. */
        fun onLatency(ms: Double, loaded: Boolean) {}
        fun onDone(result: Result) {}
        fun onError(message: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        check(thread == null) { "single use" }
        thread = Thread(::run, "tvspeed-test").apply { start() }
    }

    /** Abort the run; no further callbacks. Safe to call from any thread, any number of times. */
    fun cancel() {
        cancelled.set(true)
        thread?.interrupt()
    }

    private fun post(block: Listener.() -> Unit) {
        main.post { if (!cancelled.get()) listener.block() }
    }

    private fun run() {
        try {
            post { onPhase(Phase.CONNECTING) }
            val server = trace()
            post { onServer(server) }
            val addr = InetAddress.getByName(HOST)

            post { onPhase(Phase.LATENCY) }
            val idle = ArrayList<Double>()
            repeat(PING_SAMPLES) {
                if (cancelled.get()) return
                connectTime(addr)?.let { ms ->
                    idle.add(ms)
                    post { onLatency(ms, loaded = false) }
                }
                SystemClock.sleep(PING_GAP_MS)
            }
            if (idle.isEmpty()) throw IOException("No reply from $HOST")

            val down = transfer(Phase.DOWNLOAD, addr, DOWN_STREAMS, ::downloadLoop)
            val up = transfer(Phase.UPLOAD, addr, UP_STREAMS, ::uploadLoop)
            if (cancelled.get()) return
            val result = Result(
                pingMs = median(idle),
                jitterMs = jitter(idle),
                downLoadedMs = down.loadedMs,
                upLoadedMs = up.loadedMs,
                downMbps = down.mbps,
                upMbps = up.mbps,
                server = server,
            )
            Log.i(TAG, "result: $result")
            post { onDone(result) }
        } catch (e: InterruptedException) {
            // cancelled
        } catch (e: Exception) {
            if (cancelled.get()) return
            Log.w(TAG, "test failed", e)
            post { onError(e.message ?: e.javaClass.simpleName) }
        }
    }

    // ---- transfers --------------------------------------------------------

    private class Transfer(val mbps: Double, val loadedMs: Double)

    /** Shared state for one phase's worker threads. */
    private inner class Streams {
        val bytes = AtomicLong()
        val stop = AtomicBoolean(false)
        val errors = AtomicLong()
        val open: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
        val running get() = !stop.get() && !cancelled.get()
    }

    /**
     * Run [streams] copies of [loop] for [TRANSFER_MS], reporting live speed,
     * plus a latency prober. Returns the average after [WARMUP_MS].
     */
    private fun transfer(phase: Phase, addr: InetAddress, streams: Int, loop: (Streams) -> Unit): Transfer {
        if (cancelled.get()) return Transfer(0.0, 0.0)
        post { onPhase(phase) }
        val s = Streams()
        val workers = List(streams) { i -> Thread({ loop(s) }, "tvspeed-${phase.name.lowercase()}-$i") }
        val loaded = ArrayList<Double>()
        val prober = Thread({
            while (s.running) {
                connectTime(addr)?.let { ms ->
                    synchronized(loaded) { loaded.add(ms) }
                    post { onLatency(ms, loaded = true) }
                }
                SystemClock.sleep(LOADED_PING_GAP_MS)
            }
        }, "tvspeed-probe")
        workers.forEach { it.start() }
        prober.start()

        val t0 = SystemClock.elapsedRealtime()
        var warmT = 0L
        var warmBytes = 0L
        val window = ArrayDeque<Pair<Long, Long>>()   // (time, bytes) over the last second, for the live figure
        try {
            while (!cancelled.get()) {
                Thread.sleep(SAMPLE_MS)
                val now = SystemClock.elapsedRealtime()
                val b = s.bytes.get()
                window.addLast(now to b)
                while (window.size > 2 && now - window.first().first > 1_000) window.removeFirst()
                val (wt, wb) = window.first()
                if (now > wt) {
                    val live = mbps(b - wb, now - wt)
                    post { onSpeed(phase, live) }
                }
                if (warmT == 0L && now - t0 >= WARMUP_MS) {
                    warmT = now
                    warmBytes = b
                }
                if (now - t0 >= TRANSFER_MS) break
            }
        } finally {
            s.stop.set(true)
            s.open.forEach { runCatching { it.disconnect() } }   // unblock reads/writes
        }
        val end = SystemClock.elapsedRealtime()
        val total = s.bytes.get()
        workers.forEach { it.join(2_000) }
        prober.join(2_000)
        if (cancelled.get()) throw InterruptedException()
        if (total == 0L) throw IOException("${phase.label} failed: no data transferred (${s.errors.get()} errors)")
        val result = if (warmT > 0 && end > warmT) mbps(total - warmBytes, end - warmT) else mbps(total, end - t0)
        val loadedMedian = synchronized(loaded) { if (loaded.isEmpty()) 0.0 else median(loaded) }
        Log.i(TAG, "${phase.name}: ${"%.1f".format(result)} Mbit/s, ${total / 1_000_000} MB, loaded ${"%.0f".format(loadedMedian)} ms, ${s.errors.get()} errors")
        return Transfer(result, loadedMedian)
    }

    private fun downloadLoop(s: Streams) {
        val buf = ByteArray(BUF)
        while (s.running) {
            val c = open("$BASE/__down?bytes=$DOWN_BYTES")
            s.open.add(c)
            try {
                c.inputStream.use { ins ->
                    while (s.running) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        s.bytes.addAndGet(n.toLong())
                    }
                }
            } catch (e: IOException) {
                if (s.running) { s.errors.incrementAndGet(); SystemClock.sleep(250) }
            } finally {
                s.open.remove(c)
                if (!s.running) c.disconnect()
            }
        }
    }

    private fun uploadLoop(s: Streams) {
        val buf = ByteArray(BUF).also { Random.nextBytes(it) }   // incompressible
        while (s.running) {
            val c = open("$BASE/__up")
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(UP_BYTES)   // stream it; don't buffer the body in memory
            c.setRequestProperty("Content-Type", "application/octet-stream")
            s.open.add(c)
            try {
                c.outputStream.use { out ->
                    var left = UP_BYTES
                    while (left > 0 && s.running) {
                        val n = minOf(left, BUF.toLong()).toInt()
                        out.write(buf, 0, n)
                        s.bytes.addAndGet(n.toLong())
                        left -= n
                    }
                }
                if (s.running) c.inputStream.use { it.readBytes() }
            } catch (e: IOException) {
                if (s.running) { s.errors.incrementAndGet(); SystemClock.sleep(250) }
            } finally {
                s.open.remove(c)
                if (!s.running) c.disconnect()
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 10_000
        useCaches = false
        // Count the bytes on the wire, not a decompressed body.
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("User-Agent", "TVSpeed/${BuildConfig.VERSION_NAME}")
    }

    /** cdn-cgi/trace: plain key=value lines (colo=FRA, loc=DE, ip=…). */
    private fun trace(): Server {
        val c = open("$BASE/cdn-cgi/trace")
        try {
            val kv = c.inputStream.bufferedReader().readLines()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
            return Server(kv["colo"].orEmpty(), kv["loc"].orEmpty(), kv["ip"].orEmpty())
        } finally {
            c.disconnect()
        }
    }

    /** One TCP handshake to the edge, in ms; null on timeout/failure. */
    private fun connectTime(addr: InetAddress): Double? = try {
        Socket().use { sock ->
            val t = System.nanoTime()
            sock.connect(InetSocketAddress(addr, 443), 3_000)
            (System.nanoTime() - t) / 1e6
        }
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val TAG = "TVSpeed"
        const val HOST = "speed.cloudflare.com"
        private const val BASE = "https://$HOST"

        private const val PING_SAMPLES = 20
        private const val PING_GAP_MS = 100L
        private const val LOADED_PING_GAP_MS = 300L
        private const val DOWN_STREAMS = 6
        private const val UP_STREAMS = 4
        private const val DOWN_BYTES = 25_000_000   // Cloudflare refuses > 50 MB per request
        private const val UP_BYTES = 8_000_000L
        private const val TRANSFER_MS = 12_000L
        private const val WARMUP_MS = 2_000L
        private const val SAMPLE_MS = 200L
        private const val BUF = 64 * 1024

        private fun mbps(bytes: Long, ms: Long) = if (ms <= 0) 0.0 else bytes * 8.0 / ms / 1_000.0

        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** Mean absolute difference between consecutive samples. */
        fun jitter(xs: List<Double>): Double =
            if (xs.size < 2) 0.0 else xs.zipWithNext { a, b -> abs(b - a) }.average()
    }
}
