package com.phrag.tvspeed

import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.random.Random
import org.json.JSONObject

/**
 * One speed test run against public speed-test servers run by European
 * non-profits and research networks ([SERVERS]: free, no key, no tracking,
 * no third-party IP lookups). The nearest one by connect time from this
 * connection (VPN exit included) is used; if it refuses (rate limit) or
 * fails, the run starts over on the next.
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
 * [only] names one server from [SERVERS] to test against, with no fallback.
 */
class SpeedTest(private val listener: Listener, private val only: String? = null) {

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
        val downBytes: Long,
        val upBytes: Long,
        val server: Server,
    )

    /** The server a run uses, and the public IP it sees us at (VPN exit or line). */
    data class Server(val name: String, val city: String, val country: String, val ip: String) {
        val place get() = "$city, $country"

        /** Leaves the IP out so it never reaches logcat. */
        override fun toString() = "Server(name=$name, city=$city, country=$country)"
    }

    /**
     * A speed-test server: [down] serves a large body per GET, [up] accepts POSTs,
     * [ip] (optional) returns LibreSpeed getIP.php JSON with our public IP.
     */
    class Backend(
        val name: String, val city: String, val country: String,
        val down: String, val up: String, val ip: String? = null,
    ) {
        val host: String get() = URL(down).host
        val place get() = "$city, $country"

        companion object {
            /** LibreSpeed: `garbage.php`, `empty.php`, `getIP.php` (without `isp`: no third-party lookup). */
            fun libreSpeed(name: String, city: String, country: String, base: String) =
                Backend(name, city, country, "$base/garbage.php?ckSize=$DOWN_MIB", "$base/empty.php", "$base/getIP.php")

            /** OpenSpeedTest: a fixed 30 MiB `/downloading`, `/upload`; no IP endpoint. */
            fun openSpeedTest(name: String, city: String, country: String, base: String) =
                Backend(name, city, country, "$base/downloading", "$base/upload")
        }
    }

    interface Listener {
        /** Measuring against [server] starts now; on a fallback, earlier samples are void. */
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
            val ranked = rank()
            if (ranked.isEmpty()) throw IOException("No speed-test server reachable")
            val failures = ArrayList<String>()
            for ((backend, addr) in ranked) {
                // A dropped connection gets one more try on the same server; a refusal
                // (rate limit) goes straight to the next one.
                for (attempt in 1..2) {
                    try {
                        measure(backend, addr)
                        return
                    } catch (e: IOException) {
                        if (cancelled.get()) return
                        val refused = e is HttpError && e.refused || e.message.orEmpty().startsWith("refused")
                        if (attempt == 1 && !refused) {
                            Log.w(TAG, "${backend.name} failed, retrying once", e)
                            SystemClock.sleep(RETRY_DELAY_MS)
                            continue
                        }
                        Log.w(TAG, "${backend.name} failed, trying the next server", e)
                        failures += "${backend.name}: ${e.message}"
                        break
                    }
                }
            }
            throw IOException(failures.joinToString("; ", prefix = "every server failed (") + ")")
        } catch (e: InterruptedException) {
            // cancelled
        } catch (e: Exception) {
            if (cancelled.get()) return
            Log.w(TAG, "test failed", e)
            post { onError(e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Reachable servers, nearest (by TCP connect time) first. Probed in parallel. */
    private fun rank(): List<Pair<Backend, InetAddress>> {
        val found = ConcurrentHashMap<Backend, Pair<InetAddress, Double>>()
        val candidates = SERVERS.filter { only == null || it.name.equals(only, ignoreCase = true) }
        if (candidates.isEmpty()) throw IOException("Unknown server \"$only\"")
        candidates.map { b ->
            Thread({
                try {
                    // Every address (IPv6 and IPv4): one family can be broken on a given
                    // network while the other works.
                    InetAddress.getAllByName(b.host)
                        .mapNotNull { addr ->
                            val ms = (1..RANK_SAMPLES).mapNotNull { connectTime(addr) }
                            if (ms.size * 2 > RANK_SAMPLES) addr to median(ms) else null   // mostly answering
                        }
                        .minByOrNull { it.second }
                        ?.let { found[b] = it }
                } catch (e: IOException) {
                    Log.i(TAG, "${b.name} unreachable: $e")
                }
            }, "tvspeed-rank").apply { start() }
        }.forEach { it.join(10_000) }
        if (cancelled.get()) throw InterruptedException()
        return candidates.mapNotNull { b -> found[b]?.let { Triple(b, it.first, it.second) } }
            .sortedBy { it.third }
            .map { it.first to it.second }
    }

    /** The whole run against one server; throws if it refuses or fails so [run] can fall back. */
    private fun measure(backend: Backend, addr: InetAddress) {
        val server = Server(backend.name, backend.city, backend.country, publicIp(backend))
        post { onServer(server) }

        post { onPhase(Phase.LATENCY) }
        val idle = ArrayList<Double>()
        repeat(PING_SAMPLES) {
            if (cancelled.get()) throw InterruptedException()
            connectTime(addr)?.let { ms ->
                idle.add(ms)
                post { onLatency(ms, loaded = false) }
            }
            SystemClock.sleep(PING_GAP_MS)
        }
        if (idle.isEmpty()) throw IOException("no reply")

        val down = transfer(Phase.DOWNLOAD, addr, DOWN_STREAMS) { downloadLoop(backend, it) }
        val up = transfer(Phase.UPLOAD, addr, UP_STREAMS) { uploadLoop(backend, it) }
        if (cancelled.get()) throw InterruptedException()
        val result = Result(
            pingMs = median(idle),
            jitterMs = jitter(idle),
            downLoadedMs = down.loadedMs,
            upLoadedMs = up.loadedMs,
            downMbps = down.mbps,
            upMbps = up.mbps,
            downBytes = down.bytes,
            upBytes = up.bytes,
            server = server,
        )
        Log.i(TAG, "result: $result")
        post { onDone(result) }
    }

    // ---- transfers --------------------------------------------------------

    private class Transfer(val mbps: Double, val loadedMs: Double, val bytes: Long = 0L)

    /** Shared state for one phase's worker threads. */
    private inner class Streams(phase: Phase) {
        val bytes = AtomicLong()   // read from, or written into, the sockets
        val stop = AtomicBoolean(false)
        val errors = AtomicLong()
        val firstError = AtomicReference<String>()   // why requests fail, for the error message
        val refused = AtomicReference<HttpError>()     // set: give up now instead of retrying
        val open: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
        val running get() = !stop.get() && !cancelled.get()

        // A write returns once the bytes are in the socket's send buffer (megabytes,
        // on Android), long before they leave; on a slow or VPN uplink counting
        // written bytes inflates upload. So for upload also read what this app has
        // actually transmitted, where the platform reports it.
        private val tx0 = if (phase == Phase.UPLOAD) uidTx() else -1L

        /** Bytes that count toward the speed so far: for upload, only those sent. */
        fun counted(): Long {
            val written = bytes.get()
            if (tx0 < 0) return written
            val tx = uidTx()
            return if (tx < 0) written else sentBytes(written, tx - tx0)
        }

        fun fail(e: IOException) {
            errors.incrementAndGet()
            if (e is HttpError && e.refused) {
                refused.compareAndSet(null, e)
                stop.set(true)
            }
            if (firstError.compareAndSet(null, e.toString())) Log.w(TAG, "transfer request failed", e)
        }
    }

    /**
     * Run [streams] copies of [loop] for [TRANSFER_MS], reporting live speed,
     * plus a latency prober. Returns the average after [WARMUP_MS].
     */
    private fun transfer(phase: Phase, addr: InetAddress, streams: Int, loop: (Streams) -> Unit): Transfer {
        if (cancelled.get()) return Transfer(0.0, 0.0)
        post { onPhase(phase) }
        val s = Streams(phase)
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
                val b = s.counted()
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
                if (now - t0 >= TRANSFER_MS || s.refused.get() != null) break
            }
        } finally {
            s.stop.set(true)
            s.open.forEach { runCatching { it.disconnect() } }   // unblock reads/writes
        }
        val end = SystemClock.elapsedRealtime()
        val total = s.counted()
        workers.forEach { it.join(2_000) }
        prober.join(2_000)
        if (cancelled.get()) throw InterruptedException()
        s.refused.get()?.let { throw IOException(refusedMessage(it)) }
        if (total == 0L) throw IOException("${phase.label} failed: no data transferred (${s.errors.get()} errors: ${s.firstError.get()})")
        val result = if (warmT > 0 && end > warmT) mbps(total - warmBytes, end - warmT) else mbps(total, end - t0)
        val loadedMedian = synchronized(loaded) { if (loaded.isEmpty()) 0.0 else median(loaded) }
        Log.i(TAG, "${phase.name}: ${"%.1f".format(result)} Mbit/s, ${total / 1_000_000} MB, loaded ${"%.0f".format(loadedMedian)} ms, ${s.errors.get()} errors")
        return Transfer(result, loadedMedian, total)
    }

    private fun downloadLoop(b: Backend, s: Streams) {
        val buf = ByteArray(BUF)
        while (s.running) {
            val c = open(b.down)
            s.open.add(c)
            try {
                httpOk(c)
                c.inputStream.use { ins ->
                    while (s.running) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        s.bytes.addAndGet(n.toLong())
                    }
                }
            } catch (e: IOException) {
                if (s.running) { s.fail(e); SystemClock.sleep(250) }
            } finally {
                s.open.remove(c)
                if (!s.running) c.disconnect()
            }
        }
    }

    private fun uploadLoop(b: Backend, s: Streams) {
        val buf = ByteArray(BUF).also { Random.nextBytes(it) }   // incompressible
        while (s.running) {
            val c = open(b.up)
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
                if (s.running) { httpOk(c); c.inputStream.use { it.readBytes() } }
            } catch (e: IOException) {
                if (s.running) { s.fail(e); SystemClock.sleep(250) }
            } finally {
                s.open.remove(c)
                if (!s.running) c.disconnect()
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private class HttpError(val code: Int, val retryAfterS: Long?) : IOException("HTTP $code") {
        /** Retrying won't help: the server is refusing this client (VPN exits often hit this). */
        val refused get() = code == 429 || code == 403
    }

    private fun refusedMessage(e: HttpError): String {
        val wait = e.retryAfterS?.let { ", retry in about ${(it + 59) / 60} min" } ?: ""
        return "refused, HTTP ${e.code}$wait"
    }

    /** Fail with the status (e.g. "HTTP 429") rather than Android's bare FileNotFoundException. */
    private fun httpOk(c: HttpURLConnection) {
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw HttpError(code, c.getHeaderField("Retry-After")?.toLongOrNull())
        }
    }

    /** Our public IP as the server sees it; empty if the server has no IP endpoint. */
    private fun publicIp(b: Backend): String {
        val url = b.ip ?: return ""
        return try {
            val c = open(url)
            try {
                httpOk(c)
                JSONObject(c.inputStream.bufferedReader().readText()).optString("processedString")
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            if (e is HttpError && e.refused) throw IOException(refusedMessage(e))
            ""   // only shown on screen; the test itself doesn't need it
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

        /**
         * Public LibreSpeed / OpenSpeedTest servers run by European non-profits,
         * community networks and research networks, not ad or CDN companies.
         * Each handles 25+ MiB downloads and 8 MB uploads over HTTPS.
         */
        val SERVERS = listOf(
            // Freifunk München: community network, non-profit; anycast in Germany.
            Backend.openSpeedTest("Freifunk", "Munich", "Germany", "https://speedtest.ffmuc.net"),
            Backend.libreSpeed("CESNET", "Prague", "Czechia", "https://speedtest.cesnet.cz/backend"),       // Czech research network
            Backend.libreSpeed("CZ.NIC", "Prague", "Czechia", "https://librespeed.turris.cz/backend"),      // .cz registry, non-profit
            Backend.libreSpeed("GARR", "Rome", "Italy", "https://st-be-rm2.infra.garr.it"),                 // Italian research network
            Backend.libreSpeed("SOX", "Belgrade", "Serbia", "https://speedtest1.sox.rs/librespeed/backend"), // Serbian internet exchange
            Backend.libreSpeed("RedIRIS", "Madrid", "Spain", "https://speedtest.rediris.es/backend"),       // Spanish research network
        )

        private const val PING_SAMPLES = 20
        private const val PING_GAP_MS = 100L
        private const val LOADED_PING_GAP_MS = 300L
        private const val DOWN_STREAMS = 6
        private const val UP_STREAMS = 4
        private const val DOWN_MIB = 25   // garbage.php's ckSize: MiB per request
        private const val RANK_SAMPLES = 5   // connect times per address when picking the nearest
        private const val UP_BYTES = 8_000_000L
        private const val TRANSFER_MS = 12_000L
        private const val WARMUP_MS = 2_000L
        private const val SAMPLE_MS = 200L
        private const val BUF = 64 * 1024

        private const val RETRY_DELAY_MS = 1_000L

        private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            useCaches = false
            // Count the bytes on the wire, not a decompressed body.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "TVSpeed/${BuildConfig.VERSION_NAME}")
        }

        /**
         * The public IP this TV reaches the internet from (the VPN exit, if one is up),
         * asked of the LibreSpeed servers in turn; null if none answers. Blocking.
         */
        fun exitIp(): Pair<Backend, String>? {
            for (b in SERVERS) {
                val url = b.ip ?: continue
                try {
                    val c = open(url)
                    try {
                        if (c.responseCode != HttpURLConnection.HTTP_OK) continue
                        val ip = JSONObject(c.inputStream.bufferedReader().readText()).optString("processedString")
                        if (ip.isNotBlank()) return b to ip.trim()
                    } finally {
                        c.disconnect()
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "exit IP from ${b.name} failed: $e")
                }
            }
            return null
        }

        /** This app's transmitted bytes (headers included); negative if unsupported. */
        private fun uidTx(): Long = TrafficStats.getUidTxBytes(Process.myUid())

        /**
         * Upload bytes that have left the device: [written] into the sockets, capped
         * by [txDelta], what the app transmitted meanwhile (negative: unknown). Each
         * overcounts in its own way, written bytes by what still sits in send
         * buffers and transmitted bytes by a few percent of TCP/TLS overhead, so the
         * smaller is the closer.
         */
        fun sentBytes(written: Long, txDelta: Long): Long =
            if (txDelta < 0) written else minOf(written, txDelta)

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
