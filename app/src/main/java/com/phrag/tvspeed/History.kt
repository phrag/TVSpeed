package com.phrag.tvspeed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last [MAX] runs, newest first, so VPN servers (or VPN on and off) can be compared. */
class History(ctx: Context) {

    data class Entry(
        val time: Long,
        val downMbps: Double,
        val upMbps: Double,
        val pingMs: Double,
        val loadedMs: Double,
        val vpn: Boolean,
        val network: String,
        val place: String,   // where the test server is: "Rome, Italy"
        val server: String = "",   // which server there: "CESNET". Older runs have none.
        val tag: String = "",      // picked by the user: the VPN server or protocol used
    )

    /** Runs that went out the same way (same line, VPN state, tag and test server). The exit IP is never stored. */
    data class Group(val label: String, val vpn: Boolean, val runs: Int, val downMbps: Double, val upMbps: Double, val pingMs: Double)

    private val sp = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    init {
        // Older versions stored the exit IP with each run; rewrite them without it.
        if (sp.getString("runs", "")!!.contains("\"exit\"")) save(entries())
    }

    fun entries(): List<Entry> = try {
        val arr = JSONArray(sp.getString("runs", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                time = o.getLong("time"),
                downMbps = o.getDouble("down"),
                upMbps = o.getDouble("up"),
                pingMs = o.getDouble("ping"),
                loadedMs = o.optDouble("loaded", 0.0),
                vpn = o.optBoolean("vpn"),
                network = o.optString("network"),
                // Older runs stored a Cloudflare location code ("colo") instead.
                place = o.optString("place").ifEmpty { coloPlace(o.optString("colo")) },
                server = o.optString("server"),
                tag = o.optString("tag"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun add(e: Entry) = save((listOf(e) + entries()).take(MAX))

    /** Forgets every stored run. */
    fun clear() {
        sp.edit().remove("runs").apply()
    }

    private fun save(all: List<Entry>) {
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject()
                .put("time", it.time).put("down", it.downMbps).put("up", it.upMbps)
                .put("ping", it.pingMs).put("loaded", it.loadedMs).put("vpn", it.vpn)
                .put("network", it.network).put("place", it.place)
                .put("server", it.server).put("tag", it.tag))
        }
        sp.edit().putString("runs", arr.toString()).apply()
    }

    companion object {
        const val MAX = 20

        /** Rows shown on the main screen; the rest are under "All runs". */
        const val SHOWN = 4

        /** "Prague, Czechia · CESNET": the place alone can't tell the two Prague servers apart. */
        fun serverLabel(e: Entry) = listOf(e.place.ifEmpty { "server unknown" }, e.server).filter { it.isNotEmpty() }.joinToString(" · ")

        /** Median figures per way out (line, VPN state, tag and test server), best download first. */
        fun groups(runs: List<Entry>): List<Group> =
            runs.groupBy { listOf(it.vpn, it.network, it.tag, it.place, it.server) }.map { (_, rs) ->
                val e = rs.first()
                val label = listOf(e.network, if (e.vpn) "VPN" else "no VPN", e.tag, serverLabel(e)).filter { it.isNotEmpty() }.joinToString(" · ")
                Group(label, e.vpn, rs.size, SpeedTest.median(rs.map { it.downMbps }),
                    SpeedTest.median(rs.map { it.upMbps }), SpeedTest.median(rs.map { it.pingMs }))
            }.sortedByDescending { it.downMbps }

        /** Cloudflare location codes (IATA) that older runs stored; unknown codes stay as they are. */
        fun coloPlace(code: String): String = COLOS[code] ?: code

        private val COLOS = mapOf(
            "AMS" to "Amsterdam, Netherlands", "ARN" to "Stockholm, Sweden", "ATH" to "Athens, Greece",
            "BCN" to "Barcelona, Spain", "BER" to "Berlin, Germany", "BRU" to "Brussels, Belgium",
            "BUD" to "Budapest, Hungary", "CDG" to "Paris, France", "CPH" to "Copenhagen, Denmark",
            "DUB" to "Dublin, Ireland", "DUS" to "Düsseldorf, Germany", "FRA" to "Frankfurt, Germany",
            "HAM" to "Hamburg, Germany", "HEL" to "Helsinki, Finland", "LHR" to "London, UK",
            "LIS" to "Lisbon, Portugal", "MAD" to "Madrid, Spain", "MAN" to "Manchester, UK",
            "MRS" to "Marseille, France", "MUC" to "Munich, Germany", "MXP" to "Milan, Italy",
            "FCO" to "Rome, Italy", "OSL" to "Oslo, Norway", "PRG" to "Prague, Czechia",
            "SOF" to "Sofia, Bulgaria", "STR" to "Stuttgart, Germany", "TXL" to "Berlin, Germany",
            "VIE" to "Vienna, Austria", "WAW" to "Warsaw, Poland", "ZRH" to "Zurich, Switzerland",
        )
    }
}
