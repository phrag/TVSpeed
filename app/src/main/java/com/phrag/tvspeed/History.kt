package com.phrag.tvspeed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last few runs, newest first, so "VPN on" and "VPN off" can be compared side by side. */
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
    )

    private val sp = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

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
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun add(e: Entry) {
        val all = (listOf(e) + entries()).take(MAX)
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject()
                .put("time", it.time).put("down", it.downMbps).put("up", it.upMbps)
                .put("ping", it.pingMs).put("loaded", it.loadedMs).put("vpn", it.vpn)
                .put("network", it.network).put("place", it.place))
        }
        sp.edit().putString("runs", arr.toString()).apply()
    }

    companion object {
        const val MAX = 4

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
