package com.gabriel.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.gabriel.android.sos.SosFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

enum class GpsFixQuality {
    NO_PERMISSION,
    GPS_DISABLED,
    NO_FIX,
    INACCURATE, // > 50 meters
    STALE,      // > 2 minutes old
    GOOD        // <= 50 meters and <= 2 minutes old
}

data class LocationRecord(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val timestampMs: Long
)

data class GpsLocationState(
    val hasPermission: Boolean = false,
    val isGpsEnabled: Boolean = false,
    val fixQuality: GpsFixQuality = GpsFixQuality.NO_FIX,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null,
    val bearingDegrees: Float? = null,
    val speedKmh: Float? = null,
    val fixTimeMs: Long? = null,
    val fixAgeSeconds: Long = 0L,
    val isLocating: Boolean = false,
    val provider: String? = null
) {
    val hasValidFix: Boolean get() = latitude != null && longitude != null
    val latE7: Int? get() = latitude?.let { (it * 1e7).toInt() }
    val lonE7: Int? get() = longitude?.let { (it * 1e7).toInt() }
}

/**
 * Platform LocationManager wrapper strictly for offline emergency use.
 * Does NOT depend on Google Play Services or network/fused providers.
 * Pure GPS_PROVIDER with fallback to last known GPS fix.
 */
class GabrielLocationManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GabrielLocationManager"
        const val ACCURACY_THRESHOLD_M = 50.0f
        const val STALE_THRESHOLD_MS = 120_000L // 2 minutes
        const val MAX_HISTORY_ENTRIES = 100

        @Volatile
        private var instance: GabrielLocationManager? = null

        fun getInstance(context: Context): GabrielLocationManager {
            return instance ?: synchronized(this) {
                instance ?: GabrielLocationManager(context.applicationContext).also { instance = it }
            }
        }

        fun init(context: Context): GabrielLocationManager {
            return getInstance(context).apply {
                startGpsUpdates()
            }
        }

        fun calculateQuality(
            hasPermission: Boolean,
            isGpsEnabled: Boolean,
            location: Location?,
            nowMs: Long
        ): GpsFixQuality {
            if (!hasPermission) return GpsFixQuality.NO_PERMISSION
            if (!isGpsEnabled) return GpsFixQuality.GPS_DISABLED
            if (location == null) return GpsFixQuality.NO_FIX

            val ageMs = nowMs - location.time
            if (ageMs > STALE_THRESHOLD_MS) return GpsFixQuality.STALE
            if (location.hasAccuracy() && location.accuracy > ACCURACY_THRESHOLD_M) return GpsFixQuality.INACCURATE

            return GpsFixQuality.GOOD
        }
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow(GpsLocationState())
    val state: StateFlow<GpsLocationState> = _state.asStateFlow()

    private val history = CopyOnWriteArrayList<LocationRecord>()
    private var lastRawLocation: Location? = null
    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onNewLocation(location)
        }

        @Deprecated("Deprecated in Android Q")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {
            checkPermissionsAndGps()
        }

        override fun onProviderDisabled(provider: String) {
            checkPermissionsAndGps()
        }
    }

    init {
        checkPermissionsAndGps()
        startTicker()
    }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED
    }

    fun isGpsEnabled(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        if (!hasPermission() || locationManager == null) return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider) ?: continue
                    if (best == null || loc.time > best.time) {
                        best = loc
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying last known location from $provider", e)
            }
        }
        return best
    }

    @Synchronized
    fun startGpsUpdates(minTimeMs: Long = 5_000L, minDistanceM: Float = 3.0f) {
        if (isListening) return

        val perm = hasPermission()
        val gps = isGpsEnabled()
        if (!perm || locationManager == null) {
            Log.w(TAG, "Cannot start GPS updates: permission missing or LocationManager null")
            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(
                hasPermission = perm,
                isGpsEnabled = gps,
                fixQuality = calculateQuality(perm, gps, lastRawLocation, now),
                isLocating = false
            )
            return
        }

        // Seed with best last known location immediately
        val lastKnown = getBestLastKnownLocation()
        if (lastKnown != null && (lastRawLocation == null || lastKnown.time > lastRawLocation!!.time)) {
            onNewLocation(lastKnown)
        }

        try {
            if (gps) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    locationListener
                )
                isListening = true
                _state.value = _state.value.copy(
                    hasPermission = true,
                    isGpsEnabled = true,
                    isLocating = true
                )
                Log.i(TAG, "Started GPS location updates (interval: ${minTimeMs}ms, dist: ${minDistanceM}m)")
            } else {
                Log.w(TAG, "GPS Provider is disabled by user in settings")
                _state.value = _state.value.copy(
                    hasPermission = true,
                    isGpsEnabled = false,
                    fixQuality = GpsFixQuality.GPS_DISABLED,
                    isLocating = false
                )
            }

            // Also request from NETWORK_PROVIDER if enabled for faster initial indoor/coarse fix
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        minTimeMs,
                        minDistanceM,
                        locationListener
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network location provider unavailable", e)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting GPS updates", e)
            _state.value = _state.value.copy(
                hasPermission = false,
                fixQuality = GpsFixQuality.NO_PERMISSION,
                isLocating = false
            )
        }
    }

    @Synchronized
    fun stopGpsUpdates() {
        if (!isListening) return
        try {
            locationManager?.removeUpdates(locationListener)
            isListening = false
            _state.value = _state.value.copy(isLocating = false)
            Log.i(TAG, "Stopped GPS location updates")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing GPS updates", e)
        }
    }

    fun checkPermissionsAndGps() {
        val perm = hasPermission()
        val gps = isGpsEnabled()
        val now = System.currentTimeMillis()
        val quality = calculateQuality(perm, gps, lastRawLocation, now)
        val ageSec = lastRawLocation?.let { (now - it.time).coerceAtLeast(0L) / 1000L } ?: 0L

        _state.value = _state.value.copy(
            hasPermission = perm,
            isGpsEnabled = gps,
            fixQuality = quality,
            fixAgeSeconds = ageSec
        )

        // If permission was lost or GPS disabled while listening, stop listening
        if ((!perm || !gps) && isListening) {
            stopGpsUpdates()
        }
    }

    fun getStatusDescription(): String {
        val s = _state.value
        return when {
            !s.hasPermission -> "no permission"
            !s.isGpsEnabled -> "location off"
            s.latitude == null -> "waiting for GPS fix"
            s.fixQuality == GpsFixQuality.STALE -> {
                val mins = (s.fixAgeSeconds / 60).coerceAtLeast(1)
                "last known fix, $mins min old"
            }
            s.fixQuality == GpsFixQuality.INACCURATE -> {
                val acc = s.accuracyM?.toInt()?.let { " (±${it}m)" } ?: ""
                "inaccurate GPS fix$acc"
            }
            else -> {
                val acc = s.accuracyM?.toInt()?.let { " (±${it}m)" } ?: ""
                "GPS lock$acc"
            }
        }
    }

    private fun onNewLocation(location: Location) {
        lastRawLocation = location
        val now = System.currentTimeMillis()
        val perm = hasPermission()
        val gps = isGpsEnabled()
        val quality = calculateQuality(perm, gps, location, now)
        val ageSec = (now - location.time).coerceAtLeast(0L) / 1000L

        // Record in bounded history
        val record = LocationRecord(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            timestampMs = location.time
        )
        history.add(record)
        while (history.size > MAX_HISTORY_ENTRIES) {
            history.removeAt(0)
        }

        _state.value = GpsLocationState(
            hasPermission = perm,
            isGpsEnabled = gps,
            fixQuality = quality,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
            speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null,
            fixTimeMs = location.time,
            fixAgeSeconds = ageSec,
            isLocating = isListening,
            provider = location.provider
        )
    }

    fun getCurrentSosFix(): SosFix? {
        val loc = lastRawLocation ?: return null
        val latE7 = (loc.latitude * 1e7).toInt()
        val lonE7 = (loc.longitude * 1e7).toInt()
        val accM = if (loc.hasAccuracy()) loc.accuracy.roundToInt() else null
        return SosFix(latE7, lonE7, accM, loc.time)
    }

    fun getLocationHistory(): List<LocationRecord> = history.toList()

    fun clearLocationHistory() {
        history.clear()
        lastRawLocation = null
        checkPermissionsAndGps()
        _state.value = _state.value.copy(
            latitude = null,
            longitude = null,
            accuracyM = null,
            fixTimeMs = null,
            fixAgeSeconds = 0L,
            fixQuality = calculateQuality(hasPermission(), isGpsEnabled(), null, System.currentTimeMillis())
        )
        Log.i(TAG, "Location history cleared by user action")
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val loc = lastRawLocation
                if (loc != null) {
                    val now = System.currentTimeMillis()
                    val ageSec = (now - loc.time).coerceAtLeast(0L) / 1000L
                    val quality = calculateQuality(hasPermission(), isGpsEnabled(), loc, now)
                    _state.value = _state.value.copy(
                        fixAgeSeconds = ageSec,
                        fixQuality = quality
                    )
                }
            }
        }
    }
}
