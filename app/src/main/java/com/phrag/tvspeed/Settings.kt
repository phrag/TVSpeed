package com.phrag.tvspeed

import android.content.Context

/**
 * The test options picked on the Settings screen, kept on this TV. Anything stored that is
 * no longer one of the choices (an older build's value) reads back as the default.
 */
class Settings(context: Context) {

    /** How long each transfer phase runs. Two seconds of each are warm-up and left out. */
    enum class Length(val label: String, val transferMs: Long, val streamMs: Long) {
        QUICK("Quick", 6_000L, 5_000L),
        STANDARD("Standard", SpeedTest.TRANSFER_MS, SpeedTest.STREAM_MS),
        LONG("Long", 20_000L, 12_000L);

        /** e.g. "Standard · 12 s per direction" */
        val description get() = "$label · ${transferMs / 1000} s per direction"
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var length: Length
        get() = lengthOf(prefs.getString("length", null))
        set(v) = prefs.edit().putString("length", v.name).apply()

    var downStreams: Int
        get() = pick(prefs.getInt("down_streams", 0), DOWN_CHOICES, SpeedTest.DOWN_STREAMS)
        set(v) = prefs.edit().putInt("down_streams", v).apply()

    var upStreams: Int
        get() = pick(prefs.getInt("up_streams", 0), UP_CHOICES, SpeedTest.UP_STREAMS)
        set(v) = prefs.edit().putInt("up_streams", v).apply()

    val config get() = config(length, downStreams, upStreams)

    fun reset() = prefs.edit().clear().apply()

    companion object {
        val DOWN_CHOICES = listOf(1, 2, 4, 6, 8, 12)
        val UP_CHOICES = listOf(1, 2, 4, 6, 8)

        fun lengthOf(name: String?) = Length.values().find { it.name == name } ?: Length.STANDARD

        fun pick(stored: Int, choices: List<Int>, default: Int) = if (stored in choices) stored else default

        fun config(length: Length, downStreams: Int, upStreams: Int) = SpeedTest.Config(
            downStreams = downStreams,
            upStreams = upStreams,
            transferMs = length.transferMs,
            streamMs = length.streamMs,
        )
    }
}
