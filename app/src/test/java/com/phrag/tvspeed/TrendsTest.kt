package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingTest {
    @Test fun streamSpeedBandsStartAtTheirThreshold() {
        assertEquals(Rating.BAD, Rating.of(14.9, 20.0, 20.0, 30.0))
        assertEquals(Rating.GOOD, Rating.of(15.0, 20.0, 20.0, 30.0))
        assertEquals(Rating.GOOD, Rating.of(79.9, 20.0, 20.0, 30.0))
        assertEquals(Rating.AWESOME, Rating.of(80.0, 20.0, 20.0, 30.0))
    }

    @Test fun latencyRiseUnderLoadDragsItDown() {
        assertEquals(Rating.BAD, Rating.of(200.0, 50.0, 20.0, 121.0))
        assertEquals(Rating.GOOD, Rating.of(200.0, 50.0, 20.0, 120.0))
        assertEquals(Rating.GOOD, Rating.of(200.0, 50.0, 20.0, 51.0))
        assertEquals(Rating.AWESOME, Rating.of(200.0, 50.0, 20.0, 50.0))
    }

    @Test fun slowUploadDragsItDown() {
        assertEquals(Rating.BAD, Rating.of(200.0, 0.9, 20.0, 30.0))
        assertEquals(Rating.GOOD, Rating.of(200.0, 9.9, 20.0, 30.0))
        assertEquals(Rating.AWESOME, Rating.of(200.0, 10.0, 20.0, 30.0))
    }

    @Test fun resultUsesSingleStreamAndWorseLoadedDirection() {
        val r = SpeedTest.Result(
            pingMs = 20.0, jitterMs = 2.0, downLoadedMs = 30.0, upLoadedMs = 300.0,
            downMbps = 500.0, streamMbps = 100.0, upMbps = 50.0, downBytes = 0L, upBytes = 0L,
            server = SpeedTest.Server("Op", "Rome", "Italy", ""),
        )
        assertEquals(Rating.BAD, Rating.of(r))
        assertEquals(Rating.AWESOME, Rating.of(r.copy(upLoadedMs = 40.0)))
        // Parallel speed alone doesn't make it awesome.
        assertEquals(Rating.GOOD, Rating.of(r.copy(upLoadedMs = 40.0, streamMbps = 40.0)))
    }

    @Test fun olderRunsWithoutSingleStreamHaveNoRating() {
        assertNull(Rating.of(History.Entry(0, 100.0, 10.0, 20.0, 30.0, false, "Ethernet", "Rome, Italy")))
    }

    @Test fun badgeHasSymbolAndWord() {
        assertEquals("✕ BAD", Rating.BAD.badge)
        assertEquals("✓ GOOD", Rating.GOOD.badge)
        assertEquals("★ AWESOME", Rating.AWESOME.badge)
    }
}

class TrendsTest {
    private fun run(
        stream: Double, tag: String = "WireGuard", vpn: Boolean = true, server: String = "GARR",
        loaded: Double = 40.0, up: Double = 20.0, network: String = "Ethernet",
    ) = History.Entry(0, stream * 2, up, 20.0, loaded, vpn, network, "Rome, Italy", server, tag, stream)

    @Test fun noTrendUntilEnoughComparableRuns() {
        val earlier = listOf(run(40.0), run(50.0), run(60.0, tag = "IKEv2"), run(60.0, server = "CESNET"),
            run(60.0, vpn = false), run(0.0))   // the last stands for a run from an older version
        assertNull(Trends.trend(run(30.0), earlier))
        assertEquals("Usual speed after 3 runs this way (2 so far)", Trends.line(run(30.0), earlier))
        assertNotNull(Trends.trend(run(30.0), earlier + run(45.0)))
    }

    @Test fun usualIsTheMedianOfEarlierRuns() {
        val t = Trends.trend(run(30.0), listOf(run(40.0), run(500.0), run(50.0)))!!
        assertEquals(3, t.runs)
        assertEquals(50.0, t.usualStreamMbps, 0.001)
        assertEquals(-0.4, t.streamChange, 0.001)
        assertTrue(t.slower)
    }

    @Test fun onlyTheNewestEarlierRunsCount() {
        val earlier = List(Trends.RECENT) { run(50.0) } + List(5) { run(10.0) }
        assertEquals(50.0, Trends.trend(run(50.0), earlier)!!.usualStreamMbps, 0.001)
    }

    @Test fun slowerNeedsTheFullMargin() {
        val earlier = List(3) { run(100.0) }
        assertFalse(Trends.trend(run(75.1), earlier)!!.slower)
        assertTrue(Trends.trend(run(75.0), earlier)!!.slower)
    }

    @Test fun laggierNeedsBothAbsoluteAndRelativeRise() {
        val earlier = List(3) { run(100.0, loaded = 50.0) }
        assertTrue(Trends.trend(run(100.0, loaded = 150.0), earlier)!!.laggier)
        assertFalse(Trends.trend(run(100.0, loaded = 149.0), earlier)!!.laggier)
        val slowUsual = List(3) { run(100.0, loaded = 300.0) }
        assertFalse(Trends.trend(run(100.0, loaded = 420.0), slowUsual)!!.laggier)   // +120 ms but only 1.4×
    }

    @Test fun headingComparesNewerHalfWithOlderHalf() {
        assertNull(Trends.heading(listOf(10.0, 10.0, 10.0, 50.0, 50.0)))
        assertEquals(Trends.Heading.DOWN, Trends.heading(listOf(10.0, 12.0, 11.0, 50.0, 50.0, 55.0)))
        assertEquals(Trends.Heading.UP, Trends.heading(listOf(50.0, 50.0, 55.0, 10.0, 12.0, 11.0)))
        assertEquals(Trends.Heading.STEADY, Trends.heading(listOf(50.0, 48.0, 52.0, 50.0, 51.0, 49.0)))
        // Odd count: the middle run belongs to neither half.
        assertEquals(Trends.Heading.STEADY, Trends.heading(listOf(50.0, 50.0, 50.0, 1.0, 50.0, 50.0, 50.0)))
    }

    @Test fun lineSaysHowTheRunCompares() {
        val earlier = List(5) { run(50.0) }
        assertEquals("▼ 40% under usual 50 Mbps · steady", Trends.line(run(30.0), earlier))
        assertEquals("▲ 20% over usual 50 Mbps · steady", Trends.line(run(60.0), earlier))
        assertEquals("≈ usual 50 Mbps · steady", Trends.line(run(52.0), earlier))
        assertEquals("≈ usual 50 Mbps", Trends.line(run(52.0), earlier.take(3)))
        assertEquals("≈ usual 50 Mbps · lag +150 ms · steady", Trends.line(run(50.0, loaded = 190.0), earlier))
    }
}

class SuggestionTest {
    private fun run(stream: Double, tag: String = "WireGuard", vpn: Boolean = true, server: String = "GARR",
                    loaded: Double = 40.0, network: String = "Ethernet") =
        History.Entry(0, stream * 2, 20.0, 20.0, loaded, vpn, network, "Rome, Italy", server, tag, stream)

    private val usual = List(3) { run(50.0) }

    @Test fun nothingWhenAboutUsual() {
        assertNull(Trends.suggestion(run(45.0), usual))
    }

    @Test fun nothingWithoutEnoughHistory() {
        assertNull(Trends.suggestion(run(10.0), usual.take(2)))
    }

    @Test fun nothingWithoutVpn() {
        assertNull(Trends.suggestion(run(10.0, vpn = false), List(3) { run(50.0, vpn = false) }))
    }

    @Test fun nothingWhenStillAwesome() {
        assertNull(Trends.suggestion(run(100.0), List(3) { run(200.0) }))
    }

    @Test fun genericWhenNoOtherTagIsKnown() {
        val s = Trends.suggestion(run(30.0), usual)!!
        assertTrue(s, s.startsWith("Slower than usual on this VPN. Try switching VPN server or protocol, and tag"))
    }

    @Test fun lagAloneAlsoSuggests() {
        val s = Trends.suggestion(run(50.0, loaded = 200.0), usual)!!
        assertTrue(s, s.startsWith("Lag under load is higher than usual"))
    }

    @Test fun namesTheBestOtherTag() {
        val earlier = usual + listOf(run(90.0, "IKEv2"), run(80.0, "IKEv2"), run(70.0, "OpenVPN UDP"), run(60.0, "OpenVPN UDP"),
            run(300.0, "Solo"),   // one run isn't enough to name it
            run(400.0, "Wi-Fi tag", network = "Wi-Fi"), run(400.0, "Wi-Fi tag", network = "Wi-Fi"))   // another line
        val s = Trends.suggestion(run(30.0), earlier)!!
        assertTrue(s, s.contains("IKEv2 has done best here (usually 85 Mbps on one stream)"))
    }

    @Test fun otherTagMustBeClearlyBetter() {
        // Another tag that is about as good as this one usually is isn't worth a switch.
        val earlier = usual + listOf(run(54.0, "IKEv2"), run(54.0, "IKEv2"))
        assertFalse(Trends.suggestion(run(30.0), earlier)!!.contains("IKEv2"))
        val better = usual + listOf(run(56.0, "IKEv2"), run(56.0, "IKEv2"))
        assertTrue(Trends.suggestion(run(30.0), better)!!.contains("IKEv2"))
    }

    @Test fun untaggedRunCanStillPointToATag() {
        val earlier = List(3) { run(50.0, tag = "") } + listOf(run(90.0, "IKEv2"), run(90.0, "IKEv2"))
        assertTrue(Trends.suggestion(run(30.0, tag = ""), earlier)!!.contains("IKEv2 has done best"))
    }

    @Test fun verdictPutsTheSuggestionSecond() {
        val r = SpeedTest.Result(20.0, 2.0, 30.0, 30.0, 50.0, 50.0, 10.0, 0L, 0L, SpeedTest.Server("Op", "Rome", "Italy", "203.0.113.7"))
        val v = MainActivity.verdictFor(r, NetInfo("Ethernet", true, ""), "Switch.").lines()
        assertEquals("Switch.", v[1])
        assertTrue(v.none { it.contains("Try another VPN server") })
        assertTrue(MainActivity.verdictFor(r, NetInfo("Ethernet", true, "")).contains("Try another VPN server"))
    }
}
