package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleStreamVerdictTest {

    private val line = NetInfo("Ethernet", vpn = false, detail = "")
    private val vpn = NetInfo("Ethernet", vpn = true, detail = "")

    private fun result(down: Double, stream: Double) = SpeedTest.Result(
        pingMs = 20.0, jitterMs = 1.0, downLoadedMs = 30.0, upLoadedMs = 30.0,
        downMbps = down, streamMbps = stream, upMbps = 50.0,
        server = SpeedTest.Server("CESNET", "Prague", "Czechia", "192.0.2.1"),
    )

    @Test fun judgedOnSingleStream() {
        // Six streams say 4K60 is easy; one connection only gets 1080p.
        val v = MainActivity.verdictFor(result(down = 200.0, stream = 12.0), vpn).lines()
        assertEquals("Good for 1080p. 4K will likely buffer.", v[0])
        assertEquals("One connection, as a video app uses, got only 12 of the 200 Mbps the line carries: " +
            "VPN tunnels often hold single connections back.", v[1])
    }

    @Test fun noShortfallLineWhenStreamKeepsUp() {
        val v = MainActivity.verdictFor(result(down = 100.0, stream = 90.0), line)
        assertTrue(v.startsWith("Plenty for 4K60"))
        assertFalse(v.contains("One connection"))
    }

    @Test fun shortfallWithoutVpnEndsPlainly() {
        val v = MainActivity.verdictFor(result(down = 100.0, stream = 30.0), line).lines()
        assertEquals("Good for 4K streaming. Very high-bitrate 4K60 may need more.", v[0])
        assertEquals("One connection, as a video app uses, got only 30 of the 100 Mbps the line carries.", v[1])
    }
}
