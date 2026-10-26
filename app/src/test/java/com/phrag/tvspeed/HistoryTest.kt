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
    private fun run(down: Double, vpn: Boolean, place: String) =
        History.Entry(0, down, 10.0, 20.0, 30.0, vpn, "Ethernet", place)

    @Test fun groupsByWayOutBestFirst() {
        val g = History.groups(listOf(run(50.0, true, "Rome, Italy"), run(70.0, true, "Rome, Italy"),
            run(200.0, true, "Madrid, Spain"), run(90.0, false, "Rome, Italy")))
        assertEquals(listOf("VPN · Madrid, Spain", "no VPN · Rome, Italy", "VPN · Rome, Italy"),
            g.map { it.label.substringAfter(" · ") })
        assertEquals(2, g.last().runs)
        assertEquals(60.0, g.last().downMbps, 0.001)
        assertEquals("Ethernet · no VPN · Rome, Italy", g[1].label)
    }

    @Test fun labelsNeverCarryAnIp() {
        val g = History.groups(listOf(run(50.0, true, "")))
        assertEquals("Ethernet · VPN · server unknown", g.single().label)
    }
}
