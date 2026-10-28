package com.gabriel.android.sos

enum class SosStatus { CREATED, QUEUED, RELAYED, ACKNOWLEDGED, CANCELLED, RESOLVED }

/**
 * Sender-side truthful lifecycle. Forward-only: CREATED -> QUEUED -> RELAYED -> ACKNOWLEDGED.
 * RELAYED = handed to the radio for at least one connected device; NOT confirmed received, NOT "rescuers notified".
 * ACKNOWLEDGED = a verified acknowledgement from another device arrived; it means a person on that device
 * pressed Acknowledge, not that help is coming.
 */
class SosEvent(val eventId: String, val createdAtMs: Long) {
    var status = SosStatus.CREATED; private set
    var handedToPeers = 0; private set
    var ackedBy: String? = null; private set
    var ackedAtMs: Long? = null; private set
    val isActive get() = status != SosStatus.CANCELLED && status != SosStatus.RESOLVED

    fun markQueued() { if (status == SosStatus.CREATED) status = SosStatus.QUEUED }
    fun markHandedToPeer() {
        if (!isActive) return
        handedToPeers++
        if (status == SosStatus.CREATED || status == SosStatus.QUEUED) status = SosStatus.RELAYED
    }
    fun applyAck(ackEventId: String, responder: String, authenticated: Boolean, nowMs: Long): Boolean {
        if (!isActive || !authenticated || ackEventId != eventId || status == SosStatus.ACKNOWLEDGED) return false
        status = SosStatus.ACKNOWLEDGED; ackedBy = responder; ackedAtMs = nowMs; return true
    }
    fun cancel() { if (isActive) status = SosStatus.CANCELLED }
    fun resolve() { if (isActive) status = SosStatus.RESOLVED }
}

class ReceivedSos(
    val key: String, val originPeerID: String,
    var payload: SosPayload, var packetTsMs: Long, var receivedAtMs: Long,
) { var acknowledgedByMe = false }

/**
 * Receiver-side store. Key = authenticated origin peer + eventId, so one sender cannot overwrite another's event.
 * Older packets never overwrite newer ones (blocks replay of a stale ACTIVE after RESOLVED). Bounded.
 */
class SosInbox(private val capacity: Int = 50, private val maxAgeMs: Long = 24 * 3600_000L, private val maxSkewMs: Long = 5 * 60_000L) {
    private val map = LinkedHashMap<String, ReceivedSos>()
    val entries: List<ReceivedSos> get() = map.values.sortedByDescending { it.receivedAtMs }

    /** @return true if stored state changed. */
    fun apply(p: SosPayload, originPeerID: String, packetTsMs: Long, nowMs: Long): Boolean {
        if (nowMs - packetTsMs > maxAgeMs || packetTsMs - nowMs > maxSkewMs) return false
        val key = "$originPeerID:${p.eventIdHex}"
        val cur = map[key]
        if (cur != null) {
            if (packetTsMs <= cur.packetTsMs) return false
            if (cur.payload.status != WireStatus.ACTIVE && p.status == WireStatus.ACTIVE) return false // ended events stay ended
            cur.payload = p; cur.packetTsMs = packetTsMs; cur.receivedAtMs = nowMs
            return true
        }
        if (map.size >= capacity) map.remove(map.entries.minByOrNull { it.value.receivedAtMs }!!.key)
        map[key] = ReceivedSos(key, originPeerID, p, packetTsMs, nowMs)
        return true
    }
    fun get(key: String) = map[key]
}
