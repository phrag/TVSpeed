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
        val colo: String,   // server name (a Cloudflare location code in older entries)
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
                colo = o.optString("colo"),
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
                .put("network", it.network).put("colo", it.colo))
        }
        sp.edit().putString("runs", arr.toString()).apply()
    }

    companion object {
        const val MAX = 4
    }
}
