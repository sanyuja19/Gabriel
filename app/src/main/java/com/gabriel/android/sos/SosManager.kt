package com.gabriel.android.sos

import android.util.Log
import com.gabriel.android.protocol.GabrielPacket
import com.gabriel.android.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

interface SosSender {
    /** true = handed to the BLE stack for at least one connected device. NOT proof of reception. */
    fun sendSos(payload: ByteArray): Boolean
    fun sendAck(toPeerID: String, eventId: ByteArray): Boolean
}

class SosFix(val latE7: Int, val lonE7: Int, val accuracyM: Int?, val fixTimeMs: Long)

data class OwnSosView(
    val eventId: String, val status: SosStatus, val transmissions: Int,
    val ackedBy: String?, val ackedAtMs: Long?, val payload: SosPayload,
)

data class ReceivedSosView(
    val key: String, val originPeerID: String, val payload: SosPayload,
    val receivedAtMs: Long, val acknowledgedByMe: Boolean,
)

/**
 * Glue between the mesh and the pure SOS logic. SOS is a PUBLIC signed beacon: anything in range can read it.
 * Packets reach onPacket() only after SecurityManager verified the Ed25519 signature against the sender's
 * announced key (SOS/SOS_ACK are in its mandatory-signature list).
 */
object SosManager {
    private const val TAG = "SosManager"
    private const val REBROADCAST_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val inbox = SosInbox()
    private var myPeerID = ""
    private var sender: SosSender? = null
    private var own: SosEvent? = null
    private var ownPayload: SosPayload? = null
    private var job: Job? = null

    private val _received = MutableStateFlow<List<ReceivedSosView>>(emptyList())
    val received: StateFlow<List<ReceivedSosView>> = _received
    private val _own = MutableStateFlow<OwnSosView?>(null)
    val ownSos: StateFlow<OwnSosView?> = _own

    fun attach(peerID: String, s: SosSender) { synchronized(lock) { myPeerID = peerID; sender = s } }

    fun startSos(type: EmergencyType, message: String, fix: SosFix?, batteryPct: Int?): Boolean {
        synchronized(lock) {
            if (own?.isActive == true) return false
            val id = ByteArray(SosPayload.EVENT_ID_LEN).also { SecureRandom().nextBytes(it) }
            val p = SosPayload(id, type, WireStatus.ACTIVE, fix?.latE7, fix?.lonE7, fix?.accuracyM, fix?.fixTimeMs, batteryPct, message)
            val ev = SosEvent(p.eventIdHex, System.currentTimeMillis()).also { it.markQueued() }
            own = ev; ownPayload = p
            job?.cancel()
            job = scope.launch {
                while (isActive && ev.isActive) {
                    val ok = try { sender?.sendSos(p.encode()) == true } catch (e: Exception) { false }
                    synchronized(lock) { if (ok) ev.markHandedToPeer(); publishOwn() }
                    delay(REBROADCAST_MS)
                }
            }
            publishOwn()
            return true
        }
    }

    fun endSos(resolved: Boolean) {
        val (ev, p) = synchronized(lock) { Pair(own ?: return, ownPayload ?: return) }
        job?.cancel()
        synchronized(lock) { if (resolved) ev.resolve() else ev.cancel(); publishOwn() }
        val ended = p.withStatus(if (resolved) WireStatus.RESOLVED else WireStatus.CANCELLED)
        scope.launch { repeat(3) { try { sender?.sendSos(ended.encode()) } catch (_: Exception) {}; delay(2_000) } }
    }

    /** Called from the packet processor. Return false only for malformed payloads (so they are not relayed). */
    fun onPacket(packet: GabrielPacket): Boolean {
        val origin = packet.senderID.joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        return try {
            when (packet.type) {
                MessageType.SOS.value -> {
                    val p = SosPayload.decode(packet.payload)
                    if (origin == myPeerID) return true
                    val changed = synchronized(lock) { inbox.apply(p, origin, packet.timestamp.toLong(), now) }
                    if (changed) publishReceived()
                    true
                }
                MessageType.SOS_ACK.value -> {
                    val eventId = SosAckPayload.decode(packet.payload)
                    val toMe = packet.recipientID?.joinToString("") { "%02x".format(it) } == myPeerID
                    if (toMe) synchronized(lock) {
                        val ev = own
                        if (ev != null) {
                            val hex = eventId.joinToString("") { "%02x".format(it) }
                            // authenticated = true: signature already verified by SecurityManager.
                            if (ev.applyAck(hex, origin, authenticated = true, nowMs = now)) publishOwn()
                        }
                    }
                    true
                }
                else -> false
            }
        } catch (e: SosFormatException) {
            Log.w(TAG, "Dropping malformed SOS packet: ${e.message}")
            false
        }
    }

    fun acknowledge(key: String): Boolean {
        val r = synchronized(lock) { inbox.get(key) } ?: return false
        val ok = sender?.sendAck(r.originPeerID, r.payload.eventId) == true
        if (ok) synchronized(lock) { r.acknowledgedByMe = true }
        publishReceived()
        return ok
    }

    private fun publishOwn() {
        val ev = own; val p = ownPayload
        _own.value = if (ev == null || p == null) null else OwnSosView(ev.eventId, ev.status, ev.handedToPeers, ev.ackedBy, ev.ackedAtMs, p)
    }
    private fun publishReceived() {
        _received.value = synchronized(lock) {
            inbox.entries.map { ReceivedSosView(it.key, it.originPeerID, it.payload, it.receivedAtMs, it.acknowledgedByMe) }
        }
    }
}
