package com.gabriel.android.location

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.gabriel.android.protocol.GabrielPacket
import com.gabriel.android.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

interface PeerLocationSender {
    fun sendPeerLocation(payload: ByteArray): Boolean
}

data class PeerLocationInfo(
    val peerID: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Int?,
    val timestampMs: Long,
    val batteryPct: Int?,
    val receivedAtMs: Long
)

/**
 * Manages opt-in signed peer location broadcasting and reception over BLE mesh.
 * Location sharing is disabled by default (opt-in only).
 * Rate-limiting & thresholding: broadcasts only when moving > 50 meters or every 5 minutes.
 */
object PeerLocationManager {
    private const val TAG = "PeerLocationManager"
    private const val MIN_DISTANCE_METERS = 50.0
    private const val MIN_INTERVAL_MS = 300_000L // 5 minutes
    private const val MAX_STALE_AGE_MS = 86_400_000L // 24 hours
    private const val MAX_FUTURE_SKEW_MS = 300_000L // 5 minutes
    private const val PRUNE_INTERVAL_MS = 7_200_000L // 2 hours

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var myPeerID = ""
    private var sender: PeerLocationSender? = null
    private var appContext: Context? = null
    private var broadcasterJob: Job? = null

    private var lastSentLatE7: Int? = null
    private var lastSentLonE7: Int? = null
    private var lastSentTimeMs: Long = 0L

    private val _isSharingEnabled = MutableStateFlow(false)
    val isSharingEnabled: StateFlow<Boolean> = _isSharingEnabled

    private val _peerLocations = MutableStateFlow<Map<String, PeerLocationInfo>>(emptyMap())
    val peerLocations: StateFlow<Map<String, PeerLocationInfo>> = _peerLocations

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun attach(peerID: String, s: PeerLocationSender) {
        synchronized(lock) {
            myPeerID = peerID
            sender = s
        }
    }

    fun setSharingEnabled(enabled: Boolean) {
        synchronized(lock) {
            _isSharingEnabled.value = enabled
            if (enabled) {
                startBroadcaster()
            } else {
                stopBroadcaster()
            }
        }
    }

    private fun startBroadcaster() {
        broadcasterJob?.cancel()
        broadcasterJob = scope.launch {
            val ctx = appContext ?: return@launch
            val locManager = GabrielLocationManager.getInstance(ctx)
            locManager.state.collect { locState ->
                if (!_isSharingEnabled.value) return@collect
                val latE7 = locState.latE7 ?: return@collect
                val lonE7 = locState.lonE7 ?: return@collect
                val now = System.currentTimeMillis()

                val shouldSend = shouldBroadcastFix(latE7, lonE7, now)
                if (shouldSend) {
                    val battery = readBatteryPercentage()
                    val payload = PeerLocationPayload(
                        latE7 = latE7,
                        lonE7 = lonE7,
                        accuracyM = locState.accuracyM?.roundToInt(),
                        timestampMs = locState.fixTimeMs ?: now,
                        batteryPct = battery
                    )
                    val ok = try {
                        sender?.sendPeerLocation(payload.encode()) ?: false
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send peer location: ${e.message}")
                        false
                    }
                    if (ok) {
                        synchronized(lock) {
                            lastSentLatE7 = latE7
                            lastSentLonE7 = lonE7
                            lastSentTimeMs = now
                        }
                    }
                }
            }
        }
    }

    private fun stopBroadcaster() {
        broadcasterJob?.cancel()
        broadcasterJob = null
    }

    private fun shouldBroadcastFix(latE7: Int, lonE7: Int, now: Long): Boolean {
        synchronized(lock) {
            val lastLat = lastSentLatE7
            val lastLon = lastSentLonE7
            if (lastLat == null || lastLon == null) {
                return true
            }
            val elapsed = now - lastSentTimeMs
            if (elapsed >= MIN_INTERVAL_MS) {
                return true
            }
            val distance = haversineDistanceMeters(
                lastLat / 1e7, lastLon / 1e7,
                latE7 / 1e7, lonE7 / 1e7
            )
            return distance >= MIN_DISTANCE_METERS
        }
    }

    fun onPacket(packet: GabrielPacket): Boolean {
        if (packet.type != MessageType.PEER_LOCATION.value) return false
        val origin = packet.senderID.joinToString("") { "%02x".format(it) }
        if (origin == myPeerID) return true
        val now = System.currentTimeMillis()

        return try {
            val payload = PeerLocationPayload.decode(packet.payload)
            // Time validation: reject stale (>24h) or future-skewed (>5m) packets
            val age = now - payload.timestampMs
            if (age > MAX_STALE_AGE_MS || -age > MAX_FUTURE_SKEW_MS) {
                Log.w(TAG, "Dropping peer location packet outside time bounds (age=${age}ms)")
                return true
            }

            synchronized(lock) {
                val current = _peerLocations.value.toMutableMap()
                // Only overwrite if this fix is newer than existing fix for this peer
                val existing = current[origin]
                if (existing == null || payload.timestampMs >= existing.timestampMs) {
                    current[origin] = PeerLocationInfo(
                        peerID = origin,
                        latitude = payload.latitude,
                        longitude = payload.longitude,
                        accuracyM = payload.accuracyM,
                        timestampMs = payload.timestampMs,
                        batteryPct = payload.batteryPct,
                        receivedAtMs = now
                    )
                }
                // Prune expired locations (>2 hours old)
                val cutoff = now - PRUNE_INTERVAL_MS
                val pruned = current.filterValues { it.receivedAtMs >= cutoff }
                _peerLocations.value = pruned
            }
            true
        } catch (e: PeerLocationFormatException) {
            Log.w(TAG, "Dropping malformed peer location packet: ${e.message}")
            false
        }
    }

    private fun readBatteryPercentage(): Int? {
        val ctx = appContext ?: return null
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct != null && pct in 0..100) pct else null
        } catch (_: Exception) {
            null
        }
    }

    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
