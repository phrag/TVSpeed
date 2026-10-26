package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {
    private val server = SpeedTest.Server("Op", "Rome", "Italy", "203.0.113.7")
    private val line = NetInfo("Ethernet", false, "")
    private val vpn = NetInfo("Ethernet", true, "")

    // Single stream matches the parallel figure, so the shortfall line stays out of these.
    private fun result(down: Double, ping: Double = 20.0, downLoaded: Double = 30.0, upLoaded: Double = 30.0) =
        SpeedTest.Result(
            pingMs = ping, jitterMs = 2.0, downLoadedMs = downLoaded, upLoadedMs = upLoaded,
            downMbps = down, streamMbps = down, upMbps = 10.0, downBytes = 0L, upBytes = 0L, server = server,
        )

    private fun speedLine(down: Double) = MainActivity.verdictFor(result(down), line).lines().first()

    @Test fun speedBandsStartAtTheirThreshold() {
        assertTrue(speedLine(80.0).startsWith("Plenty for 4K60"))
        assertTrue(speedLine(79.9).startsWith("Good for 4K streaming"))
        assertTrue(speedLine(25.0).startsWith("Good for 4K streaming"))
        assertTrue(speedLine(24.9).startsWith("Enough for 4K"))
        assertTrue(speedLine(15.0).startsWith("Enough for 4K"))
        assertTrue(speedLine(14.9).startsWith("Good for 1080p"))
        assertTrue(speedLine(5.0).startsWith("Good for 1080p"))
        assertTrue(speedLine(4.9).startsWith("OK for 720p"))
        assertTrue(speedLine(3.0).startsWith("OK for 720p"))
        assertTrue(speedLine(2.9).startsWith("Too slow"))
        assertTrue(speedLine(0.0).startsWith("Too slow"))
    }

    @Test fun loadedLatencyOverHundredMsOverIdleIsFlagged() {
        val v = MainActivity.verdictFor(result(50.0, ping = 17.0, downLoaded = 66.0, upLoaded = 214.0), line)
        assertTrue(v, v.contains("Latency rises by 197 ms under load"))
    }

    @Test fun latencyIncreaseOfExactlyHundredIsNotFlagged() {
        assertFalse(MainActivity.verdictFor(result(50.0, ping = 20.0, downLoaded = 120.0), line).contains("under load"))
    }

    @Test fun worseOfDownAndUpLoadedLatencyDecidesIt() {
        val v = MainActivity.verdictFor(result(50.0, ping = 20.0, downLoaded = 500.0, upLoaded = 30.0), line)
        assertTrue(v, v.contains("rises by 480 ms"))
    }

    @Test fun noVpnSaysSitesSeeTheRealIp() {
        val v = MainActivity.verdictFor(result(50.0), line)
        assertTrue(v.contains("No VPN"))
        assertFalse(v.contains("203.0.113.7"))
    }

    @Test fun vpnNamesTheExitIp() {
        assertTrue(MainActivity.verdictFor(result(50.0), vpn).contains("exit IP 203.0.113.7"))
    }

    @Test fun vpnWithoutKnownIpSaysUnknown() {
        val r = result(50.0).copy(server = SpeedTest.Server("Op", "Rome", "Italy", ""))
        assertTrue(MainActivity.verdictFor(r, vpn).contains("exit IP unknown"))
    }

    @Test fun linesAreJoinedByNewlines() {
        assertEquals(3, MainActivity.verdictFor(result(50.0), line).lines().size)
        assertEquals(4, MainActivity.verdictFor(result(50.0, downLoaded = 400.0), line).lines().size)
    }
}
