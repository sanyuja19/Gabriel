package com.gabriel.android.sos

import com.gabriel.android.protocol.GabrielPacket
import com.gabriel.android.protocol.MessageType
import com.gabriel.android.protocol.SpecialRecipients
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class SosSecurityTest {

    private val now = 50_000_000L

    private fun sampleEventId(seed: Int = 1): ByteArray = ByteArray(16) { (it + seed).toByte() }

    private fun createSamplePayload(
        eventId: ByteArray = sampleEventId(1),
        status: WireStatus = WireStatus.ACTIVE,
        type: EmergencyType = EmergencyType.MEDICAL,
        message: String = "Test emergency note"
    ): SosPayload {
        return SosPayload(
            eventId = eventId,
            type = type,
            status = status,
            latE7 = 286139000,
            lonE7 = 772090000,
            accuracyM = 15,
            fixTimeMs = now - 1000,
            batteryPct = 90,
            message = message
        )
    }

    private fun generateEd25519KeyPair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        val kpGen = Ed25519KeyPairGenerator()
        kpGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = kpGen.generateKeyPair()
        return Pair(kp.private as Ed25519PrivateKeyParameters, kp.public as Ed25519PublicKeyParameters)
    }

    private fun signData(data: ByteArray, privateKey: Ed25519PrivateKeyParameters): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun verifySignature(data: ByteArray, signature: ByteArray, publicKey: Ed25519PublicKeyParameters): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun testEd25519SignatureVerificationOnValidPacket() {
        val (privateKey, publicKey) = generateEd25519KeyPair()
        val payload = createSamplePayload()
        val encodedPayload = payload.encode()

        val packet = GabrielPacket(
            version = 1u,
            type = MessageType.SOS.value,
            senderID = ByteArray(8) { 0x01 },
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = (now / 1000).toULong(),
            payload = encodedPayload,
            signature = null,
            ttl = 3u
        )

        // Sign the canonical binary data
        val dataForSigning = packet.toBinaryDataForSigning()
        assertNotNull(dataForSigning)

        val signature = signData(dataForSigning!!, privateKey)
        assertEquals(64, signature.size)

        val signedPacket = packet.copy(signature = signature)

        // Verify with matching public key
        val isValid = verifySignature(signedPacket.toBinaryDataForSigning()!!, signedPacket.signature!!, publicKey)
        assertTrue("Valid packet signature should verify successfully", isValid)
    }

    @Test
    fun testTamperedPayloadFailsEd25519Verification() {
        val (privateKey, publicKey) = generateEd25519KeyPair()
        val payload = createSamplePayload(message = "Authentic emergency note")
        val packet = GabrielPacket(
            version = 1u,
            type = MessageType.SOS.value,
            senderID = ByteArray(8) { 0x02 },
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = (now / 1000).toULong(),
            payload = payload.encode(),
            signature = null,
            ttl = 3u
        )

        val dataForSigning = packet.toBinaryDataForSigning()!!
        val signature = signData(dataForSigning, privateKey)
        val signedPacket = packet.copy(signature = signature)

        // Attacker tampers with 1 byte of the message payload
        val tamperedPayloadBytes = signedPacket.payload.copyOf()
        tamperedPayloadBytes[tamperedPayloadBytes.size - 1] = (tamperedPayloadBytes[tamperedPayloadBytes.size - 1] + 1).toByte()

        val tamperedPacket = signedPacket.copy(payload = tamperedPayloadBytes)
        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!

        val isValid = verifySignature(tamperedData, signedPacket.signature!!, publicKey)
        assertFalse("Tampered payload MUST fail signature verification", isValid)
    }

    @Test
    fun testTamperedCoordinatesFailsEd25519Verification() {
        val (privateKey, publicKey) = generateEd25519KeyPair()
        val payload = createSamplePayload()
        val packet = GabrielPacket(
            version = 1u,
            type = MessageType.SOS.value,
            senderID = ByteArray(8) { 0x03 },
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = (now / 1000).toULong(),
            payload = payload.encode(),
            signature = null,
            ttl = 3u
        )

        val dataForSigning = packet.toBinaryDataForSigning()!!
        val signature = signData(dataForSigning, privateKey)
        val signedPacket = packet.copy(signature = signature)

        // Attacker alters coordinates in payload (bytes 17..20 = latE7)
        val tamperedPayloadBytes = signedPacket.payload.copyOf()
        tamperedPayloadBytes[18] = (tamperedPayloadBytes[18].toInt() xor 0xFF).toByte()

        val tamperedPacket = signedPacket.copy(payload = tamperedPayloadBytes)
        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!

        val isValid = verifySignature(tamperedData, signedPacket.signature!!, publicKey)
        assertFalse("Tampered coordinates MUST fail signature verification", isValid)
    }

    @Test
    fun testReplayAttackOnResolvedSosCannotReopen() {
        val inbox = SosInbox()
        val origin = "peer_attacker_01"
        val activePayload = createSamplePayload(status = WireStatus.ACTIVE)
        val resolvedPayload = createSamplePayload(status = WireStatus.RESOLVED)

        // 1. Initial active packet arrives
        val changed1 = inbox.apply(activePayload, origin, now, now)
        assertTrue(changed1)
        val entry1 = inbox.get(origin + ":" + activePayload.eventIdHex)
        assertNotNull(entry1)
        assertEquals(WireStatus.ACTIVE, entry1!!.payload.status)

        // 2. Resolved packet arrives
        val changed2 = inbox.apply(resolvedPayload, origin, now + 1000, now + 1000)
        assertTrue(changed2)
        val entry2 = inbox.get(origin + ":" + activePayload.eventIdHex)
        assertEquals(WireStatus.RESOLVED, entry2!!.payload.status)

        // 3. Replay attack: Attacker re-broadcasts the old ACTIVE packet
        val replayedChange = inbox.apply(activePayload, origin, now + 2000, now + 2000)
        assertFalse("Replaying ACTIVE packet on a RESOLVED event must be ignored", replayedChange)

        // Verify status remains RESOLVED
        val entryFinal = inbox.get(origin + ":" + activePayload.eventIdHex)
        assertEquals(WireStatus.RESOLVED, entryFinal!!.payload.status)
    }

    @Test
    fun testReplayAttackOnCancelledSosCannotReopen() {
        val inbox = SosInbox()
        val origin = "peer_attacker_02"
        val activePayload = createSamplePayload(status = WireStatus.ACTIVE)
        val cancelledPayload = createSamplePayload(status = WireStatus.CANCELLED)

        inbox.apply(activePayload, origin, now, now)
        inbox.apply(cancelledPayload, origin, now + 1000, now + 1000)

        // Replay active
        val replayed = inbox.apply(activePayload, origin, now + 5000, now + 5000)
        assertFalse("Replaying ACTIVE packet on a CANCELLED event must be ignored", replayed)

        val entry = inbox.get(origin + ":" + activePayload.eventIdHex)
        assertEquals(WireStatus.CANCELLED, entry!!.payload.status)
    }

    @Test
    fun testStaleTimestampRejectionInInbox() {
        val inbox = SosInbox()
        val origin = "peer_stale"
        val payload = createSamplePayload()

        // Packet timestamp is > 24 hours in the past
        val staleTime = now - 86_400_000L - 1000L
        val applied = inbox.apply(payload, origin, staleTime, now)
        assertFalse("Stale packet older than 24 hours must be rejected", applied)
        assertNull(inbox.get(origin + ":" + payload.eventIdHex))
    }

    @Test
    fun testFutureSkewTimestampRejectionInInbox() {
        val inbox = SosInbox()
        val origin = "peer_future"
        val payload = createSamplePayload()

        // Packet timestamp is > 5 minutes in the future
        val futureTime = now + 300_000L + 1000L
        val applied = inbox.apply(payload, origin, futureTime, now)
        assertFalse("Packet timestamp skewed > 5 min in the future must be rejected", applied)
        assertNull(inbox.get(origin + ":" + payload.eventIdHex))
    }

    @Test
    fun testInboxCapacityHardBoundedAtFiftyEvents() {
        val inbox = SosInbox()
        val origin = "peer_flood"

        for (i in 0 until 60) {
            val p = createSamplePayload(eventId = sampleEventId(i))
            inbox.apply(p, origin, now + i, now + i)
        }

        assertTrue("Inbox capacity must be capped at 50", inbox.entries.size <= 50)
    }

    @Test
    fun testSenderSideForwardOnlyTransitions() {
        val event = SosEvent("event_123", now)
        assertEquals(SosStatus.CREATED, event.status)

        event.markQueued()
        assertEquals(SosStatus.QUEUED, event.status)

        event.markHandedToPeer()
        assertEquals(SosStatus.RELAYED, event.status)

        val ackAccepted = event.applyAck("event_123", "responder_peer", authenticated = true, nowMs = now + 5000)
        assertTrue(ackAccepted)
        assertEquals(SosStatus.ACKNOWLEDGED, event.status)
        assertEquals("responder_peer", event.ackedBy)

        // Once acknowledged, another ack does not change state or overwrite ackedBy
        val secondAck = event.applyAck("event_123", "second_peer", authenticated = true, nowMs = now + 6000)
        assertFalse(secondAck)
        assertEquals("responder_peer", event.ackedBy)

        // Resolve
        event.resolve()
        assertEquals(SosStatus.RESOLVED, event.status)
        assertFalse(event.isActive)

        // Terminal state cannot be cancelled or acknowledged
        event.cancel()
        assertEquals(SosStatus.RESOLVED, event.status)
    }
}
