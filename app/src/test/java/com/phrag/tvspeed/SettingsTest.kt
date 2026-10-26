package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun defaultsMatchTheBuiltInTest() {
        val c = Settings.config(Settings.lengthOf(null),
            Settings.pick(0, Settings.DOWN_CHOICES, SpeedTest.DOWN_STREAMS),
            Settings.pick(0, Settings.UP_CHOICES, SpeedTest.UP_STREAMS))
        assertEquals(SpeedTest.Config(), c)
    }

    @Test fun unknownStoredValuesFallBackToDefaults() {
        assertEquals(Settings.Length.STANDARD, Settings.lengthOf("TURBO"))
        assertEquals(SpeedTest.DOWN_STREAMS, Settings.pick(7, Settings.DOWN_CHOICES, SpeedTest.DOWN_STREAMS))
        assertEquals(8, Settings.pick(8, Settings.DOWN_CHOICES, SpeedTest.DOWN_STREAMS))
    }

    @Test fun lengthsSetBothPhases() {
        val c = Settings.config(Settings.Length.QUICK, 2, 1)
        assertEquals(6_000L, c.transferMs)
        assertEquals(5_000L, c.streamMs)
        assertEquals(2, c.downStreams)
        assertEquals(1, c.upStreams)
    }

    @Test fun everyLengthOutlastsTheWarmUp() {
        // Two seconds of each phase are slow start and left out of the figure.
        for (l in Settings.Length.values()) assertTrue(l.name, l.streamMs > 2_000 && l.transferMs > 2_000)
    }

    @Test fun defaultsAreAmongTheChoices() {
        assertTrue(SpeedTest.DOWN_STREAMS in Settings.DOWN_CHOICES)
        assertTrue(SpeedTest.UP_STREAMS in Settings.UP_CHOICES)
    }
}
