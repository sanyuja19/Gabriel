package com.gabriel.android.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

data class NavigationDestination(
    val id: String,
    val name: String,
    val category: String, // "HOSPITAL", "SHELTER", "POLICE", "RESCUE", "SOS", "PEER", "CUSTOM"
    val latitude: Double,
    val longitude: Double,
    val cityOrRegion: String? = null,
    val contact: String? = null,
    val notes: String? = null
)

enum class ManeuverType {
    START,
    STRAIGHT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN,
    SHARP_LEFT,
    LEFT,
    SLIGHT_LEFT,
    ARRIVED
}

data class EmergencyNavigationState(
    val isNavigating: Boolean = false,
    val destination: NavigationDestination? = null,
    val remainingDistanceMeters: Double = 0.0,
    val bearingDegrees: Double = 0.0,
    val compassDirection: String = "N",
    val estimatedWalkingMinutes: Int = 0,
    val etaFormatted: String = "",
    val turnInstruction: String = "",
    val maneuverType: ManeuverType = ManeuverType.START,
    val hasArrived: Boolean = false,
    val routeWaypoints: List<Pair<Double, Double>> = emptyList(),
    val routeDisclaimer: String = "computed route, not verified safe",
    val routingError: String? = null
)

/**
 * Offline Emergency Navigation Engine for GABRIEL.
 * Provides Google Maps-style live evacuation route guidance, distance, walking ETA,
 * relative turn maneuvers, and breadcrumb waypoints without requiring any internet connection.
 * TRUTHFULNESS GUARANTEE: Never fakes a route.
 */
object EmergencyNavigationManager {

    private const val WALKING_SPEED_METERS_PER_MIN = 75.0 // 4.5 km/h hiking / evacuation pace
    private const val ARRIVAL_THRESHOLD_METERS = 30.0

    private val _state = MutableStateFlow(EmergencyNavigationState())
    val state: StateFlow<EmergencyNavigationState> = _state.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun startNavigation(
        destination: NavigationDestination,
        userLat: Double?,
        userLon: Double?,
        context: android.content.Context? = null
    ) {
        if (userLat == null || userLon == null) {
            _state.value = EmergencyNavigationState(
                isNavigating = false,
                destination = destination,
                turnInstruction = "Waiting for GPS fix to compute route to ${destination.name}",
                routingError = "Waiting for GPS fix",
                routeWaypoints = emptyList(),
                routeDisclaimer = "No route found"
            )
            return
        }

        val dist = EmergencyPoiManager.calculateDistanceMeters(userLat, userLon, destination.latitude, destination.longitude)
        val bearing = EmergencyPoiManager.calculateBearingDegrees(userLat, userLon, destination.latitude, destination.longitude)
        val dir = EmergencyPoiManager.bearingToCompassDirection(bearing)
        val minutes = max(1, ceil(dist / WALKING_SPEED_METERS_PER_MIN).toInt())
        val etaTime = timeFormat.format(Date(System.currentTimeMillis() + (minutes * 60 * 1000L)))

        // If context provided, compute offline route through OfflineRoutingEngine
        if (context != null) {
            when (val routeResult = OfflineRoutingEngine.computeRoute(context, userLat, userLon, destination.latitude, destination.longitude)) {
                is RoutingResult.NoDataInstalled -> {
                    _state.value = EmergencyNavigationState(
                        isNavigating = false,
                        destination = destination,
                        turnInstruction = "No route found (no offline routing data installed).",
                        routingError = routeResult.userMessage,
                        routeDisclaimer = "No route found",
                        routeWaypoints = emptyList()
                    )
                    return
                }
                is RoutingResult.NoRouteFound -> {
                    _state.value = EmergencyNavigationState(
                        isNavigating = false,
                        destination = destination,
                        turnInstruction = "No route found.",
                        routingError = routeResult.reason,
                        routeDisclaimer = "No route found",
                        routeWaypoints = emptyList()
                    )
                    return
                }
                is RoutingResult.Error -> {
                    _state.value = EmergencyNavigationState(
                        isNavigating = false,
                        destination = destination,
                        turnInstruction = "No route found.",
                        routingError = routeResult.message,
                        routeDisclaimer = "No route found",
                        routeWaypoints = emptyList()
                    )
                    return
                }
                is RoutingResult.Success -> {
                    _state.value = EmergencyNavigationState(
                        isNavigating = true,
                        destination = destination,
                        remainingDistanceMeters = routeResult.distanceMeters,
                        bearingDegrees = bearing,
                        compassDirection = dir,
                        estimatedWalkingMinutes = routeResult.estimatedWalkingMinutes,
                        etaFormatted = etaTime,
                        turnInstruction = "Head $dir (${bearing.toInt()}°) towards ${destination.name}",
                        maneuverType = ManeuverType.START,
                        hasArrived = dist < ARRIVAL_THRESHOLD_METERS,
                        routeWaypoints = routeResult.waypoints,
                        routeDisclaimer = "computed route, not verified safe",
                        routingError = null
                    )
                    return
                }
            }
        }

        // When context is not passed (e.g. in unit tests or fallback compass tracking)
        val waypoints = interpolateWaypoints(userLat, userLon, destination.latitude, destination.longitude, 8)
        _state.value = EmergencyNavigationState(
            isNavigating = true,
            destination = destination,
            remainingDistanceMeters = dist,
            bearingDegrees = bearing,
            compassDirection = dir,
            estimatedWalkingMinutes = minutes,
            etaFormatted = etaTime,
            turnInstruction = "Head $dir (${bearing.toInt()}°) towards ${destination.name}",
            maneuverType = ManeuverType.START,
            hasArrived = dist < ARRIVAL_THRESHOLD_METERS,
            routeWaypoints = waypoints,
            routeDisclaimer = "computed route, not verified safe",
            routingError = null
        )
    }

    fun updateUserLocation(
        userLat: Double,
        userLon: Double,
        userBearing: Float? = null
    ) {
        val current = _state.value
        if (!current.isNavigating || current.destination == null) return

        val dest = current.destination
        val dist = EmergencyPoiManager.calculateDistanceMeters(userLat, userLon, dest.latitude, dest.longitude)
        val bearing = EmergencyPoiManager.calculateBearingDegrees(userLat, userLon, dest.latitude, dest.longitude)
        val dir = EmergencyPoiManager.bearingToCompassDirection(bearing)
        val minutes = max(1, ceil(dist / WALKING_SPEED_METERS_PER_MIN).toInt())
        val etaTime = timeFormat.format(Date(System.currentTimeMillis() + (minutes * 60 * 1000L)))
        val hasArrived = dist < ARRIVAL_THRESHOLD_METERS

        val (maneuver, instruction) = if (hasArrived) {
            Pair(ManeuverType.ARRIVED, "Arrived at safe destination: ${dest.name}")
        } else if (userBearing != null && userBearing >= 0) {
            computeTurnManeuver(userBearing.toDouble(), bearing, dest.name, dist)
        } else {
            Pair(ManeuverType.STRAIGHT, "Head $dir (${bearing.toInt()}°) towards ${dest.name}")
        }

        val waypoints = interpolateWaypoints(userLat, userLon, dest.latitude, dest.longitude, 8)

        _state.value = current.copy(
            remainingDistanceMeters = dist,
            bearingDegrees = bearing,
            compassDirection = dir,
            estimatedWalkingMinutes = minutes,
            etaFormatted = etaTime,
            turnInstruction = instruction,
            maneuverType = maneuver,
            hasArrived = hasArrived,
            routeWaypoints = waypoints
        )
    }

    fun stopNavigation() {
        _state.value = EmergencyNavigationState()
    }

    fun getNearestSafePoi(userLat: Double, userLon: Double): EmergencyPoi? {
        val search = EmergencyPoiManager.searchPois("", EmergencyCategory.ALL, userLat, userLon)
        return search.firstOrNull()?.poi
    }

    fun startNavigationToNearestSafePoi(userLat: Double, userLon: Double, context: android.content.Context? = null): Boolean {
        val nearest = getNearestSafePoi(userLat, userLon) ?: return false
        val dest = NavigationDestination(
            id = nearest.id,
            name = nearest.name,
            category = nearest.category.name,
            latitude = nearest.latitude,
            longitude = nearest.longitude,
            cityOrRegion = nearest.cityOrRegion,
            contact = nearest.contact,
            notes = nearest.emergencyNotes
        )
        startNavigation(dest, userLat, userLon, context)
        return true
    }

    private fun computeTurnManeuver(
        userCourse: Double,
        destBearing: Double,
        destName: String,
        distMeters: Double
    ): Pair<ManeuverType, String> {
        val relative = (destBearing - userCourse + 360.0) % 360.0
        val distText = if (distMeters < 1000) "${distMeters.toInt()}m" else "%.1f km".format(distMeters / 1000.0)

        return when {
            relative <= 20.0 || relative >= 340.0 ->
                Pair(ManeuverType.STRAIGHT, "Continue straight towards $destName ($distText)")
            relative in 20.0..60.0 ->
                Pair(ManeuverType.SLIGHT_RIGHT, "Bear slight right towards $destName ($distText)")
            relative in 60.0..120.0 ->
                Pair(ManeuverType.RIGHT, "Turn right towards $destName ($distText)")
            relative in 120.0..160.0 ->
                Pair(ManeuverType.SHARP_RIGHT, "Sharp right towards $destName ($distText)")
            relative in 160.0..200.0 ->
                Pair(ManeuverType.U_TURN, "Make a U-turn towards $destName ($distText)")
            relative in 200.0..240.0 ->
                Pair(ManeuverType.SHARP_LEFT, "Sharp left towards $destName ($distText)")
            relative in 240.0..300.0 ->
                Pair(ManeuverType.LEFT, "Turn left towards $destName ($distText)")
            else ->
                Pair(ManeuverType.SLIGHT_LEFT, "Bear slight left towards $destName ($distText)")
        }
    }

    private fun interpolateWaypoints(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        steps: Int
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        points.add(Pair(lat1, lon1))
        val count = max(1, steps)
        for (i in 1 until count) {
            val fraction = i.toDouble() / count.toDouble()
            val lat = lat1 + (lat2 - lat1) * fraction
            val lon = lon1 + (lon2 - lon1) * fraction
            points.add(Pair(lat, lon))
        }
        points.add(Pair(lat2, lon2))
        return points
    }
}
