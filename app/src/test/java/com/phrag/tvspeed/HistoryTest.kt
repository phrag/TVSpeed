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
