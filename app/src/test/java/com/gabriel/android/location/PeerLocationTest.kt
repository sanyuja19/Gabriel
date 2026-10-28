package com.gabriel.android.location

import com.gabriel.android.protocol.GabrielPacket
import com.gabriel.android.protocol.MessageType
import org.junit.Assert.*
import org.junit.Test

class PeerLocationTest {

    @Test
    fun testPeerLocationPayloadRoundTrip() {
        val original = PeerLocationPayload(
            latE7 = 286139391, // 28.6139391
            lonE7 = 772090212, // 77.2090212
            accuracyM = 15,
            timestampMs = 1700000000000L,
            batteryPct = 85
        )
        val encoded = original.encode()
        assertEquals(20, encoded.size)

        val decoded = PeerLocationPayload.decode(encoded)
        assertEquals(original.latE7, decoded.latE7)
        assertEquals(original.lonE7, decoded.lonE7)
        assertEquals(original.accuracyM, decoded.accuracyM)
        assertEquals(original.timestampMs, decoded.timestampMs)
        assertEquals(original.batteryPct, decoded.batteryPct)
        assertEquals(28.6139391, decoded.latitude, 0.000001)
        assertEquals(77.2090212, decoded.longitude, 0.000001)
    }

    @Test
    fun testPeerLocationPayloadWithNulls() {
        val original = PeerLocationPayload(
            latE7 = 190760000,
            lonE7 = 728777000,
            accuracyM = null,
            timestampMs = 1700000000000L,
            batteryPct = null
        )
        val encoded = original.encode()
        val decoded = PeerLocationPayload.decode(encoded)
        assertNull(decoded.accuracyM)
        assertNull(decoded.batteryPct)
        assertEquals(original.latE7, decoded.latE7)
        assertEquals(original.lonE7, decoded.lonE7)
    }

    @Test(expected = PeerLocationFormatException::class)
    fun testDecodeRejectsTruncatedPayload() {
        val bytes = ByteArray(15)
        PeerLocationPayload.decode(bytes)
    }

    @Test(expected = PeerLocationFormatException::class)
    fun testDecodeRejectsInvalidVersion() {
        val p = PeerLocationPayload(280000000, 770000000, 10, 1000L, 50)
        val bytes = p.encode()
        bytes[0] = 2 // Invalid version
        PeerLocationPayload.decode(bytes)
    }

    @Test(expected = PeerLocationFormatException::class)
    fun testDecodeRejectsOutOfRangeLatitude() {
        val p = PeerLocationPayload(950_000_000, 770000000, 10, 1000L, 50) // > 90 deg
        val bytes = p.encode()
        PeerLocationPayload.decode(bytes)
    }

    @Test(expected = PeerLocationFormatException::class)
    fun testDecodeRejectsOutOfRangeLongitude() {
        val p = PeerLocationPayload(280000000, 1950_000_000, 10, 1000L, 50) // > 180 deg
        val bytes = p.encode()
        PeerLocationPayload.decode(bytes)
    }

    @Test
    fun testPeerLocationManagerRejectsStalePackets() {
        val staleTime = System.currentTimeMillis() - 90_000_000L // > 24 hours ago
        val payload = PeerLocationPayload(280000000, 770000000, 10, staleTime, 50)
        val packet = GabrielPacket(
            version = 1u,
            type = MessageType.PEER_LOCATION.value,
            senderID = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload.encode(),
            signature = null,
            ttl = 3u
        )
        // Should drop stale packet without adding to peerLocations
        val handled = PeerLocationManager.onPacket(packet)
        assertTrue(handled)
        val locations = PeerLocationManager.peerLocations.value
        assertFalse(locations.containsKey("0102030405060708"))
    }

    @Test
    fun testEmergencyPoiDistanceCalculation() {
        // AIIMS Delhi to Safdarjung Hospital (across Sri Aurobindo Marg) ~400-500 meters
        val aiimsLat = 28.5672
        val aiimsLon = 77.2100
        val safdarjungLat = 28.5702
        val safdarjungLon = 77.2078

        val dist = EmergencyPoiManager.calculateDistanceMeters(aiimsLat, aiimsLon, safdarjungLat, safdarjungLon)
        assertTrue("Distance should be between 300m and 600m, was $dist", dist in 300.0..600.0)
    }

    @Test
    fun testEmergencyPoiBearingCalculation() {
        // Point A to Point B directly North
        val bNorth = EmergencyPoiManager.calculateBearingDegrees(10.0, 70.0, 11.0, 70.0)
        assertEquals(0.0, bNorth, 1.0)
        assertEquals("N", EmergencyPoiManager.bearingToCompassDirection(bNorth))

        // Point A to Point B directly East
        val bEast = EmergencyPoiManager.calculateBearingDegrees(10.0, 70.0, 10.0, 71.0)
        assertEquals(90.0, bEast, 1.0)
        assertEquals("E", EmergencyPoiManager.bearingToCompassDirection(bEast))
    }

    @Test
    fun testEmergencyPoiSearchFilter() {
        val delhiLat = 28.6139
        val delhiLon = 77.2090

        val hospitalResults = EmergencyPoiManager.searchPois(
            query = "AIIMS",
            category = EmergencyCategory.HOSPITAL,
            userLat = delhiLat,
            userLon = delhiLon
        )
        assertTrue(hospitalResults.isNotEmpty())
        assertTrue(hospitalResults.any { it.poi.name.contains("AIIMS New Delhi") })

        // Nearest should be AIIMS New Delhi from Central Delhi
        assertEquals("[UNVERIFIED sample data] AIIMS New Delhi Apex Trauma Centre", hospitalResults.first().poi.name)
        assertNotNull(hospitalResults.first().distanceMeters)
        assertNotNull(hospitalResults.first().bearingDegrees)
        assertNotNull(hospitalResults.first().compassDirection)
    }
}
