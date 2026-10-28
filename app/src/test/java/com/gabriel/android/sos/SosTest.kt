package com.gabriel.android.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SosTest {
    private fun id(seed: Int) = ByteArray(16) { (it + seed).toByte() }

    private fun sos(
        seed: Int = 1,
        status: WireStatus = WireStatus.ACTIVE,
        loc: Boolean = true,
        msg: String = "help"
    ) = SosPayload(
        id(seed),
        EmergencyType.LOST,
        status,
        if (loc) 211_500_000 else null,
        if (loc) 790_800_000 else null,
        if (loc) 12 else null,
        if (loc) 5000L else null,
        77,
        msg
    )

    private val now = 10_000_000L

    @Test
    fun testPayloadRoundTripWithLocation() {
        val p = SosPayload.decode(sos(msg = "broken leg, near river").encode())
        assertTrue(p.hasLocation)
        assertEquals(211_500_000, p.latE7)
        assertEquals(790_800_000, p.lonE7)
        assertEquals(12, p.accuracyM)
        assertEquals(5000L, p.fixTimeMs)
        assertEquals(77, p.batteryPct)
        assertEquals("broken leg, near river", p.message)
        assertEquals(EmergencyType.LOST, p.type)
        assertEquals(WireStatus.ACTIVE, p.status)
        assertEquals(sos().eventIdHex, p.eventIdHex)
    }

    @Test
    fun testPayloadWithoutLocationUnknownBatteryUnknownAccuracy() {
        val p = SosPayload.decode(
            SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, null, null, null, null, null, "").encode()
        )
        assertFalse(p.hasLocation)
        assertNull(p.batteryPct)
        assertNull(p.latitude)
        val q = SosPayload.decode(
            SosPayload(id(2), EmergencyType.OTHER, WireStatus.ACTIVE, 1, 1, null, 1L, 50, "").encode()
        )
        assertNull(q.accuracyM)
    }

    @Test
    fun testUtf8MessageAnd140ByteLimit() {
        assertEquals("मदद चाहिए", SosPayload.decode(sos(msg = "मदद चाहिए").encode()).message)
        try {
            sos(msg = "x".repeat(141))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
        sos(msg = "x".repeat(140))
    }

    @Test
    fun testConstructorRejectsBadCoordinatesBatteryPartialLocation() {
        try {
            SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, 910_000_000, 0, 1, 1L, 1, "")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}

        try {
            SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, 0, 1_900_000_000, 1, 1L, 1, "")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}

        try {
            SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, 0, 0, 1, 1L, 101, "")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}

        try {
            SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, 0, null, 1, 1L, 1, "")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun testDecodeRejectsMalformedData() {
        val e = sos().encode()
        for (n in 0 until e.size) {
            try {
                SosPayload.decode(e.copyOf(n))
                throw AssertionError("Expected SosFormatException for size $n")
            } catch (_: SosFormatException) {}
        }

        try {
            SosPayload.decode(e + byteArrayOf(0))
            throw AssertionError("Expected SosFormatException for trailing byte")
        } catch (_: SosFormatException) {}

        try {
            SosPayload.decode(e.clone().also { it[0] = 2 })
            throw AssertionError("Expected SosFormatException for bad version")
        } catch (_: SosFormatException) {}

        try {
            SosPayload.decode(e.clone().also { it[17] = 99 })
            throw AssertionError("Expected SosFormatException for bad type")
        } catch (_: SosFormatException) {}

        try {
            SosPayload.decode(e.clone().also { it[18] = 99 })
            throw AssertionError("Expected SosFormatException for bad status")
        } catch (_: SosFormatException) {}

        try {
            SosPayload.decode(e.clone().also { it[19] = 0x81.toByte() })
            throw AssertionError("Expected SosFormatException for bad flags")
        } catch (_: SosFormatException) {}

        try {
            SosPayload.decode(e.clone().also { it[20] = 0x7f; it[21] = 0xff.toByte() })
            throw AssertionError("Expected SosFormatException for bad lat")
        } catch (_: SosFormatException) {}

        try {
            SosPayload.decode(e.clone().also { it[38] = 150.toByte() })
            throw AssertionError("Expected SosFormatException for bad battery")
        } catch (_: SosFormatException) {}
    }

    @Test
    fun testFuzzingRandomInputs() {
        val r = Random(7)
        repeat(20000) {
            val g = ByteArray(r.nextInt(80)).also { r.nextBytes(it) }
            if (g.isNotEmpty() && r.nextBoolean()) g[0] = 1
            try {
                SosPayload.decode(g)
            } catch (_: SosFormatException) {
            }
        }
    }

    @Test
    fun testAckPayloadRoundTripAndRejection() {
        assertTrue(SosAckPayload.decode(SosAckPayload.encode(id(3))).contentEquals(id(3)))
        try {
            SosAckPayload.decode(ByteArray(5))
            throw AssertionError("Expected SosFormatException for short ack")
        } catch (_: SosFormatException) {}

        try {
            SosAckPayload.decode(byteArrayOf(2) + id(3))
            throw AssertionError("Expected SosFormatException for bad version")
        } catch (_: SosFormatException) {}
    }

    @Test
    fun testInboxStoresNewEventNewerUpdateWinsOlderIgnored() {
        val i = SosInbox()
        assertTrue(i.apply(sos(), "peerA", now - 1000, now))
        assertFalse(i.apply(sos(), "peerA", now - 2000, now))
        assertTrue(i.apply(sos(msg = "update"), "peerA", now - 500, now))
        assertEquals("update", i.entries.single().payload.message)
    }

    @Test
    fun testInboxResolvedStaysResolvedEvenIfStaleActiveReplayed() {
        val i = SosInbox()
        i.apply(sos(), "peerA", now - 3000, now)
        i.apply(sos(status = WireStatus.RESOLVED), "peerA", now - 2000, now)
        assertFalse(i.apply(sos(), "peerA", now - 1000, now))
        assertEquals(WireStatus.RESOLVED, i.entries.single().payload.status)
    }

    @Test
    fun testInboxSameEventIdFromDifferentAuthenticatedOriginIsSeparateEvent() {
        val i = SosInbox()
        i.apply(sos(msg = "real"), "peerA", now - 1000, now)
        i.apply(sos(msg = "fake"), "peerM", now - 500, now)
        assertEquals(2, i.entries.size)
        assertEquals("real", i.entries.first { it.originPeerID == "peerA" }.payload.message)
    }

    @Test
    fun testInboxRejectsStaleAndFuturePackets() {
        val i = SosInbox()
        assertFalse(i.apply(sos(), "p", now - 25 * 3600_000L, now))
        assertFalse(i.apply(sos(), "p", now + 10 * 60_000L, now))
        assertTrue(i.entries.isEmpty())
    }

    @Test
    fun testInboxBoundedAtCapacity() {
        val i = SosInbox(capacity = 5)
        for (k in 0 until 20) {
            i.apply(sos(seed = k), "p", now - 100, now + k)
        }
        assertEquals(5, i.entries.size)
    }

    @Test
    fun testSenderStateForwardOnlyTruthfulAckRules() {
        val e = SosEvent("e1", 0)
        e.markQueued()
        assertEquals(SosStatus.QUEUED, e.status)
        e.markHandedToPeer()
        assertEquals(SosStatus.RELAYED, e.status)
        assertFalse(e.applyAck("e1", "f", false, 1))
        assertFalse(e.applyAck("zz", "f", true, 1))
        assertEquals(SosStatus.RELAYED, e.status)
        assertTrue(e.applyAck("e1", "f", true, 1))
        assertEquals(SosStatus.ACKNOWLEDGED, e.status)
        e.markQueued()
        e.markHandedToPeer()
        assertEquals(SosStatus.ACKNOWLEDGED, e.status)
        e.cancel()
        assertEquals(SosStatus.CANCELLED, e.status)
        assertFalse(e.applyAck("e1", "f", true, 2))
    }
}
