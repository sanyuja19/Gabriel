package com.gabriel.android.location

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GabrielLocationManagerTest {

    @Test
    fun testStartGpsUpdatesDoesNotRecursivelyOverflowWhenPermissionsGranted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val manager = GabrielLocationManager.getInstance(app)
        // Must not throw java.lang.StackOverflowError
        manager.startGpsUpdates()
        manager.checkPermissionsAndGps()
        assertNotNull(manager.state.value)
    }

    @Test
    fun testStartGpsUpdatesDoesNotRecursivelyOverflowWhenPermissionsMissing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val manager = GabrielLocationManager.getInstance(app)
        // Must not throw java.lang.StackOverflowError
        manager.startGpsUpdates()
        manager.checkPermissionsAndGps()
        assertNotNull(manager.state.value)
    }

    @Test
    fun testQualityNoPermission() {
        val q = GabrielLocationManager.calculateQuality(
            hasPermission = false,
            isGpsEnabled = true,
            location = null,
            nowMs = 100_000L
        )
        assertEquals(GpsFixQuality.NO_PERMISSION, q)
    }

    @Test
    fun testQualityGpsDisabled() {
        val q = GabrielLocationManager.calculateQuality(
            hasPermission = true,
            isGpsEnabled = false,
            location = null,
            nowMs = 100_000L
        )
        assertEquals(GpsFixQuality.GPS_DISABLED, q)
    }

    @Test
    fun testQualityNoFix() {
        val q = GabrielLocationManager.calculateQuality(
            hasPermission = true,
            isGpsEnabled = true,
            location = null,
            nowMs = 100_000L
        )
        assertEquals(GpsFixQuality.NO_FIX, q)
    }

    @Test
    fun testQualityStaleFix() {
        val loc = mock(Location::class.java)
        `when`(loc.time).thenReturn(10_000L) // > 2 min ago
        val q = GabrielLocationManager.calculateQuality(
            hasPermission = true,
            isGpsEnabled = true,
            location = loc,
            nowMs = 200_000L // age = 190s > 120s
        )
        assertEquals(GpsFixQuality.STALE, q)
    }

    @Test
    fun testQualityInaccurateFix() {
        val loc = mock(Location::class.java)
        `when`(loc.time).thenReturn(100_000L)
        `when`(loc.hasAccuracy()).thenReturn(true)
        `when`(loc.accuracy).thenReturn(65.0f) // > 50m
        val q = GabrielLocationManager.calculateQuality(
            hasPermission = true,
            isGpsEnabled = true,
            location = loc,
            nowMs = 110_000L // age = 10s
        )
        assertEquals(GpsFixQuality.INACCURATE, q)
    }

    @Test
    fun testQualityGoodFix() {
        val loc = mock(Location::class.java)
        `when`(loc.time).thenReturn(100_000L)
        `when`(loc.hasAccuracy()).thenReturn(true)
        `when`(loc.accuracy).thenReturn(12.0f) // <= 50m
        val q = GabrielLocationManager.calculateQuality(
            hasPermission = true,
            isGpsEnabled = true,
            location = loc,
            nowMs = 115_000L // age = 15s <= 120s
        )
        assertEquals(GpsFixQuality.GOOD, q)
    }
}

