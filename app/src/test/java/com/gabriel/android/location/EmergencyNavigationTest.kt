package com.gabriel.android.location

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EmergencyNavigationTest {

    @Before
    fun setUp() {
        EmergencyNavigationManager.stopNavigation()
    }

    @Test
    fun testStartNavigationInitializesRouteState() {
        // User at Connaught Place New Delhi (28.6304, 77.2177)
        // Destination at AIIMS Trauma Centre (28.5672, 77.2100) ~7 km south
        val dest = NavigationDestination(
            id = "test-aiims",
            name = "AIIMS Trauma Centre",
            category = "HOSPITAL",
            latitude = 28.5672,
            longitude = 77.2100
        )

        EmergencyNavigationManager.startNavigation(dest, 28.6304, 77.2177)
        val state = EmergencyNavigationManager.state.value

        assertTrue(state.isNavigating)
        assertEquals(dest, state.destination)
        assertTrue("Distance should be around 7000m", state.remainingDistanceMeters in 6500.0..7500.0)
        assertTrue("Walking time should be roughly 80-100 mins", state.estimatedWalkingMinutes in 80..110)
        assertFalse(state.hasArrived)
        assertTrue("Waypoints should be generated", state.routeWaypoints.size >= 8)
        assertTrue("ETA string should be non-empty", state.etaFormatted.isNotBlank())
    }

    @Test
    fun testArrivalThresholdTriggersArrivedState() {
        val dest = NavigationDestination(
            id = "test-dest",
            name = "Local Relief Camp",
            category = "SHELTER",
            latitude = 28.5672,
            longitude = 77.2100
        )

        // User starts 500m away
        EmergencyNavigationManager.startNavigation(dest, 28.5710, 77.2100)
        assertFalse(EmergencyNavigationManager.state.value.hasArrived)

        // User walks to within 15 meters
        EmergencyNavigationManager.updateUserLocation(28.5673, 77.2101)
        val arrivedState = EmergencyNavigationManager.state.value

        assertTrue("Should detect arrival within 30m", arrivedState.hasArrived)
        assertEquals(ManeuverType.ARRIVED, arrivedState.maneuverType)
        assertTrue("Instruction should confirm arrival", arrivedState.turnInstruction.contains("Arrived"))
    }

    @Test
    fun testTurnManeuverCalculations() {
        // Dest is due North (bearing ~0°)
        val dest = NavigationDestination(
            id = "north-poi",
            name = "North Base",
            category = "RESCUE",
            latitude = 28.6000,
            longitude = 77.2000
        )

        EmergencyNavigationManager.startNavigation(dest, 28.5000, 77.2000)

        // If user is heading North (0°), relative turn is 0° -> STRAIGHT
        EmergencyNavigationManager.updateUserLocation(28.5000, 77.2000, userBearing = 0.0f)
        assertEquals(ManeuverType.STRAIGHT, EmergencyNavigationManager.state.value.maneuverType)

        // If user is heading West (270°), destination is North (0°), relative is 90° -> RIGHT
        EmergencyNavigationManager.updateUserLocation(28.5000, 77.2000, userBearing = 270.0f)
        assertEquals(ManeuverType.RIGHT, EmergencyNavigationManager.state.value.maneuverType)

        // If user is heading South (180°), destination is North (0°), relative is 180° -> U_TURN
        EmergencyNavigationManager.updateUserLocation(28.5000, 77.2000, userBearing = 180.0f)
        assertEquals(ManeuverType.U_TURN, EmergencyNavigationManager.state.value.maneuverType)

        // If user is heading East (90°), destination is North (0°), relative is 270° -> LEFT
        EmergencyNavigationManager.updateUserLocation(28.5000, 77.2000, userBearing = 90.0f)
        assertEquals(ManeuverType.LEFT, EmergencyNavigationManager.state.value.maneuverType)
    }

    @Test
    fun testEvacuateToNearestSafePoi() {
        // User in Shimla Himachal Pradesh (31.1048, 77.1734)
        val success = EmergencyNavigationManager.startNavigationToNearestSafePoi(31.1048, 77.1734)
        assertTrue("Should successfully find nearest safe facility in Shimla", success)

        val state = EmergencyNavigationManager.state.value
        assertTrue(state.isNavigating)
        assertNotNull(state.destination)
        assertTrue(state.destination?.name?.contains("Shimla") == true || state.destination?.name?.contains("Manali") == true)
    }

    @Test
    fun testStopNavigationResetsState() {
        val dest = NavigationDestination(
            id = "test-stop",
            name = "Test Site",
            category = "POLICE",
            latitude = 28.5672,
            longitude = 77.2100
        )
        EmergencyNavigationManager.startNavigation(dest, 28.6000, 77.2100)
        assertTrue(EmergencyNavigationManager.state.value.isNavigating)

        EmergencyNavigationManager.stopNavigation()
        assertFalse(EmergencyNavigationManager.state.value.isNavigating)
        assertNull(EmergencyNavigationManager.state.value.destination)
    }
}
