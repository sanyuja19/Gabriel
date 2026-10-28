package com.gabriel.android.location

import java.nio.ByteBuffer
import java.nio.ByteOrder

class PeerLocationFormatException(message: String) : Exception(message)

/**
 * Binary wire format for GABRIEL opt-in signed peer location share.
 * Wire length: 20 bytes.
 *
 * Layout:
 * - Version (1 byte): 0x01
 * - latE7 (4 bytes, signed Int32, big-endian)
 * - lonE7 (4 bytes, signed Int32, big-endian)
 * - accuracyM (2 bytes, unsigned UInt16, big-endian, 0xFFFF if unknown)
 * - timestampMs (8 bytes, Int64, big-endian)
 * - batteryPct (1 byte, unsigned 0..100, 0xFF if unknown)
 */
data class PeerLocationPayload(
    val latE7: Int,
    val lonE7: Int,
    val accuracyM: Int?,
    val timestampMs: Long,
    val batteryPct: Int?
) {
    val latitude: Double get() = latE7 / 1e7
    val longitude: Double get() = lonE7 / 1e7

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(PAYLOAD_LEN).order(ByteOrder.BIG_ENDIAN)
        buf.put(VERSION)
        buf.putInt(latE7)
        buf.putInt(lonE7)
        val accShort = accuracyM?.coerceIn(0, 65534)?.toShort() ?: UNKNOWN_ACCURACY.toShort()
        buf.putShort(accShort)
        buf.putLong(timestampMs)
        val batByte = batteryPct?.coerceIn(0, 100)?.toByte() ?: UNKNOWN_BATTERY.toByte()
        buf.put(batByte)
        return buf.array()
    }

    companion object {
        const val VERSION: Byte = 1
        const val PAYLOAD_LEN = 20
        const val UNKNOWN_ACCURACY = 0xFFFF
        const val UNKNOWN_BATTERY = 0xFF

        fun decode(bytes: ByteArray): PeerLocationPayload {
            if (bytes.size != PAYLOAD_LEN) {
                throw PeerLocationFormatException("Invalid payload length: expected $PAYLOAD_LEN, got ${bytes.size}")
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val ver = buf.get()
            if (ver != VERSION) {
                throw PeerLocationFormatException("Unsupported version: $ver")
            }
            val lat = buf.getInt()
            val lon = buf.getInt()
            val accRaw = buf.getShort().toInt() and 0xFFFF
            val time = buf.getLong()
            val batRaw = buf.get().toInt() and 0xFF

            if (lat < -900_000_000 || lat > 900_000_000) {
                throw PeerLocationFormatException("Latitude out of bounds: $lat")
            }
            if (lon < -1_800_000_000 || lon > 1_800_000_000) {
                throw PeerLocationFormatException("Longitude out of bounds: $lon")
            }

            val accuracy = if (accRaw == UNKNOWN_ACCURACY) null else accRaw
            val battery = if (batRaw == UNKNOWN_BATTERY || batRaw > 100) null else batRaw

            return PeerLocationPayload(
                latE7 = lat,
                lonE7 = lon,
                accuracyM = accuracy,
                timestampMs = time,
                batteryPct = battery
            )
        }
    }
}
