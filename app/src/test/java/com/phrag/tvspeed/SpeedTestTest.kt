package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestTest {

    @Test fun uploadDropsBytesStillInSendBuffers() {
        // 4 MB written on a slow uplink, only 1 MB of it on the wire yet.
        assertEquals(1_000_000L, SpeedTest.sentBytes(written = 4_000_000, txDelta = 1_000_000))
    }

    @Test fun uploadLeavesOutPacketOverhead() {
        // Buffers drained: the wire carried the payload plus headers.
        assertEquals(4_000_000L, SpeedTest.sentBytes(written = 4_000_000, txDelta = 4_150_000))
    }

    @Test fun uploadFallsBackToWrittenWhenUnsupported() {
        assertEquals(4_000_000L, SpeedTest.sentBytes(written = 4_000_000, txDelta = -1))
    }

    private fun head(vararg names: String) = names.map { n ->
        SpeedTest.Backend(n, "", "", "https://example.invalid/$n", "https://example.invalid/$n") to java.net.InetAddress.getLoopbackAddress()
    }

    private fun order(
        head: List<Pair<SpeedTest.Backend, java.net.InetAddress>>, times: Map<String, Double>,
        tied: Int = 1, last: String? = null,
    ) = SpeedTest.reorder(head, times, tied, last).map { it.first.name }

    @Test fun autoKeepsNearestWhenHttpsTimesAreClose() {
        // Madrid answers 20 ms faster on this run, which is script noise, not distance.
        val h = head("CESNET", "CZ.NIC", "RedIRIS")
        assertEquals(listOf("CESNET", "RedIRIS", "CZ.NIC"), order(h, mapOf("CESNET" to 70.0, "CZ.NIC" to 90.0, "RedIRIS" to 50.0)))
    }

    @Test fun autoDropsNearestWhenItsHttpsTimeIsFarWorse() {
        // A fake-near edge: fast handshake, the real server far away.
        val h = head("Edge", "CESNET", "CZ.NIC")
        assertEquals(listOf("CESNET", "CZ.NIC", "Edge"), order(h, mapOf("Edge" to 400.0, "CESNET" to 60.0, "CZ.NIC" to 80.0)))
    }

    @Test fun autoKeepsNearestWhenItDidNotAnswer() {
        val h = head("CESNET", "CZ.NIC")
        assertEquals(listOf("CZ.NIC", "CESNET"), order(h, mapOf("CZ.NIC" to 80.0)))
    }

    @Test fun equalConnectTimesAreTied() {
        // Through a VPN every server answered the handshake in about 24.8 ms.
        assertEquals(3, SpeedTest.tiedCount(listOf(24.7, 24.8, 25.1, 60.0)))
        assertEquals(8, SpeedTest.tiedCount(List(8) { 24.8 }))
    }

    @Test fun clearlyNearerServerIsNotTied() {
        assertEquals(1, SpeedTest.tiedCount(listOf(5.0, 10.0, 25.0)))
        assertEquals(1, SpeedTest.tiedCount(listOf(40.0, 50.0)))
        assertEquals(0, SpeedTest.tiedCount(emptyList()))
    }

    @Test fun autoKeepsLastServerAmongTied() {
        // Connect order is chance here; the previous pick stays though Rome answered a bit faster.
        val h = head("Freifunk", "GARR", "CESNET")
        val t = mapOf("Freifunk" to 48.0, "GARR" to 40.0, "CESNET" to 45.0)
        assertEquals("CESNET", order(h, t, tied = 3, last = "CESNET").first())
        assertEquals("CESNET", order(head("GARR", "CESNET", "Freifunk"), t, tied = 3, last = "CESNET").first())
    }

    @Test fun autoPicksFastestHttpsAmongTiedWithoutHistory() {
        val t = mapOf("Freifunk" to 48.0, "GARR" to 60.0, "CESNET" to 35.0)
        assertEquals("CESNET", order(head("Freifunk", "GARR", "CESNET"), t, tied = 3).first())
        assertEquals("CESNET", order(head("GARR", "CESNET", "Freifunk"), t, tied = 3).first())
    }

    @Test fun autoBreaksExactHttpsTiesByListOrder() {
        val t = mapOf("CESNET" to 40.0, "CZ.NIC" to 40.0)
        assertEquals("CESNET", order(head("CESNET", "CZ.NIC"), t, tied = 2).first())
    }

    @Test fun autoIgnoresLastServerWhenAnotherIsNearer() {
        // The last pick isn't among the tied: the connection changed, so the nearest wins.
        val h = head("CESNET", "GARR")
        assertEquals("CESNET", order(h, mapOf("CESNET" to 40.0, "GARR" to 45.0), tied = 1, last = "GARR").first())
    }

    @Test fun autoDropsLastServerWhenItTurnsOutToBeAFarEdge() {
        // The last pick is tied by handshake but its real server is far: anycast protection still applies.
        val h = head("Freifunk", "CESNET", "CZ.NIC")
        val t = mapOf("Freifunk" to 400.0, "CESNET" to 60.0, "CZ.NIC" to 80.0)
        assertEquals("CESNET", order(h, t, tied = 3, last = "Freifunk").first())
    }
}
