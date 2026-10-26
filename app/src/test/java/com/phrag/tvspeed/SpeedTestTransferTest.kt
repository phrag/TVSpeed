package com.phrag.tvspeed

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Drives [SpeedTest.transfer] on a fake clock. Only the test's own thread (the sampling loop)
 * moves virtual time; worker and probe threads sleep for real so they can't skew it. Each
 * sample the loop sleeps through credits bytes to the transfer, so the expected rate is exact.
 */
class SpeedTestTransferTest {
    private class FakeClock(private val onSample: (Long) -> Unit) : SpeedTest.Clock {
        private val now = AtomicLong()
        override fun elapsedMs() = now.get()
        override fun sleep(ms: Long) {
            if (Thread.currentThread().name.startsWith("tvspeed-")) {
                Thread.sleep(1)
                return
            }
            onSample(now.addAndGet(ms))
        }
    }

    private val addr = InetAddress.getLoopbackAddress()

    /** Runs a transfer where [bytesAt] says how many bytes arrive in the sample ending at time t. */
    private fun measure(bytesAt: (Long) -> Long): SpeedTest.Transfer {
        val streams = arrayOfNulls<SpeedTest.Streams>(1)
        val ready = CountDownLatch(1)
        val clock = FakeClock { t ->
            ready.await(5, TimeUnit.SECONDS)
            streams[0]!!.bytes.addAndGet(bytesAt(t))
        }
        val test = SpeedTest(object : SpeedTest.Listener {}, clock = clock)
        return test.transfer(SpeedTest.Phase.DOWNLOAD, addr, 1) { s ->
            streams[0] = s
            ready.countDown()
        }
    }

    @Test fun steadyRateIsReportedExactly() {
        // 125_000 bytes per 200 ms sample = 5 Mbit/s.
        assertEquals(5.0, measure { 125_000L }.mbps, 0.001)
    }

    @Test fun slowStartIsLeftOutOfTheAverage() {
        // 0.5 Mbit/s for the 2 s warm-up, then 5 Mbit/s.
        val t = measure { if (it <= 2_000) 12_500L else 125_000L }
        assertEquals(5.0, t.mbps, 0.001)
    }

    @Test fun noDataIsAnError() {
        try {
            measure { 0L }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.startsWith("Download failed: no data transferred"))
        }
    }
}
