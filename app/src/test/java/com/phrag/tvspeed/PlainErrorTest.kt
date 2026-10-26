package com.phrag.tvspeed

import org.junit.Assert.assertTrue
import org.junit.Test

class PlainErrorTest {

    @Test fun refusalKeepsRetryTime() {
        val m = MainActivity.plainError("refused, HTTP 429, retry in about 5 min")
        assertTrue(m, "about 5 min" in m && "HTTP" !in m)
    }

    @Test fun unreachableIsPlain() {
        val m = MainActivity.plainError("every server failed (GARR: no reply)")
        assertTrue(m, "online" in m && "GARR" !in m)
    }

    @Test fun dataUsed() {
        assertTrue(MainActivity.fmtData(123_400_000) == "123 MB")
    }
}
