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
    private fun run(down: Double, vpn: Boolean, exit: String) =
        History.Entry(0, down, 10.0, 20.0, 30.0, vpn, "Ethernet", "Rome, Italy", exit)

    @Test fun groupsByWayOutBestFirst() {
        val g = History.groups(listOf(run(50.0, true, "1.1.1.1"), run(70.0, true, "1.1.1.1"),
            run(200.0, true, "2.2.2.2"), run(90.0, false, "3.3.3.3")))
        assertEquals(listOf("2.2.2.2", "3.3.3.3", "1.1.1.1"), g.map { it.label.substringAfterLast(" · ") })
        assertEquals(2, g.last().runs)
        assertEquals(60.0, g.last().downMbps, 0.001)
        assertEquals("Ethernet · no VPN · 3.3.3.3", g[1].label)
    }
}
