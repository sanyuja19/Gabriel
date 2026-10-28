package com.gabriel.android.location

import kotlin.math.*

enum class EmergencyCategory(val displayName: String) {
    ALL("All Emergency"),
    HOSPITAL("Hospital / Medical"),
    POLICE("Police / Security"),
    SHELTER("Disaster Shelter"),
    FIRE_STATION("Fire & Rescue"),
    MOUNTAIN_RESCUE("Mountain Rescue")
}

data class EmergencyPoi(
    val id: String,
    val name: String,
    val category: EmergencyCategory,
    val latitude: Double,
    val longitude: Double,
    val cityOrRegion: String,
    val contact: String,
    val emergencyNotes: String
)

data class PoiWithDistance(
    val poi: EmergencyPoi,
    val distanceMeters: Double?,
    val bearingDegrees: Double?,
    val compassDirection: String?
)

/**
 * Curated offline emergency POI database and search engine for India.
 * Zero internet required. Computes offline distance and compass bearing to each safe location.
 */
object EmergencyPoiManager {

    // Bundled unverified sample data - clearly marked per safety standards
    private val RAW_BUNDLED_POIS: List<EmergencyPoi> = listOf(
        // Himalayan & Mountain Trekking Rescue Gateway
        EmergencyPoi(
            id = "in-mr-01",
            name = "[UNVERIFIED sample data] Leh District Hospital",
            category = EmergencyCategory.MOUNTAIN_RESCUE,
            latitude = 34.1642,
            longitude = 77.5848,
            cityOrRegion = "Leh, Ladakh",
            contact = "01982-252014 / 112",
            emergencyNotes = "Sample facility - not verified safe on ground"
        ),
        EmergencyPoi(
            id = "in-mr-02",
            name = "Manali Civil Hospital & Trekker Medical Centre",
            category = EmergencyCategory.MOUNTAIN_RESCUE,
            latitude = 32.2432,
            longitude = 77.1892,
            cityOrRegion = "Manali, Himachal Pradesh",
            contact = "01902-252338 / 108",
            emergencyNotes = "Himalayan pass emergency & mountain rescue coordination"
        ),
        EmergencyPoi(
            id = "in-mr-03",
            name = "Indira Gandhi Medical College (IGMC) Shimla",
            category = EmergencyCategory.HOSPITAL,
            latitude = 31.1070,
            longitude = 77.1820,
            cityOrRegion = "Shimla, Himachal Pradesh",
            contact = "0177-2804251 / 108",
            emergencyNotes = "Regional apex trauma care for Himachal Pradesh"
        ),
        EmergencyPoi(
            id = "in-mr-04",
            name = "AIIMS Rishikesh Emergency & Trauma Centre",
            category = EmergencyCategory.HOSPITAL,
            latitude = 30.0759,
            longitude = 78.2878,
            cityOrRegion = "Rishikesh, Uttarakhand",
            contact = "0135-2462933 / 108",
            emergencyNotes = "Char Dham & Garhwal Himalayan disaster response hub"
        ),
        EmergencyPoi(
            id = "in-mr-05",
            name = "Sher-i-Kashmir Institute of Medical Sciences (SKIMS)",
            category = EmergencyCategory.HOSPITAL,
            latitude = 34.1350,
            longitude = 74.8016,
            cityOrRegion = "Srinagar, Jammu & Kashmir",
            contact = "0194-2401013 / 112",
            emergencyNotes = "Kashmir valley tertiary medical & disaster emergency"
        ),

        // National Capital & Northern Hubs
        EmergencyPoi(
            id = "in-hosp-01",
            name = "AIIMS New Delhi Apex Trauma Centre",
            category = EmergencyCategory.HOSPITAL,
            latitude = 28.5672,
            longitude = 77.2100,
            cityOrRegion = "New Delhi, Delhi NCR",
            contact = "011-26588500 / 102",
            emergencyNotes = "India national apex trauma and disaster hospital"
        ),
        EmergencyPoi(
            id = "in-hosp-02",
            name = "Safdarjung Hospital Emergency Block",
            category = EmergencyCategory.HOSPITAL,
            latitude = 28.5702,
            longitude = 77.2078,
            cityOrRegion = "New Delhi, Delhi NCR",
            contact = "011-26165060 / 102",
            emergencyNotes = "Major burns, trauma, and multi-casualty emergency"
        ),
        EmergencyPoi(
            id = "in-pol-01",
            name = "Delhi Police Headquarters (Central Dispatch)",
            category = EmergencyCategory.POLICE,
            latitude = 28.6315,
            longitude = 77.2410,
            cityOrRegion = "New Delhi, Delhi NCR",
            contact = "112 / 011-23490000",
            emergencyNotes = "State emergency operation centre & unified dispatch"
        ),
        EmergencyPoi(
            id = "in-ndrf-01",
            name = "NDRF 8th Battalion Disaster Response Base",
            category = EmergencyCategory.SHELTER,
            latitude = 28.6720,
            longitude = 77.4520,
            cityOrRegion = "Ghaziabad, Uttar Pradesh",
            contact = "0120-2766013 / 1070",
            emergencyNotes = "National Disaster Response Force NCR search & rescue"
        ),

        // Western & Coastal Emergency Hubs
        EmergencyPoi(
            id = "in-hosp-03",
            name = "KEM Hospital & Trauma Centre",
            category = EmergencyCategory.HOSPITAL,
            latitude = 18.9984,
            longitude = 72.8427,
            cityOrRegion = "Parel, Mumbai, Maharashtra",
            contact = "022-24107000 / 108",
            emergencyNotes = "Mumbai apex public trauma & emergency facility"
        ),
        EmergencyPoi(
            id = "in-ndrf-02",
            name = "NDRF 5th Battalion Pune Base",
            category = EmergencyCategory.SHELTER,
            latitude = 18.7500,
            longitude = 73.8500,
            cityOrRegion = "Sudumbare, Pune, Maharashtra",
            contact = "02114-247000 / 1070",
            emergencyNotes = "Flood, landslide, and coastal disaster rescue base"
        ),
        EmergencyPoi(
            id = "in-fire-01",
            name = "Mumbai Fire Brigade Headquarters",
            category = EmergencyCategory.FIRE_STATION,
            latitude = 18.9620,
            longitude = 72.8310,
            cityOrRegion = "Byculla, Mumbai, Maharashtra",
            contact = "101 / 022-23076111",
            emergencyNotes = "Urban rescue, collapse extraction, flood mitigation"
        ),

        // Southern Emergency Hubs
        EmergencyPoi(
            id = "in-hosp-04",
            name = "Rajiv Gandhi Government General Hospital",
            category = EmergencyCategory.HOSPITAL,
            latitude = 13.0827,
            longitude = 80.2796,
            cityOrRegion = "Chennai, Tamil Nadu",
            contact = "044-25305000 / 108",
            emergencyNotes = "Tamil Nadu apex multi-specialty trauma centre"
        ),
        EmergencyPoi(
            id = "in-shlt-01",
            name = "Red Cross Cyclone & Disaster Relief Centre",
            category = EmergencyCategory.SHELTER,
            latitude = 13.0620,
            longitude = 80.2520,
            cityOrRegion = "Egmore, Chennai, Tamil Nadu",
            contact = "044-28554425",
            emergencyNotes = "Emergency food, shelter, clean water & medical aid"
        ),
        EmergencyPoi(
            id = "in-hosp-05",
            name = "Victoria Hospital & Trauma Institute",
            category = EmergencyCategory.HOSPITAL,
            latitude = 12.9645,
            longitude = 77.5750,
            cityOrRegion = "Bengaluru, Karnataka",
            contact = "080-26701150 / 108",
            emergencyNotes = "Major trauma & emergency casualty care for Karnataka"
        ),
        EmergencyPoi(
            id = "in-ndrf-03",
            name = "NDRF 10th Battalion Regional Base",
            category = EmergencyCategory.SHELTER,
            latitude = 16.5062,
            longitude = 80.6480,
            cityOrRegion = "Guntur, Andhra Pradesh",
            contact = "0863-2293050 / 1070",
            emergencyNotes = "Coastal cyclone, flood & tsunami rescue operations"
        ),

        // Eastern & Northeastern Disaster Hubs
        EmergencyPoi(
            id = "in-hosp-06",
            name = "SSKM Hospital & Institute of Post Graduate Medical Education",
            category = EmergencyCategory.HOSPITAL,
            latitude = 22.5395,
            longitude = 88.3430,
            cityOrRegion = "Kolkata, West Bengal",
            contact = "033-22231589 / 102",
            emergencyNotes = "Eastern India premier trauma and emergency institution"
        ),
        EmergencyPoi(
            id = "in-ndrf-04",
            name = "NDRF 2nd Battalion Disaster Base",
            category = EmergencyCategory.SHELTER,
            latitude = 22.9868,
            longitude = 88.4345,
            cityOrRegion = "Haringhata, Nadia, West Bengal",
            contact = "033-25875032 / 1070",
            emergencyNotes = "Cyclone, flood, and earthquake response battalion"
        ),
        EmergencyPoi(
            id = "in-hosp-07",
            name = "Gauhati Medical College & Hospital (GMCH)",
            category = EmergencyCategory.HOSPITAL,
            latitude = 26.1557,
            longitude = 91.7725,
            cityOrRegion = "Guwahati, Assam",
            contact = "0361-2529457 / 108",
            emergencyNotes = "Northeast India apex trauma hospital and flood emergency"
        ),
        EmergencyPoi(
            id = "in-ndrf-05",
            name = "NDRF 1st Battalion Northeast Regional Base",
            category = EmergencyCategory.SHELTER,
            latitude = 26.1800,
            longitude = 91.7500,
            cityOrRegion = "Patgaon, Guwahati, Assam",
            contact = "0361-2840284 / 1070",
            emergencyNotes = "Specialized mountain, landslide, and riverine flood rescue"
        ),
        EmergencyPoi(
            id = "in-shlt-02",
            name = "Odisha State Disaster Mitigation Authority Relief Base",
            category = EmergencyCategory.SHELTER,
            latitude = 20.2961,
            longitude = 85.8245,
            cityOrRegion = "Bhubaneswar, Odisha",
            contact = "0674-2534177 / 1070",
            emergencyNotes = "Model cyclone shelter network coordination & relief"
        ),

        // Central & Western Hubs
        EmergencyPoi(
            id = "in-hosp-08",
            name = "Sawai Man Singh (SMS) Hospital & Trauma Centre",
            category = EmergencyCategory.HOSPITAL,
            latitude = 26.8973,
            longitude = 75.8164,
            cityOrRegion = "Jaipur, Rajasthan",
            contact = "0141-2560291 / 108",
            emergencyNotes = "Premier emergency facility for Rajasthan & desert border"
        ),
        EmergencyPoi(
            id = "in-hosp-09",
            name = "Ahmedabad Civil Hospital & Trauma Centre",
            category = EmergencyCategory.HOSPITAL,
            latitude = 23.0525,
            longitude = 72.5936,
            cityOrRegion = "Ahmedabad, Gujarat",
            contact = "079-22683721 / 108",
            emergencyNotes = "One of Asia's largest public trauma emergency centres"
        ),
        EmergencyPoi(
            id = "in-cg-01",
            name = "Indian Coast Guard District HQ 14",
            category = EmergencyCategory.MOUNTAIN_RESCUE,
            latitude = 11.6670,
            longitude = 92.7360,
            cityOrRegion = "Port Blair, Andaman & Nicobar",
            contact = "03192-232655 / 1554",
            emergencyNotes = "Maritime search and rescue, tsunami emergency response"
        )
    )

    /**
     * All bundled locations are explicitly tagged as [UNVERIFIED sample data]
     * so that users are never misled into trusting mock coordinates in a real emergency.
     */
    val BUNDLED_POIS: List<EmergencyPoi> = RAW_BUNDLED_POIS.map {
        val cleanName = it.name.removePrefix("[UNVERIFIED sample data] ")
        it.copy(name = "[UNVERIFIED sample data] $cleanName")
    }

    fun searchPois(
        query: String = "",
        category: EmergencyCategory = EmergencyCategory.ALL,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<PoiWithDistance> {
        val q = query.trim().lowercase()
        return BUNDLED_POIS
            .filter { poi ->
                val matchesCategory = category == EmergencyCategory.ALL || poi.category == category
                val matchesQuery = q.isEmpty() ||
                        poi.name.lowercase().contains(q) ||
                        poi.cityOrRegion.lowercase().contains(q) ||
                        poi.category.displayName.lowercase().contains(q) ||
                        poi.emergencyNotes.lowercase().contains(q)
                matchesCategory && matchesQuery
            }
            .map { poi ->
                val distance = if (userLat != null && userLon != null) {
                    calculateDistanceMeters(userLat, userLon, poi.latitude, poi.longitude)
                } else null

                val bearing = if (userLat != null && userLon != null) {
                    calculateBearingDegrees(userLat, userLon, poi.latitude, poi.longitude)
                } else null

                val dir = bearing?.let { bearingToCompassDirection(it) }

                PoiWithDistance(poi, distance, bearing, dir)
            }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        val degrees = Math.toDegrees(theta)
        return (degrees + 360.0) % 360.0
    }

    fun bearingToCompassDirection(degrees: Double): String {
        val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = ((degrees + 11.25) / 22.5).toInt() % 16
        return directions[index]
    }
}
