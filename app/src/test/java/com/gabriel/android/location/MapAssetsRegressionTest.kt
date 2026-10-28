package com.gabriel.android.location

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Regression test verifying that offline map GeoJSON assets are valid FeatureCollections
 * and never GeometryCollections, preventing MapLibre Native C++ geojson-vt SIGSEGV aborts.
 */
@RunWith(RobolectricTestRunner::class)
class MapAssetsRegressionTest {

    private fun getAssetFile(name: String): File {
        val candidates = listOf(
            File("app/src/main/assets/$name"),
            File("src/main/assets/$name"),
            File("../app/src/main/assets/$name")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find asset file $name in candidates: $candidates")
    }

    @Test
    fun testWorldLandGeoJsonIsFeatureCollectionNotGeometryCollection() {
        val file = getAssetFile("world_land.geojson")
        val content = file.readText()
        val json = JSONObject(content)

        // Root must be FeatureCollection for MapLibre Native geojson-vt
        val type = json.getString("type")
        assertNotEquals("GeometryCollection triggers MapLibre C++ native SIGSEGV", "GeometryCollection", type)
        assertEquals("FeatureCollection", type)

        val features = json.getJSONArray("features")
        assertTrue("world_land features must not be empty", features.length() > 0)

        for (i in 0 until minOf(features.length(), 10)) {
            val feature = features.getJSONObject(i)
            assertEquals("Feature", feature.getString("type"))
            assertTrue(feature.has("geometry"))
            val geom = feature.getJSONObject("geometry")
            val geomType = geom.getString("type")
            assertTrue("Geometry must be Polygon or MultiPolygon", geomType == "Polygon" || geomType == "MultiPolygon")
        }
    }

    @Test
    fun testWorldBordersGeoJsonIsFeatureCollectionNotGeometryCollection() {
        val file = getAssetFile("world_borders.geojson")
        val content = file.readText()
        val json = JSONObject(content)

        val type = json.getString("type")
        assertNotEquals("GeometryCollection triggers MapLibre C++ native SIGSEGV", "GeometryCollection", type)
        assertEquals("FeatureCollection", type)

        val features = json.getJSONArray("features")
        assertTrue("world_borders features must not be empty", features.length() > 0)

        for (i in 0 until minOf(features.length(), 10)) {
            val feature = features.getJSONObject(i)
            assertEquals("Feature", feature.getString("type"))
            assertTrue(feature.has("geometry"))
        }
    }

    @Test
    fun testOfflineBasemapStyleIsValidJson() {
        val file = getAssetFile("offline_basemap_style.json")
        val content = file.readText()
        val json = JSONObject(content)

        assertEquals("MapLibre style version must be 8", 8, json.getInt("version"))
        assertTrue("Must contain sources", json.has("sources"))
        assertTrue("Must contain layers", json.has("layers"))

        val sources = json.getJSONObject("sources")
        assertTrue("Must contain google_street source", sources.has("google_street"))
        val googleStreet = sources.getJSONObject("google_street")
        assertEquals("google_street must be raster type", "raster", googleStreet.getString("type"))
        val tiles = googleStreet.getJSONArray("tiles")
        assertTrue("google_street must contain tile mirrors", tiles.length() > 0)
        for (i in 0 until tiles.length()) {
            val url = tiles.getString(i)
            assertTrue("Tile URL must point to google maps tile service", url.contains("google.com/vt/lyrs=m"))
        }
    }

    @Test
    fun testGeoJsonAssetsHaveNoByteOrderMark() {
        val files = listOf("world_land.geojson", "world_borders.geojson", "offline_basemap_style.json")
        for (name in files) {
            val file = getAssetFile(name)
            val bytes = file.readBytes()
            assertTrue("File $name must not be empty", bytes.isNotEmpty())
            val hasBom = bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            org.junit.Assert.assertFalse("File $name must NOT contain UTF-8 Byte Order Mark (0xEF, 0xBB, 0xBF)", hasBom)
        }
    }
}
