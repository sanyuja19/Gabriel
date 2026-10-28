package com.gabriel.android.sos

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** Pure Kotlin (no Android deps) so it can be unit-tested on the JVM. */
enum class EmergencyType(val code: Int) {
    LOST(0), MEDICAL(1), INJURY(2), DANGER(3), OTHER(4);
    companion object { fun from(c: Int) = values().firstOrNull { it.code == c } }
}

enum class WireStatus(val code: Int) {
    ACTIVE(0), CANCELLED(1), RESOLVED(2);
    companion object { fun from(c: Int) = values().firstOrNull { it.code == c } }
}

class SosFormatException(msg: String) : Exception(msg)

/**
 * SOS payload v1 (carried inside a signed GabrielPacket of type SOS).
 * PUBLIC BEACON: readable by every device in range. Never put secrets in [message].
 *  0 ver u8 | 1..16 eventId | 17 type u8 | 18 status u8 | 19 flags u8 (bit0 = has location)
 *  [if location: lat i32 (deg*1e7), lon i32, accuracyM u16 (0xFFFF=unknown), fixTimeMs u64]
 *  battery u8 (255 = unknown) | msgLen u8 | msg UTF-8 (<=140 bytes)
 */
class SosPayload(
    val eventId: ByteArray,
    val type: EmergencyType,
    val status: WireStatus,
    val latE7: Int?, val lonE7: Int?, val accuracyM: Int?, val fixTimeMs: Long?,
    val batteryPct: Int?,
    val message: String,
) {
    init {
        require(eventId.size == EVENT_ID_LEN)
        require((latE7 == null) == (lonE7 == null) && (latE7 == null) == (fixTimeMs == null)) { "location fields must be all-or-nothing" }
        latE7?.let { require(it in -900_000_000..900_000_000) { "lat out of range" } }
        lonE7?.let { require(it in -1_800_000_000..1_800_000_000) { "lon out of range" } }
        accuracyM?.let { require(it in 0..0xFFFE) }
        batteryPct?.let { require(it in 0..100) }
        require(message.toByteArray(Charsets.UTF_8).size <= MAX_MSG) { "message too long" }
    }
    val hasLocation get() = latE7 != null
    val eventIdHex get() = eventId.joinToString("") { "%02x".format(it) }
    val latitude get() = latE7?.let { it / 1e7 }
    val longitude get() = lonE7?.let { it / 1e7 }

    fun withStatus(s: WireStatus) = SosPayload(eventId, type, s, latE7, lonE7, accuracyM, fixTimeMs, batteryPct, message)

    fun encode(): ByteArray {
        val msg = message.toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(1 + EVENT_ID_LEN + 3 + (if (hasLocation) 18 else 0) + 2 + msg.size)
        b.put(VERSION.toByte()).put(eventId).put(type.code.toByte()).put(status.code.toByte())
        b.put((if (hasLocation) 1 else 0).toByte())
        if (hasLocation) {
            b.putInt(latE7!!).putInt(lonE7!!).putShort((accuracyM ?: 0xFFFF).toShort()).putLong(fixTimeMs!!)
        }
        b.put((batteryPct ?: 255).toByte()).put(msg.size.toByte()).put(msg)
        return b.array()
    }

    companion object {
        const val VERSION = 1
        const val EVENT_ID_LEN = 16
        const val MAX_MSG = 140

        /** Strict: any malformed, out-of-range, or trailing data is rejected. */
        fun decode(data: ByteArray): SosPayload {
            try {
                val b = ByteBuffer.wrap(data)
                if ((b.get().toInt() and 0xff) != VERSION) throw SosFormatException("version")
                val id = ByteArray(EVENT_ID_LEN).also { b.get(it) }
                val type = EmergencyType.from(b.get().toInt() and 0xff) ?: throw SosFormatException("type")
                val status = WireStatus.from(b.get().toInt() and 0xff) ?: throw SosFormatException("status")
                val flags = b.get().toInt() and 0xff
                if (flags and 1.inv() != 0) throw SosFormatException("flags")
                var lat: Int? = null; var lon: Int? = null; var acc: Int? = null; var fix: Long? = null
                if (flags and 1 != 0) {
                    lat = b.getInt(); lon = b.getInt()
                    val a = b.getShort().toInt() and 0xffff; acc = if (a == 0xFFFF) null else a
                    fix = b.getLong()
                    if (lat !in -900_000_000..900_000_000 || lon !in -1_800_000_000..1_800_000_000) throw SosFormatException("coords")
                    if (fix <= 0) throw SosFormatException("fix time")
                }
                val bat = b.get().toInt() and 0xff
                if (bat != 255 && bat > 100) throw SosFormatException("battery")
                val mlen = b.get().toInt() and 0xff
                if (mlen > MAX_MSG) throw SosFormatException("msg length")
                if (b.remaining() != mlen) throw SosFormatException("length mismatch")
                val m = ByteArray(mlen).also { b.get(it) }
                return SosPayload(id, type, status, lat, lon, acc, fix, if (bat == 255) null else bat, String(m, Charsets.UTF_8))
            } catch (e: SosFormatException) { throw e
            } catch (e: BufferUnderflowException) { throw SosFormatException("truncated")
            } catch (e: IllegalArgumentException) { throw SosFormatException(e.message ?: "invalid") }
        }
    }
}

/** SOS_ACK payload: version + eventId. Authenticity comes from the packet signature. */
object SosAckPayload {
    fun encode(eventId: ByteArray): ByteArray { require(eventId.size == SosPayload.EVENT_ID_LEN); return byteArrayOf(1) + eventId }
    fun decode(data: ByteArray): ByteArray {
        if (data.size != 1 + SosPayload.EVENT_ID_LEN || data[0].toInt() != 1) throw SosFormatException("bad ack")
        return data.copyOfRange(1, data.size)
    }
}
