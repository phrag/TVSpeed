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
}
