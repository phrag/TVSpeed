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

    private fun order(head: List<Pair<SpeedTest.Backend, java.net.InetAddress>>, times: Map<String, Double>) =
        SpeedTest.reorder(head, times).map { it.first.name }

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
}
