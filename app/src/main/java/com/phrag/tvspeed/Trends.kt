package com.phrag.tvspeed

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One word for the whole run, readable from the sofa: a symbol and a word as well as a color.
 * Judged on what streaming depends on, like the verdict: the single-stream download,
 * the upload, and how far latency rises under load.
 */
enum class Rating(val word: String, val symbol: String) {
    BAD("Bad", "✕"),
    GOOD("Good", "✓"),
    AWESOME("Awesome", "★");

    /** "★ AWESOME" */
    val badge get() = "$symbol ${word.uppercase()}"

    companion object {
        /** Below this single-stream speed 4K buffers (the verdict's "Enough for 4K" band starts here). */
        const val GOOD_STREAM_MBPS = 15.0
        /** The verdict's "Plenty for 4K60". */
        const val AWESOME_STREAM_MBPS = 80.0
        /** Below this upload, calls and uploads from the TV struggle. */
        const val GOOD_UP_MBPS = 1.0
        const val AWESOME_UP_MBPS = 10.0
        /** The verdict flags a rise this large: slow starts and stutter on live streams. */
        const val BAD_LATENCY_RISE_MS = 100.0
        const val AWESOME_LATENCY_RISE_MS = 30.0

        fun of(streamMbps: Double, upMbps: Double, pingMs: Double, loadedMs: Double): Rating {
            val rise = loadedMs - pingMs
            return when {
                streamMbps < GOOD_STREAM_MBPS || upMbps < GOOD_UP_MBPS || rise > BAD_LATENCY_RISE_MS -> BAD
                streamMbps >= AWESOME_STREAM_MBPS && upMbps >= AWESOME_UP_MBPS && rise <= AWESOME_LATENCY_RISE_MS -> AWESOME
                else -> GOOD
            }
        }

        /** Loaded latency is the worse of the two directions, as in the tile, the verdict and History. */
        fun of(r: SpeedTest.Result) = of(r.streamMbps, r.upMbps, r.pingMs, maxOf(r.downLoadedMs, r.upLoadedMs))

        /** Null for runs from older versions, which didn't keep the single-stream figure. */
        fun of(e: History.Entry): Rating? = if (e.streamMbps > 0) of(e.streamMbps, e.upMbps, e.pingMs, e.loadedMs) else null
    }
}

/**
 * What is normal for this TV: the latest run against the median of the earlier runs that went
 * out the same way (line, VPN state, tag and test server). Pure functions over History entries.
 */
object Trends {
    /** Earlier runs the same way out before "usual" means anything. */
    const val MIN_RUNS = 3
    /** How many of the newest earlier runs "usual" is the median of. */
    const val RECENT = 10
    /** Within this share of usual, a run reads as about usual. */
    const val STEADY = 0.10
    /** At least this share under the usual single-stream speed counts as worse than normal. */
    const val SLOWER = 0.25
    /** Or loaded latency at least this much over usual (and half again as high). */
    const val LAGGIER_MS = 100.0
    /** Runs (the latest included) needed to say which way speed is heading: the newer half against the older half. */
    const val TREND_RUNS = 6
    /** Runs a tag needs before the suggestion names it as the best. */
    const val MIN_TAG_RUNS = 2
    /** How much better than the current tag another tag must usually be to be named. */
    const val BETTER = 0.10

    enum class Heading(val text: String) { DOWN("trending down"), STEADY("steady"), UP("trending up") }

    data class Trend(
        /** Earlier runs the usual figures come from. */
        val runs: Int,
        val usualStreamMbps: Double,
        /** Latest against usual: -0.32 is 32% slower. */
        val streamChange: Double,
        val usualLoadedMs: Double,
        /** Latest minus usual; 0 when either has no figure. */
        val loadedChangeMs: Double,
        /** Null until there are [TREND_RUNS]. */
        val heading: Heading?,
        val slower: Boolean,
        val laggier: Boolean,
    ) {
        val worse get() = slower || laggier
    }

    /** Earlier runs that went out the same way as [latest] and have a single-stream figure, newest first. */
    fun comparable(latest: History.Entry, earlier: List<History.Entry>): List<History.Entry> =
        earlier.filter { it.streamMbps > 0 && History.wayOut(it) == History.wayOut(latest) }

    /** Null until there are [MIN_RUNS] comparable earlier runs. [earlier] is newest first and leaves [latest] out. */
    fun trend(latest: History.Entry, earlier: List<History.Entry>): Trend? {
        if (latest.streamMbps <= 0) return null
        val past = comparable(latest, earlier).take(RECENT)
        if (past.size < MIN_RUNS) return null
        val usual = SpeedTest.median(past.map { it.streamMbps })
        val change = latest.streamMbps / usual - 1
        val loads = past.map { it.loadedMs }.filter { it > 0 }
        val usualLoaded = if (loads.isEmpty()) 0.0 else SpeedTest.median(loads)
        val loadedChange = if (usualLoaded > 0 && latest.loadedMs > 0) latest.loadedMs - usualLoaded else 0.0
        return Trend(
            runs = past.size,
            usualStreamMbps = usual,
            streamChange = change,
            usualLoadedMs = usualLoaded,
            loadedChangeMs = loadedChange,
            heading = heading((listOf(latest) + past).map { it.streamMbps }),
            slower = change <= -SLOWER,
            laggier = loadedChange >= LAGGIER_MS && latest.loadedMs >= usualLoaded * 1.5,
        )
    }

    /** [newestFirst] speeds: the median of the newer half against the older half. Null with fewer than [TREND_RUNS]. */
    fun heading(newestFirst: List<Double>): Heading? {
        if (newestFirst.size < TREND_RUNS) return null
        val half = newestFirst.size / 2
        val older = SpeedTest.median(newestFirst.drop(newestFirst.size - half))
        val newer = SpeedTest.median(newestFirst.take(half))
        if (older <= 0) return null
        val change = newer / older - 1
        return when {
            change <= -STEADY -> Heading.DOWN
            change >= STEADY -> Heading.UP
            else -> Heading.STEADY
        }
    }

    /** One short line under the gauge, e.g. "▼ 32% under usual 48 Mbps · trending down". */
    fun line(latest: History.Entry, earlier: List<History.Entry>): String {
        val t = trend(latest, earlier)
        if (t == null) {
            if (latest.streamMbps <= 0) return ""
            val have = comparable(latest, earlier).size
            return "Usual speed after $MIN_RUNS runs this way ($have so far)"
        }
        val pct = abs(t.streamChange * 100).roundToInt()
        val usual = "usual ${MainActivity.fmtMbps(t.usualStreamMbps)} Mbps"
        val parts = ArrayList<String>()
        parts += when {
            abs(t.streamChange) < STEADY -> "≈ $usual"
            t.streamChange < 0 -> "▼ $pct% under $usual"
            else -> "▲ $pct% over $usual"
        }
        if (t.laggier) parts += "lag +${t.loadedChangeMs.roundToInt()} ms"
        t.heading?.let { parts += it.text }
        return parts.joinToString(" · ")
    }

    /** Median single-stream speed per tag over runs on [network] through a VPN, with at least [MIN_TAG_RUNS]. */
    fun tagMedians(runs: List<History.Entry>, network: String): Map<String, Double> =
        runs.filter { it.vpn && it.network == network && it.tag.isNotEmpty() && it.streamMbps > 0 }
            .groupBy { it.tag }
            .filterValues { it.size >= MIN_TAG_RUNS }
            .mapValues { (_, rs) -> SpeedTest.median(rs.map { it.streamMbps }) }

    /**
     * When the latest VPN run is clearly worse than usual for its way out, a nudge to switch VPN
     * server or protocol, naming the tag that has done best on this line if it is clearly better.
     * Null when there is no VPN, not enough history, nothing worse, or the run is rated awesome anyway.
     */
    fun suggestion(latest: History.Entry, earlier: List<History.Entry>): String? {
        if (!latest.vpn) return null
        val t = trend(latest, earlier) ?: return null
        if (!t.worse || Rating.of(latest) == Rating.AWESOME) return null
        val what = if (t.slower) "Slower than usual on this VPN." else "Lag under load is higher than usual on this VPN."
        val tags = tagMedians(earlier, latest.network)
        val mine = tags[latest.tag] ?: t.usualStreamMbps
        val best = tags.filterKeys { it != latest.tag }.maxByOrNull { it.value }?.takeIf { it.value >= mine * (1 + BETTER) }
        return if (best != null) "$what Try switching VPN server or protocol: ${best.key} has done best here " +
            "(usually ${MainActivity.fmtMbps(best.value)} Mbps on one stream)."
        else "$what Try switching VPN server or protocol, and tag each run to see which does best."
    }
}
