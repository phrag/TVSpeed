package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class NetInfoTest {

    @Test fun prefersReceiveRate() {
        // Shield TV idle: sending at 6 Mbps, receiving at 520.
        assertEquals("520 Mbps link", NetInfo.linkLabel(rxMbps = 520, legacyMbps = 6))
    }

    @Test fun fallsBackWhenReceiveRateUnknown() {
        assertEquals("866 Mbps link", NetInfo.linkLabel(rxMbps = -1, legacyMbps = 866))
    }

    @Test fun emptyWhenNoRateKnown() {
        assertEquals("", NetInfo.linkLabel(rxMbps = -1, legacyMbps = -1))
    }
}
