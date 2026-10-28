package com.gabriel.android.ui.gabriel

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.text.selection.SelectionContainer
import com.gabriel.android.location.EmergencyCategory
import com.gabriel.android.location.EmergencyNavigationManager
import com.gabriel.android.location.EmergencyPoi
import com.gabriel.android.location.EmergencyPoiManager
import com.gabriel.android.location.GabrielLocationManager
import com.gabriel.android.location.GpsFixQuality
import com.gabriel.android.location.GpsLocationState
import com.gabriel.android.location.OfflineRoutingEngine
import com.gabriel.android.location.ManeuverType
import com.gabriel.android.location.NavigationDestination
import com.gabriel.android.location.PeerLocationInfo
import com.gabriel.android.location.PeerLocationManager
import com.gabriel.android.sos.ReceivedSosView
import com.gabriel.android.sos.SosManager
import com.gabriel.android.sos.WireStatus
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import java.io.File

enum class MapTrackingMode {
    FREE,           // User freely pans map
    FOLLOW_USER,    // Camera locks & centers on user location
    COMPASS_3D      // 45° perspective tilt + rotates with compass heading
}

fun getOfflineMapFiles(context: Context): List<File> {
    val mapsDir = File(context.getExternalFilesDir(null), "maps")
    if (!mapsDir.exists()) {
        mapsDir.mkdirs()
    }
    return mapsDir.listFiles { file -> file.extension.lowercase() == "pmtiles" }?.toList() ?: emptyList()
}

fun buildPmtilesStyleJson(pmtilesFile: File): String {
    val pmtilesUrl = "pmtiles://file://${pmtilesFile.absolutePath}"
    return """
    {
      "version": 8,
      "name": "Gabriel Offline PMTiles",
      "sources": {
        "protomaps": {
          "type": "vector",
          "url": "$pmtilesUrl"
        }
      },
      "layers": [
        {
          "id": "background",
          "type": "background",
          "paint": { "background-color": "#111a11" }
        },
        {
          "id": "water",
          "type": "fill",
          "source": "protomaps",
          "source-layer": "water",
          "paint": { "fill-color": "#0d2b3a" }
        },
        {
          "id": "earth",
          "type": "fill",
          "source": "protomaps",
          "source-layer": "earth",
          "paint": { "fill-color": "#1a2a1a" }
        },
        {
          "id": "landuse",
          "type": "fill",
          "source": "protomaps",
          "source-layer": "landuse",
          "paint": { "fill-color": "#1e331e" }
        },
        {
          "id": "roads",
          "type": "line",
          "source": "protomaps",
          "source-layer": "roads",
          "paint": {
            "line-color": "#3d523d",
            "line-width": 1.5
          }
        },
        {
          "id": "boundaries",
          "type": "line",
          "source": "protomaps",
          "source-layer": "boundaries",
          "paint": {
            "line-color": "#5b735b",
            "line-dasharray": [2, 2],
            "line-width": 1.0
          }
        }
      ]
    }
    """.trimIndent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    targetLat: Double? = null,
    targetLon: Double? = null,
    onNavigateToSos: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val locationManager = remember(context) { GabrielLocationManager.getInstance(context) }
    val locationState by locationManager.state.collectAsState()
    val receivedAlerts by SosManager.received.collectAsState()
    val peerLocations by PeerLocationManager.peerLocations.collectAsState()
    val isSharingLocation by PeerLocationManager.isSharingEnabled.collectAsState()
    val navState by EmergencyNavigationManager.state.collectAsState()

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var userMarker by remember { mutableStateOf<Marker?>(null) }
    val alertMarkers = remember { mutableStateMapOf<String, Marker>() }
    val peerMarkers = remember { mutableStateMapOf<String, Marker>() }
    val poiMarkers = remember { mutableStateMapOf<String, Marker>() }
    var customMarker by remember { mutableStateOf<Marker?>(null) }

    var routePolyline by remember { mutableStateOf<Polyline?>(null) }
    var routeBorderPolyline by remember { mutableStateOf<Polyline?>(null) }

    var trackingMode by remember { mutableStateOf(MapTrackingMode.FOLLOW_USER) }

    var selectedAlert by remember { mutableStateOf<ReceivedSosView?>(null) }
    var selectedPeer by remember { mutableStateOf<PeerLocationInfo?>(null) }
    var selectedPoi by remember { mutableStateOf<EmergencyPoi?>(null) }
    var customWaypoint by remember { mutableStateOf<LatLng?>(null) }

    var showMapInfoSheet by remember { mutableStateOf(false) }
    var showSafeLocationsSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf(EmergencyCategory.ALL) }

    var currentZoom by remember { mutableStateOf(4.5) }
    var currentCenter by remember { mutableStateOf(LatLng(20.5937, 78.9629)) }

    // Bind location lifecycle to resume GPS updates whenever MapScreen is active
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationManager.startGpsUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        locationManager.startGpsUpdates()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val mapFiles = remember { getOfflineMapFiles(context) }
    val hasPmtiles = mapFiles.isNotEmpty()

    var mapInitError by remember { mutableStateOf<String?>(null) }
    var isStyleLoaded by remember { mutableStateOf(false) }

    // Safely instantiate MapView and catch any native / GL context creation failures
    val mapView = remember(context) {
        try {
            MapLibre.getInstance(context)
            MapView(context).apply {
                onCreate(null)
            }
        } catch (e: Throwable) {
            android.util.Log.e("MapScreen", "Failed to create MapView", e)
            mapInitError = e.message ?: "Failed to initialize MapView"
            null
        }
    }

    // Forward Android Activity lifecycle to MapView so EGL context and render threads are properly managed
    DisposableEffect(lifecycleOwner, mapView) {
        val mv = mapView
        if (mv != null) {
            val observer = LifecycleEventObserver { _, event ->
                try {
                    when (event) {
                        Lifecycle.Event.ON_START -> mv.onStart()
                        Lifecycle.Event.ON_RESUME -> mv.onResume()
                        Lifecycle.Event.ON_PAUSE -> mv.onPause()
                        Lifecycle.Event.ON_STOP -> mv.onStop()
                        Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
                        else -> {}
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("MapScreen", "MapView lifecycle error on $event", e)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)

            // Since the Activity is already resumed when user taps the MAP tab,
            // immediately call onStart and onResume so the GL surface starts cleanly!
            try {
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    mv.onStart()
                }
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    mv.onResume()
                }
            } catch (e: Throwable) {
                android.util.Log.e("MapScreen", "Error starting MapView", e)
            }

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                try {
                    mv.onPause()
                    mv.onStop()
                    mv.onDestroy()
                } catch (e: Throwable) {
                    android.util.Log.e("MapScreen", "Error destroying MapView", e)
                }
            }
        } else {
            onDispose { }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (mapView != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { _ ->
                    mapView.apply {
                        getMapAsync { map ->
                            mapInstance = map
                            try {
                                map.uiSettings.isAttributionEnabled = false
                                map.uiSettings.isLogoEnabled = false
                                map.uiSettings.isCompassEnabled = true
                                map.uiSettings.isRotateGesturesEnabled = true
                                map.uiSettings.isTiltGesturesEnabled = true

                                map.addOnCameraMoveListener {
                                    currentZoom = map.cameraPosition.zoom
                                    map.cameraPosition.target?.let { currentCenter = it }
                                }

                                // Initial camera: India overview or target position
                                val initialPos = LatLng(targetLat ?: locationState.latitude ?: 20.5937,
                                                        targetLon ?: locationState.longitude ?: 78.9629)
                                val initialZoom = if (targetLat != null) 14.0 else if (locationState.latitude != null) 15.0 else 4.5
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(initialPos)
                                    .zoom(initialZoom)
                                    .build()

                                // Load offline style: PMTiles if installed on device, otherwise bundled Google Maps style
                                val styleBuilder = if (hasPmtiles) {
                                    val styleJson = buildPmtilesStyleJson(mapFiles.first())
                                    Style.Builder().fromJson(styleJson)
                                } else {
                                    Style.Builder().fromUri("asset://offline_basemap_style.json")
                                }

                                map.setStyle(styleBuilder) { _ ->
                                    isStyleLoaded = true
                                    android.util.Log.i("MapScreen", "Map style loaded successfully")

                                    // Plot Bundled Emergency Safe POIs ONLY after style is loaded
                                    try {
                                        EmergencyPoiManager.BUNDLED_POIS.forEach { poi ->
                                            val marker = map.addMarker(
                                                MarkerOptions()
                                                    .position(LatLng(poi.latitude, poi.longitude))
                                                    .title("[${poi.category.displayName}] ${poi.name}")
                                                    .snippet("${poi.cityOrRegion} • ${poi.contact}")
                                            )
                                            if (marker != null) poiMarkers[poi.id] = marker
                                        }
                                    } catch (e: Throwable) {
                                        android.util.Log.e("MapScreen", "Error adding initial markers", e)
                                    }
                                }

                        // Marker click listener
                        map.setOnMarkerClickListener { marker ->
                            // Check if SOS alert clicked
                            val matchedAlert = receivedAlerts.firstOrNull { alert ->
                                alert.payload.latitude?.let { lat ->
                                    alert.payload.longitude?.let { lon ->
                                        Math.abs(marker.position.latitude - lat) < 0.0002 &&
                                        Math.abs(marker.position.longitude - lon) < 0.0002
                                    }
                                } ?: false
                            }
                            if (matchedAlert != null) {
                                selectedAlert = matchedAlert
                                selectedPeer = null
                                selectedPoi = null
                                customWaypoint = null
                                return@setOnMarkerClickListener true
                            }

                            // Check if Peer location clicked
                            val matchedPeer = peerLocations.values.firstOrNull { peer ->
                                Math.abs(marker.position.latitude - peer.latitude) < 0.0002 &&
                                Math.abs(marker.position.longitude - peer.longitude) < 0.0002
                            }
                            if (matchedPeer != null) {
                                selectedPeer = matchedPeer
                                selectedAlert = null
                                selectedPoi = null
                                customWaypoint = null
                                return@setOnMarkerClickListener true
                            }

                            // Check if Emergency POI clicked
                            val matchedPoi = EmergencyPoiManager.BUNDLED_POIS.firstOrNull { poi ->
                                Math.abs(marker.position.latitude - poi.latitude) < 0.0002 &&
                                Math.abs(marker.position.longitude - poi.longitude) < 0.0002
                            }
                            if (matchedPoi != null) {
                                selectedPoi = matchedPoi
                                selectedAlert = null
                                selectedPeer = null
                                customWaypoint = null
                                return@setOnMarkerClickListener true
                            }

                            false
                        }

                        // Map click listener: tap to drop custom emergency waypoint
                        map.addOnMapClickListener { point ->
                            selectedAlert = null
                            selectedPeer = null
                            selectedPoi = null
                            customWaypoint = point
                            if (customMarker == null) {
                                customMarker = map.addMarker(
                                    MarkerOptions()
                                        .position(point)
                                        .title("Custom Evacuation Waypoint")
                                        .snippet("${"%.4f".format(point.latitude)}, ${"%.4f".format(point.longitude)}")
                                )
                            } else {
                                customMarker?.position = point
                            }
                            true
                        }

                        // Gesture detection: user moved map manually -> drop tracking to FREE
                        map.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                trackingMode = MapTrackingMode.FREE
                            }
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("MapScreen", "Error configuring map in getMapAsync", e)
                    }
                }
            }
        },
            update = { _ ->
                val map = mapInstance ?: return@AndroidView
                if (!isStyleLoaded) return@AndroidView

                try {
                    // 1. Update user location & push to navigation engine
                    val uLat = locationState.latitude
                    val uLon = locationState.longitude
                    if (uLat != null && uLon != null) {
                        val userPos = LatLng(uLat, uLon)
                        val acc = locationState.accuracyM?.toInt() ?: 0
                        val speed = locationState.speedKmh?.let { " • %.1f km/h".format(it) } ?: ""

                        if (userMarker == null) {
                            userMarker = map.addMarker(
                                MarkerOptions()
                                    .position(userPos)
                                    .title("My Location (GPS)")
                                    .snippet("Accuracy: ±${acc}m$speed")
                            )
                        } else {
                            userMarker?.position = userPos
                            userMarker?.snippet = "Accuracy: ±${acc}m$speed"
                        }

                        // Feed position into navigation manager
                        if (navState.isNavigating) {
                            EmergencyNavigationManager.updateUserLocation(
                                uLat,
                                uLon,
                                locationState.bearingDegrees
                            )
                        }

                        // Camera follow modes
                        if (trackingMode == MapTrackingMode.FOLLOW_USER) {
                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(userPos)
                                        .zoom(15.5)
                                        .tilt(0.0)
                                        .bearing(0.0)
                                        .build()
                                ),
                                600
                            )
                        } else if (trackingMode == MapTrackingMode.COMPASS_3D) {
                            val heading = if (navState.isNavigating) {
                                navState.bearingDegrees
                            } else {
                                locationState.bearingDegrees?.toDouble() ?: 0.0
                            }
                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(userPos)
                                        .zoom(16.5)
                                        .tilt(45.0) // 3D Google Maps Perspective Tilt
                                        .bearing(heading) // Heading orientation
                                        .build()
                                ),
                                600
                            )
                        }
                    }

                    // 2. Manage Emergency Route Polyline
                    if (navState.isNavigating && navState.routeWaypoints.isNotEmpty()) {
                        val points = navState.routeWaypoints.map { LatLng(it.first, it.second) }

                        // Outer border line for high contrast
                        if (routeBorderPolyline == null) {
                            routeBorderPolyline = map.addPolyline(
                                PolylineOptions()
                                    .addAll(points)
                                    .color(android.graphics.Color.parseColor("#0D47A1"))
                                    .width(10.0f)
                            )
                        } else {
                            routeBorderPolyline?.points = points
                        }

                        // Inner bright Google Maps route polyline
                        if (routePolyline == null) {
                            routePolyline = map.addPolyline(
                                PolylineOptions()
                                    .addAll(points)
                                    .color(android.graphics.Color.parseColor("#1A73E8"))
                                    .width(6.5f)
                            )
                        } else {
                            routePolyline?.points = points
                        }
                    } else {
                        // Remove polyline when navigation stops
                        routePolyline?.let { map.removePolyline(it) }
                        routeBorderPolyline?.let { map.removePolyline(it) }
                        routePolyline = null
                        routeBorderPolyline = null
                    }

                    // 3. Update SOS markers
                    receivedAlerts.filter { it.payload.status == WireStatus.ACTIVE && it.payload.hasLocation }.forEach { alert ->
                        val aLat = alert.payload.latitude
                        val aLon = alert.payload.longitude
                        if (aLat != null && aLon != null) {
                            val pos = LatLng(aLat, aLon)
                            if (!alertMarkers.containsKey(alert.key)) {
                                val m = map.addMarker(
                                    MarkerOptions()
                                        .position(pos)
                                        .title("SOS: ${alert.payload.type.name}")
                                        .snippet(alert.payload.message)
                                )
                                if (m != null) alertMarkers[alert.key] = m
                            } else {
                                alertMarkers[alert.key]?.position = pos
                            }
                        }
                    }

                    // 4. Update Peer location markers
                    peerLocations.forEach { (peerId, info) ->
                        val pos = LatLng(info.latitude, info.longitude)
                        if (!peerMarkers.containsKey(peerId)) {
                            val m = map.addMarker(
                                MarkerOptions()
                                    .position(pos)
                                    .title("Peer ${peerId.take(8)}")
                                    .snippet("Battery: ${info.batteryPct?.let { "$it%" } ?: "Unknown"}")
                            )
                            if (m != null) peerMarkers[peerId] = m
                        } else {
                            peerMarkers[peerId]?.position = pos
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("MapScreen", "Error during map update", e)
                }
            }
        )
    } else {
        MapFallbackView(
            locationState = locationState,
            errorMessage = mapInitError,
            onOpenDirectory = { showSafeLocationsSheet = true }
        )
    }

        // Target navigation effect (e.g. from SOS or POI "View on Map" click)
        LaunchedEffect(targetLat, targetLon) {
            if (targetLat != null && targetLon != null && mapInstance != null) {
                trackingMode = MapTrackingMode.FREE
                mapInstance?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(targetLat, targetLon), 14.5),
                    800
                )
            }
        }

        // ==============================================================
        // TOP OVERLAY: Google Maps Navigation HUD or Search/Filter Bar
        // ==============================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, start = 12.dp, end = 12.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (navState.isNavigating) {
                // GOOGLE MAPS NAVIGATION TURN-BY-TURN HUD (EMERALD GREEN BANNER)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0F5132),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Maneuver directional arrow icon
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF198754)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (navState.maneuverType) {
                                    ManeuverType.ARRIVED -> Icons.Default.CheckCircle
                                    ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT -> Icons.Default.TurnRight
                                    ManeuverType.LEFT, ManeuverType.SHARP_LEFT -> Icons.Default.TurnLeft
                                    ManeuverType.SLIGHT_RIGHT -> Icons.Default.TurnSlightRight
                                    ManeuverType.SLIGHT_LEFT -> Icons.Default.TurnSlightLeft
                                    ManeuverType.U_TURN -> Icons.Default.RotateLeft
                                    else -> Icons.Default.Straight
                                },
                                contentDescription = "Turn Maneuver",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Instruction and distance
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (navState.remainingDistanceMeters < 1000)
                                    "${navState.remainingDistanceMeters.toInt()} m"
                                else
                                    "%.1f km".format(navState.remainingDistanceMeters / 1000.0),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = navState.turnInstruction,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFD1E7DD),
                                maxLines = 2
                            )
                        }

                        // Stop Navigation Button
                        IconButton(
                            onClick = { EmergencyNavigationManager.stopNavigation() },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.3f))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Exit Navigation", tint = Color.White)
                        }
                    }
                }
            } else {
                // GOOGLE MAPS STYLE FLOATING SEARCH BAR (Evacuate button removed)
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 6.dp,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )

                        Text(
                            text = if (searchQuery.isEmpty()) "Search emergency locations, hospitals, shelters…" else searchQuery,
                            color = if (searchQuery.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showSafeLocationsSheet = true }
                        )

                        IconButton(
                            onClick = { showMapInfoSheet = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = "Installed Maps", modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // 1. LOCATION STATUS BANNER (Truthful GPS state readout)
                val statusDesc = locationManager.getStatusDescription()
                val isGpsLocked = locationState.fixQuality == GpsFixQuality.GOOD ||
                                 locationState.fixQuality == GpsFixQuality.INACCURATE
                if (!isGpsLocked) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when (locationState.fixQuality) {
                            GpsFixQuality.NO_PERMISSION,
                            GpsFixQuality.GPS_DISABLED -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.tertiaryContainer
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val (statusIcon, iconTint) = when (locationState.fixQuality) {
                                GpsFixQuality.NO_PERMISSION -> Icons.Default.LocationOff to MaterialTheme.colorScheme.error
                                GpsFixQuality.GPS_DISABLED -> Icons.Default.GpsOff to MaterialTheme.colorScheme.error
                                GpsFixQuality.STALE -> Icons.Default.GpsFixed to Color(0xFFE0A100)
                                else -> Icons.Default.GpsNotFixed to Color(0xFFE0A100)
                            }
                            Icon(statusIcon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
                            Text(
                                text = when (locationState.fixQuality) {
                                    GpsFixQuality.NO_PERMISSION -> "Location permission required. Tap to grant."
                                    GpsFixQuality.GPS_DISABLED -> "Location turned off in device settings. Enable GPS."
                                    GpsFixQuality.STALE -> {
                                        val mins = (locationState.fixAgeSeconds / 60).coerceAtLeast(1)
                                        "Last known fix, $mins min old (${locationState.accuracyM?.toInt()?.let { "±${it}m" } ?: ""})"
                                    }
                                    GpsFixQuality.NO_FIX -> "Waiting for GPS fix (searching satellites)…"
                                    else -> statusDesc
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // 2. MAP DETAIL & COVERAGE WARNINGS
                val isOutsideIndia = currentCenter.latitude < 6.0 || currentCenter.latitude > 38.0 ||
                                    currentCenter.longitude < 68.0 || currentCenter.longitude > 98.0
                if (hasPmtiles && isOutsideIndia) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF3B2A10),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(14.dp))
                            Text(
                                text = "Outside downloaded offline map area (India bounds)",
                                fontSize = 11.sp,
                                color = Color(0xFFFFD54F)
                            )
                        }
                    }
                }

                // HORIZONTAL CATEGORY FILTER CHIPS (Like Google Maps)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(EmergencyCategory.values()) { cat ->
                        FilterChip(
                            selected = selectedCategoryFilter == cat,
                            onClick = {
                                selectedCategoryFilter = cat
                                // Filter POIs on map
                                val uLat = locationState.latitude
                                val uLon = locationState.longitude
                                val results = EmergencyPoiManager.searchPois("", cat, uLat, uLon)
                                results.firstOrNull()?.let { topItem ->
                                    mapInstance?.animateCamera(
                                        CameraUpdateFactory.newLatLngZoom(
                                            LatLng(topItem.poi.latitude, topItem.poi.longitude),
                                            13.0
                                        ),
                                        800
                                    )
                                }
                            },
                            label = { Text(cat.displayName, fontSize = 11.sp) },
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            )
                        )
                    }
                }
            }

            // Peer Location Sharing Status Banner
            if (isSharingLocation) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF003322).copy(alpha = 0.9f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34C759)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.ShareLocation, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(14.dp))
                            Text("Peer Location Sharing Active (Mesh)", fontSize = 11.sp, color = Color.White)
                        }
                        TextButton(
                            onClick = { PeerLocationManager.setSharingEnabled(false) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("STOP", fontSize = 11.sp, color = Color(0xFFFF453A), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // RECENTER FLOATING PILL (Shows when user panned away in FREE mode)
            if (trackingMode == MapTrackingMode.FREE && locationState.latitude != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable {
                            trackingMode = MapTrackingMode.FOLLOW_USER
                            val uLat = locationState.latitude
                            val uLon = locationState.longitude
                            if (uLat != null && uLon != null) {
                                mapInstance?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(uLat, uLon), 15.5),
                                    600
                                )
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                        Text("Recenter on My Location", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        // ==============================================================
        // FLOATING ACTION BUTTONS (Location Follow, Zoom, Places)
        // ==============================================================
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    bottom = if (navState.isNavigating) 110.dp
                             else if (selectedAlert != null || selectedPeer != null || selectedPoi != null || customWaypoint != null) 220.dp
                             else 20.dp,
                    end = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Safe Places List Sheet FAB
            FloatingActionButton(
                onClick = { showSafeLocationsSheet = true },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = "Safe Places Directory")
            }

            // Mesh Location Sharing FAB
            FloatingActionButton(
                onClick = { PeerLocationManager.setSharingEnabled(!isSharingLocation) },
                containerColor = if (isSharingLocation) Color(0xFF34C759) else MaterialTheme.colorScheme.surface,
                contentColor = if (isSharingLocation) Color.Black else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    imageVector = if (isSharingLocation) Icons.Default.LocationOn else Icons.Default.LocationOff,
                    contentDescription = "Toggle Mesh Location Sharing"
                )
            }

            // Google Maps 3-State Camera Tracking FAB (Free -> Follow -> 3D Compass Perspective)
            FloatingActionButton(
                onClick = {
                    val uLat = locationState.latitude
                    val uLon = locationState.longitude
                    if (uLat != null && uLon != null && mapInstance != null) {
                        trackingMode = when (trackingMode) {
                            MapTrackingMode.FREE -> MapTrackingMode.FOLLOW_USER
                            MapTrackingMode.FOLLOW_USER -> MapTrackingMode.COMPASS_3D
                            MapTrackingMode.COMPASS_3D -> MapTrackingMode.FOLLOW_USER
                        }
                    }
                },
                containerColor = when (trackingMode) {
                    MapTrackingMode.FREE -> MaterialTheme.colorScheme.surface
                    MapTrackingMode.FOLLOW_USER -> Color(0xFF1A73E8) // Google Maps Blue
                    MapTrackingMode.COMPASS_3D -> Color(0xFF00E5FF)  // Electric Cyan
                },
                contentColor = when (trackingMode) {
                    MapTrackingMode.FREE -> MaterialTheme.colorScheme.onSurface
                    MapTrackingMode.FOLLOW_USER -> Color.White
                    MapTrackingMode.COMPASS_3D -> Color.Black
                },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = when (trackingMode) {
                        MapTrackingMode.FREE -> Icons.Default.MyLocation
                        MapTrackingMode.FOLLOW_USER -> Icons.Default.MyLocation
                        MapTrackingMode.COMPASS_3D -> Icons.Default.Explore
                    },
                    contentDescription = "Tracking Mode: ${trackingMode.name}"
                )
            }

            // Zoom In
            SmallFloatingActionButton(
                onClick = { mapInstance?.animateCamera(CameraUpdateFactory.zoomIn(), 250) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In")
            }

            // Zoom Out
            SmallFloatingActionButton(
                onClick = { mapInstance?.animateCamera(CameraUpdateFactory.zoomOut(), 250) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out")
            }
        }

        // ==============================================================
        // BOTTOM OVERLAYS: Google Maps Trip Bar OR Place Detail Cards
        // ==============================================================

        // 1. GOOGLE MAPS BOTTOM NAVIGATION TRIP BAR (When Navigating)
        AnimatedVisibility(
            visible = navState.isNavigating,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(12.dp),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Walking ETA, Remaining Distance, Arrival Clock
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${navState.estimatedWalkingMinutes} min",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF198754)
                            )
                            Text(text = "•", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (navState.remainingDistanceMeters < 1000)
                                    "${navState.remainingDistanceMeters.toInt()} m"
                                else
                                    "%.1f km".format(navState.remainingDistanceMeters / 1000.0),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(text = "•", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = navState.etaFormatted,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Stop Navigation Button
                        Button(
                            onClick = { EmergencyNavigationManager.stopNavigation() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("STOP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Destination Info & Compass readout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = navState.destination?.name ?: "Emergency Destination",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                            Text(
                                text = "Bearing ${navState.compassDirection} (${navState.bearingDegrees.toInt()}°) • ${navState.turnInstruction}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF0E2A1F))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "OFFLINE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676),
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }

                    // Route Disclaimer banner
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = navState.routeDisclaimer,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 2. SELECTED SAFE LOCATION POI CARD WITH "START ROUTE"
        AnimatedVisibility(
            visible = !navState.isNavigating && selectedPoi != null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val poi = selectedPoi
            if (poi != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF102216)),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF34C759)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = poi.name,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34C759),
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { selectedPoi = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }

                        Text(text = "${poi.category.displayName} • ${poi.cityOrRegion}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))

                        val uLat = locationState.latitude
                        val uLon = locationState.longitude
                        if (uLat != null && uLon != null) {
                            val distM = EmergencyPoiManager.calculateDistanceMeters(uLat, uLon, poi.latitude, poi.longitude)
                            val bearing = EmergencyPoiManager.calculateBearingDegrees(uLat, uLon, poi.latitude, poi.longitude)
                            val dir = EmergencyPoiManager.bearingToCompassDirection(bearing)
                            Text(
                                text = "Distance: ${if (distM < 1000) "${distM.toInt()} m" else "%.1f km".format(distM / 1000.0)} • Heading: $dir (${bearing.toInt()}°)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD60A)
                            )
                        }

                        Text(text = "Emergency Contact: ${poi.contact}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                        Text(text = poi.emergencyNotes, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))

                        // START ROUTE BUTTON (GOOGLE MAPS STYLE)
                        Button(
                            onClick = {
                                val dest = NavigationDestination(
                                    id = poi.id,
                                    name = poi.name,
                                    category = poi.category.name,
                                    latitude = poi.latitude,
                                    longitude = poi.longitude,
                                    cityOrRegion = poi.cityOrRegion,
                                    contact = poi.contact,
                                    notes = poi.emergencyNotes
                                )
                                EmergencyNavigationManager.startNavigation(dest, uLat, uLon, context)
                                trackingMode = MapTrackingMode.COMPASS_3D
                                selectedPoi = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("START ROUTE (OFFLINE DIRECTIONS)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // 3. SELECTED SOS ALERT CARD WITH "NAVIGATE TO BEACON"
        AnimatedVisibility(
            visible = !navState.isNavigating && selectedAlert != null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val alert = selectedAlert
            if (alert != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF281111)),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFF453A)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "EMERGENCY: ${alert.payload.type.name.replace('_', ' ')}",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF6961),
                                fontSize = 15.sp
                            )
                            IconButton(onClick = { selectedAlert = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }

                        if (alert.payload.message.isNotBlank()) {
                            Text(text = "\"${alert.payload.message}\"", fontSize = 12.sp, color = Color.White)
                        }

                        val uLat = locationState.latitude
                        val uLon = locationState.longitude
                        val aLat = alert.payload.latitude
                        val aLon = alert.payload.longitude
                        if (uLat != null && uLon != null && aLat != null && aLon != null) {
                            val distM = EmergencyPoiManager.calculateDistanceMeters(uLat, uLon, aLat, aLon)
                            val bearing = EmergencyPoiManager.calculateBearingDegrees(uLat, uLon, aLat, aLon)
                            val dir = EmergencyPoiManager.bearingToCompassDirection(bearing)
                            Text(
                                text = "Distance to Victim: ${if (distM < 1000) "${distM.toInt()} m" else "%.2f km".format(distM / 1000.0)} ($dir • ${bearing.toInt()}°)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD60A)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (aLat != null && aLon != null) {
                                        val dest = NavigationDestination(
                                            id = alert.key,
                                            name = "SOS: ${alert.payload.type.name}",
                                            category = "SOS",
                                            latitude = aLat,
                                            longitude = aLon,
                                            notes = alert.payload.message
                                        )
                                        EmergencyNavigationManager.startNavigation(dest, uLat, uLon, context)
                                        trackingMode = MapTrackingMode.COMPASS_3D
                                        selectedAlert = null
                                    }
                                },
                                modifier = Modifier.weight(1.2f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("NAVIGATE OUT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { SosManager.acknowledge(alert.key) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (alert.acknowledgedByMe) "ACKED" else "ACK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 4. SELECTED PEER LOCATION CARD WITH "NAVIGATE TO PEER"
        AnimatedVisibility(
            visible = !navState.isNavigating && selectedPeer != null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val peer = selectedPeer
            if (peer != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1D2F)),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mesh Peer: ${peer.peerID.take(8)}…",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64D2FF),
                                fontSize = 15.sp
                            )
                            IconButton(onClick = { selectedPeer = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }

                        val uLat = locationState.latitude
                        val uLon = locationState.longitude
                        if (uLat != null && uLon != null) {
                            val distM = EmergencyPoiManager.calculateDistanceMeters(uLat, uLon, peer.latitude, peer.longitude)
                            val bearing = EmergencyPoiManager.calculateBearingDegrees(uLat, uLon, peer.latitude, peer.longitude)
                            val dir = EmergencyPoiManager.bearingToCompassDirection(bearing)
                            Text(
                                text = "Distance: ${if (distM < 1000) "${distM.toInt()} m" else "%.2f km".format(distM / 1000.0)} ($dir • ${bearing.toInt()}°)",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        Button(
                            onClick = {
                                val dest = NavigationDestination(
                                    id = peer.peerID,
                                    name = "Peer ${peer.peerID.take(8)}",
                                    category = "PEER",
                                    latitude = peer.latitude,
                                    longitude = peer.longitude
                                )
                                EmergencyNavigationManager.startNavigation(dest, uLat, uLon, context)
                                trackingMode = MapTrackingMode.COMPASS_3D
                                selectedPeer = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("NAVIGATE TO PEER", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // 5. CUSTOM MAP TAP WAYPOINT CARD WITH "START ROUTE"
        AnimatedVisibility(
            visible = !navState.isNavigating && customWaypoint != null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val waypoint = customWaypoint
            if (waypoint != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F25)),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF9E86FF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Custom Evacuation Waypoint",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD0BCFF),
                                fontSize = 15.sp
                            )
                            IconButton(onClick = { customWaypoint = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }

                        val uLat = locationState.latitude
                        val uLon = locationState.longitude
                        if (uLat != null && uLon != null) {
                            val distM = EmergencyPoiManager.calculateDistanceMeters(uLat, uLon, waypoint.latitude, waypoint.longitude)
                            val bearing = EmergencyPoiManager.calculateBearingDegrees(uLat, uLon, waypoint.latitude, waypoint.longitude)
                            val dir = EmergencyPoiManager.bearingToCompassDirection(bearing)
                            Text(
                                text = "Distance: ${if (distM < 1000) "${distM.toInt()} m" else "%.2f km".format(distM / 1000.0)} • Bearing: $dir (${bearing.toInt()}°)",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        Button(
                            onClick = {
                                val dest = NavigationDestination(
                                    id = "custom-${System.currentTimeMillis()}",
                                    name = "Custom Waypoint",
                                    category = "CUSTOM",
                                    latitude = waypoint.latitude,
                                    longitude = waypoint.longitude
                                )
                                EmergencyNavigationManager.startNavigation(dest, uLat, uLon, context)
                                trackingMode = MapTrackingMode.COMPASS_3D
                                customWaypoint = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("START ROUTE TO WAYPOINT", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // ==============================================================
    // SAFE LOCATIONS / EMERGENCY POI DIRECTORY BOTTOM SHEET
    // ==============================================================
    if (showSafeLocationsSheet) {
        val userLat = locationState.latitude
        val userLon = locationState.longitude
        val results = remember(searchQuery, selectedCategoryFilter, userLat, userLon) {
            EmergencyPoiManager.searchPois(searchQuery, selectedCategoryFilter, userLat, userLon)
        }

        ModalBottomSheet(
            onDismissRequest = { showSafeLocationsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxHeight(0.85f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Offline Safe Locations & Evacuation Hubs",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search hospitals, shelters, police, rescue…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                // Category filter chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(EmergencyCategory.values()) { category ->
                        FilterChip(
                            selected = selectedCategoryFilter == category,
                            onClick = { selectedCategoryFilter = category },
                            label = { Text(category.displayName, fontSize = 11.sp) }
                        )
                    }
                }

                // POI Results list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results) { item ->
                        val poi = item.poi
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSafeLocationsSheet = false
                                    selectedPoi = poi
                                    trackingMode = MapTrackingMode.FREE
                                    mapInstance?.animateCamera(
                                        CameraUpdateFactory.newLatLngZoom(LatLng(poi.latitude, poi.longitude), 14.5),
                                        800
                                    )
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = poi.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    if (item.distanceMeters != null) {
                                        Text(
                                            text = if (item.distanceMeters < 1000) "${item.distanceMeters.toInt()}m" else "%.1f km".format(item.distanceMeters / 1000.0),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = poi.category.displayName, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    Text(text = "•", fontSize = 11.sp)
                                    Text(text = poi.cityOrRegion, fontSize = 11.sp)
                                    if (item.compassDirection != null) {
                                        Text(text = "•", fontSize = 11.sp)
                                        Text(text = "${item.compassDirection} (${item.bearingDegrees?.toInt()}°)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Text(text = "Contact: ${poi.contact}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = poi.emergencyNotes, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                // Direct Start Route Button
                                Button(
                                    onClick = {
                                        showSafeLocationsSheet = false
                                        val dest = NavigationDestination(
                                            id = poi.id,
                                            name = poi.name,
                                            category = poi.category.name,
                                            latitude = poi.latitude,
                                            longitude = poi.longitude,
                                            cityOrRegion = poi.cityOrRegion,
                                            contact = poi.contact,
                                            notes = poi.emergencyNotes
                                        )
                                        EmergencyNavigationManager.startNavigation(dest, userLat, userLon, context)
                                        trackingMode = MapTrackingMode.COMPASS_3D
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("START ROUTE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==============================================================
    // OFFLINE MAP STORAGE INFO DIALOG
    // ==============================================================
    if (showMapInfoSheet) {
        val mapsDir = File(context.getExternalFilesDir(null), "maps")
        val routingDir = OfflineRoutingEngine.getRoutingDirectory(context)
        val installedTiles = OfflineRoutingEngine.getInstalledRoutingTiles(context)

        AlertDialog(
            onDismissRequest = { showMapInfoSheet = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Installed Offline Maps & Routing", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        Text("Vector Map Files (*.pmtiles):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        if (mapFiles.isEmpty()) {
                            Text(
                                text = "No .pmtiles files installed. Currently displaying low-detail country overview.",
                                fontSize = 11.sp,
                                color = Color(0xFFFFA000)
                            )
                        } else {
                            mapFiles.forEach { f ->
                                val sizeMb = f.length() / (1024 * 1024)
                                Text(
                                    text = "• ${f.name} ($sizeMb MB)",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                        }
                    }

                    item {
                        Text("Extract & Push Detailed Maps:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "# 1. Extract PMTiles for your area (e.g. Delhi NCR):\npmtiles extract https://r2-public.protomaps.com/protomaps-sample-datasets/protomaps-basemap-opensource-20230408.pmtiles delhi.pmtiles --bbox=76.8,28.4,77.5,28.9 --maxzoom=15\n\n# 2. Push to device via ADB:\nadb push delhi.pmtiles ${mapsDir.absolutePath}/",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text("Offline Routing Data (*.rd5):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        if (installedTiles.isEmpty()) {
                            Text(
                                text = "No BRouter *.rd5 routing segment files installed.",
                                fontSize = 11.sp,
                                color = Color(0xFFFFA000)
                            )
                        } else {
                            installedTiles.forEach { t ->
                                val sizeMb = t.fileSizeBytes / (1024 * 1024)
                                Text(
                                    text = "• ${t.fileName} ($sizeMb MB)",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                        }
                    }

                    item {
                        Text("Push Routing Tiles Command:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "# Push BRouter routing tiles (*.rd5) for offline navigation:\nadb push E75_N25.rd5 ${routingDir.absolutePath}/",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "All data is stored locally on device. Zero internet telemetry.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMapInfoSheet = false }) {
                    Text("CLOSE")
                }
            }
        )
    }
}

@Composable
private fun MapFallbackView(
    locationState: GpsLocationState,
    errorMessage: String?,
    onOpenDirectory: () -> Unit
) {
    val shortReason = errorMessage ?: "no map file installed, or map style failed to load"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1416))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFF0F6E7C).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = null,
                tint = Color(0xFF0F6E7C),
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Offline Emergency Navigation",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = Color.White
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Vector graphics acceleration is unavailable, but all offline GPS routing, turn-by-turn guidance, safe location directories, and compass bearings are active.",
            fontSize = 12.sp,
            color = Color(0xFF9E9E9E),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(14.dp))

        // Map Unavailable Error Notice Banner
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF3E1F1F),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC62828)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                Text(
                    text = "Map unavailable: $shortReason",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // GPS Readout card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1B2428),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C3E44)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GPS SATELLITE STATUS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFF0F6E7C),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = locationState.fixQuality.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (locationState.fixQuality) {
                            GpsFixQuality.GOOD -> Color(0xFF34C759)
                            GpsFixQuality.INACCURATE -> Color(0xFFFFD60A)
                            GpsFixQuality.STALE -> Color(0xFFFF9F0A)
                            else -> Color(0xFF8E8E93)
                        }
                    )
                }

                val lat = locationState.latitude
                val lon = locationState.longitude
                if (lat != null && lon != null) {
                    Text(
                        text = "${"%.5f".format(lat)}°, ${"%.5f".format(lon)}°",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        color = Color.White
                    )
                    Text(
                        text = "Accuracy: ±${locationState.accuracyM?.toInt() ?: 0}m • ${locationState.speedKmh?.let { "%.1f km/h".format(it) } ?: "Stationary"}",
                        fontSize = 12.sp,
                        color = Color(0xFFB0BEC5)
                    )
                } else {
                    Text(
                        text = when (locationState.fixQuality) {
                            GpsFixQuality.NO_PERMISSION -> "Location permission required. Please grant permission in settings."
                            GpsFixQuality.GPS_DISABLED -> "Location turned off in device settings. Please enable GPS."
                            else -> "Searching for GPS satellites…"
                        },
                        fontSize = 13.sp,
                        color = Color(0xFFFFA000)
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onOpenDirectory,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F6E7C)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Default.LocalHospital, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("OPEN SAFE LOCATIONS & EVACUATION HUBS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
