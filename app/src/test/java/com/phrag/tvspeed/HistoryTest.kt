package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    @Test fun oldCloudflareCodesBecomePlaces() {
        assertEquals("Berlin, Germany", History.coloPlace("TXL"))
        assertEquals("Frankfurt, Germany", History.coloPlace("FRA"))
    }

    @Test fun unknownCodesStayAsTheyAre() {
        assertEquals("XYZ", History.coloPlace("XYZ"))
        assertEquals("", History.coloPlace(""))
    }
}

class HistoryGroupsTest {
    private fun run(down: Double, vpn: Boolean, place: String, server: String = "", tag: String = "") =
        History.Entry(0, down, 10.0, 20.0, 30.0, vpn, "Ethernet", place, server, tag)

    @Test fun groupsByWayOutBestFirst() {
        val g = History.groups(listOf(run(50.0, true, "Rome, Italy"), run(70.0, true, "Rome, Italy"),
            run(200.0, true, "Berlin, Germany"), run(90.0, false, "Rome, Italy")))
        assertEquals(listOf("Ethernet · VPN · Berlin, Germany", "Ethernet · no VPN · Rome, Italy", "Ethernet · VPN · Rome, Italy"),
            g.map { it.label })
        assertEquals(2, g.last().runs)
        assertEquals(60.0, g.last().downMbps, 0.001)
    }

    @Test fun twoServersInOnePlaceStayApart() {
        val g = History.groups(listOf(run(50.0, true, "Prague, Czechia", "CESNET"), run(80.0, true, "Prague, Czechia", "CZ.NIC")))
        assertEquals(listOf("Ethernet · VPN · Prague, Czechia · CZ.NIC", "Ethernet · VPN · Prague, Czechia · CESNET"), g.map { it.label })
    }

    @Test fun tagsSplitGroupsAndShowInLabel() {
        val g = History.groups(listOf(run(50.0, true, "Rome, Italy", "GARR", "WireGuard"),
            run(60.0, true, "Rome, Italy", "GARR", "WireGuard"), run(90.0, true, "Rome, Italy", "GARR", "OpenVPN UDP")))
        assertEquals(listOf("Ethernet · VPN · OpenVPN UDP · Rome, Italy · GARR", "Ethernet · VPN · WireGuard · Rome, Italy · GARR"),
            g.map { it.label })
        assertEquals(2, g.last().runs)
    }

    @Test fun runsFromOlderVersionsStillGroup() {
        val g = History.groups(listOf(run(50.0, true, "Rome, Italy"), run(70.0, true, "Rome, Italy"), run(10.0, false, "")))
        assertEquals(listOf("Ethernet · VPN · Rome, Italy", "Ethernet · no VPN · server unknown"), g.map { it.label })
        assertEquals(2, g.first().runs)
    }
}

class ServerLogTest {
    @Test fun toStringLeavesOutTheIp() {
        val s = SpeedTest.Server("Op", "Rome", "Italy", "203.0.113.7").toString()
        assertEquals(false, s.contains("203.0.113.7"))
    }
}
