package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun head(vararg names: String) = names.map { n ->
        SpeedTest.Backend(n, "", "", "https://example.invalid/$n", "https://example.invalid/$n") to java.net.InetAddress.getLoopbackAddress()
    }

    private fun order(head: List<Pair<SpeedTest.Backend, java.net.InetAddress>>, times: Map<String, Double>) =
        SpeedTest.reorder(head, times).map { it.first.name }

    @Test fun autoKeepsNearestWhenHttpsTimesAreClose() {
        // Madrid answers 20 ms faster on this run, which is script noise, not distance.
        val h = head("CESNET", "CZ.NIC", "RedIRIS")
        assertEquals(listOf("CESNET", "RedIRIS", "CZ.NIC"), order(h, mapOf("CESNET" to 70.0, "CZ.NIC" to 90.0, "RedIRIS" to 50.0)))
    }

    @Test fun autoDropsNearestWhenItsHttpsTimeIsFarWorse() {
        // A fake-near edge: fast handshake, the real server far away.
        val h = head("Edge", "CESNET", "CZ.NIC")
        assertEquals(listOf("CESNET", "CZ.NIC", "Edge"), order(h, mapOf("Edge" to 400.0, "CESNET" to 60.0, "CZ.NIC" to 80.0)))
    }

    @Test fun autoKeepsNearestWhenItDidNotAnswer() {
        val h = head("CESNET", "CZ.NIC")
        assertEquals(listOf("CZ.NIC", "CESNET"), order(h, mapOf("CZ.NIC" to 80.0)))
    }

    private fun servers(vararg spec: Triple<String, String, String>) = spec.map { (n, city, country) ->
        SpeedTest.Backend(n, city, country, "https://example.invalid/$n", "https://example.invalid/$n") to java.net.InetAddress.getLoopbackAddress()
    }

    private val europe = servers(
        Triple("CESNET", "Prague", "Czechia"),
        Triple("Freifunk", "Munich", "Germany"),
        Triple("Berlin1", "Berlin", "Germany"),
        Triple("GARR", "Rome", "Italy"),
    )
    private val ms = mapOf("CESNET" to 12.0, "Freifunk" to 20.0, "Berlin1" to 25.0, "GARR" to 35.0)

    private fun prefer(place: SpeedTest.Place?, connect: Map<String, Double> = ms) =
        SpeedTest.preferPlace(europe, connect, place).map { it.first.name }

    @Test fun autoPrefersTheCityThenTheCountry() {
        val berlin = SpeedTest.Place("Berlin", "Germany", chosen = true)
        assertEquals(listOf("Berlin1", "Freifunk", "CESNET", "GARR"), prefer(berlin))
    }

    @Test fun autoPrefersTheCountryWhenNoServerIsInTheCity() {
        val hamburg = SpeedTest.Place("Hamburg", "Germany", chosen = true)
        assertEquals(listOf("Freifunk", "Berlin1", "CESNET", "GARR"), prefer(hamburg))
    }

    @Test fun autoKeepsNearestWhenNoServerIsInTheCountry() {
        assertEquals(listOf("CESNET", "Freifunk", "Berlin1", "GARR"), prefer(SpeedTest.Place(null, "France", chosen = false)))
        assertEquals(listOf("CESNET", "Freifunk", "Berlin1", "GARR"), prefer(null))
    }

    @Test fun guessedCountryGivesWayToANearerServer() {
        // Time zone says Germany, but the VPN exit is in the US: German servers are 100 ms further.
        val viaUs = mapOf("CESNET" to 110.0, "Freifunk" to 105.0, "Berlin1" to 108.0, "GARR" to 120.0, "LAX" to 5.0)
        val list = europe + servers(Triple("LAX", "Los Angeles", "USA"))
        val germany = SpeedTest.Place(null, "Germany", chosen = false)
        val byConnect = list.sortedBy { viaUs[it.first.name] }
        assertEquals("LAX", SpeedTest.preferPlace(byConnect, viaUs, germany).first().first.name)
        // Picked by the user: Germany it is.
        assertEquals("Freifunk", SpeedTest.preferPlace(byConnect, viaUs, germany.copy(chosen = true)).first().first.name)
    }

    @Test fun guessedCountryWinsWhenAboutAsNear() {
        assertEquals(listOf("Freifunk", "Berlin1", "CESNET", "GARR"), prefer(SpeedTest.Place(null, "Germany", chosen = false)))
    }

    @Test fun everyServerCountryHasARegionCode() {
        // A server added in a new country needs its code, or the time zone can't point to it.
        for (b in SpeedTest.SERVERS) assertTrue(b.country, b.country in SpeedTest.COUNTRY_CODES.values)
        assertEquals("Germany", SpeedTest.countryFor("de"))
        assertEquals("Czechia", SpeedTest.countryFor("CZ"))
        assertEquals("USA", SpeedTest.countryFor("US"))
        assertEquals(null, SpeedTest.countryFor("FR"))
        assertEquals(null, SpeedTest.countryFor(null))
    }
}
