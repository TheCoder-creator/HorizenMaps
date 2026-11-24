package com.horizen.maps.car

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.*

data class PlaceResult(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)

data class RouteStep(
    val instruction: String,
    val road: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val latitude: Double,
    val longitude: Double,
    val maneuverType: String,
    val modifier: String
)

data class DrivingRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<Pair<Double, Double>>,
    val steps: List<RouteStep>,
    val summary: String
)

object NavigationEngine {
    private const val USER_AGENT = "HorizenMaps/1.0 (https://maps.horizensearch.com)"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    private const val OSRM = "https://router.project-osrm.org"

    fun searchPlaces(query: String): List<PlaceResult> {
        require(query.trim().length >= 3) { "Enter at least 3 characters." }
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = request("$NOMINATIM?q=$encoded&format=jsonv2&limit=6&addressdetails=1")
        val results = mutableListOf<PlaceResult>()
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            val address = item.optString("display_name")
            val name = item.optJSONObject("name")?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?: address.substringBefore(",")
            val lat = item.optString("lat").toDoubleOrNull() ?: continue
            val lon = item.optString("lon").toDoubleOrNull() ?: continue
            results.add(PlaceResult(name, address, lat, lon))
        }
        return results
    }

    fun drivingRoutes(originLat: Double, originLon: Double, destLat: Double, destLon: Double): List<DrivingRoute> {
        val coordinates = "$originLon,$originLat;$destLon,$destLat"
        val json = request("$OSRM/route/v1/driving/$coordinates?alternatives=true&steps=true&overview=full&geometries=geojson")
        if (json.optString("code") != "Ok") {
            throw IllegalStateException(json.optString("message", "No driving route was found."))
        }
        val routes = json.optJSONArray("routes") ?: JSONArray()
        return (0 until routes.length()).mapNotNull { index ->
            val route = routes.optJSONObject(index) ?: return@mapNotNull null
            val geometryJson = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: JSONArray()
            val geometry = (0 until geometryJson.length()).mapNotNull { point ->
                val pair = geometryJson.optJSONArray(point) ?: return@mapNotNull null
                if (pair.length() < 2) return@mapNotNull null
                Pair(pair.optDouble(1), pair.optDouble(0))
            }
            if (geometry.size < 2) return@mapNotNull null

            val steps = mutableListOf<RouteStep>()
            val legs = route.optJSONArray("legs") ?: JSONArray()
            for (legIndex in 0 until legs.length()) {
                val leg = legs.optJSONObject(legIndex) ?: continue
                val legSteps = leg.optJSONArray("steps") ?: JSONArray()
                for (stepIndex in 0 until legSteps.length()) {
                    val step = legSteps.optJSONObject(stepIndex) ?: continue
                    val maneuver = step.optJSONObject("maneuver") ?: JSONObject()
                    val location = maneuver.optJSONArray("location") ?: JSONArray()
                    val lon = location.optDouble(0, Double.NaN)
                    val lat = location.optDouble(1, Double.NaN)
                    val type = maneuver.optString("type", "continue")
                    val modifier = maneuver.optString("modifier", "")
                    val road = step.optString("name").ifBlank { "the road" }
                    val instruction = instructionFor(type, modifier, road)
                    if (lat.isFinite() && lon.isFinite()) {
                        steps.add(
                            RouteStep(
                                instruction,
                                road,
                                step.optDouble("distance"),
                                step.optDouble("duration"),
                                lat,
                                lon,
                                type,
                                modifier
                            )
                        )
                    }
                }
            }
            DrivingRoute(
                route.optDouble("distance"),
                route.optDouble("duration"),
                geometry,
                steps,
                route.optString("weight_name", "Driving route")
            )
        }
    }

    private fun instructionFor(type: String, modifier: String, road: String): String {
        val action = when (type) {
            "depart" -> "Start"
            "arrive" -> "Arrive"
            "turn" -> when (modifier) {
                "left" -> "Turn left"
                "right" -> "Turn right"
                "slight left" -> "Bear left"
                "slight right" -> "Bear right"
                "sharp left" -> "Turn sharp left"
                "sharp right" -> "Turn sharp right"
                "uturn" -> "Make a U-turn"
                else -> "Continue"
            }
            "roundabout", "rotary" -> "Enter the roundabout"
            "fork" -> if (modifier == "left") "Keep left" else "Keep right"
            "merge" -> "Merge"
            "on ramp" -> "Take the ramp"
            "off ramp" -> "Take the exit"
            "end of road" -> if (modifier == "left") "Turn left" else "Turn right"
            "continue" -> "Continue"
            "new name" -> "Continue"
            else -> "Continue"
        }
        return if (type == "arrive") "Arrive at your destination" else "$action on $road"
    }

    private fun request(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Navigation service returned HTTP $code.")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = Math.toRadians(bLat - aLat)
        val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 6_371_000.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    fun nearestPointIndex(points: List<Pair<Double, Double>>, latitude: Double, longitude: Double, fromIndex: Int): Pair<Int, Double> {
        if (points.isEmpty()) return Pair(0, Double.POSITIVE_INFINITY)
        val start = (fromIndex - 5).coerceAtLeast(0)
        val end = (fromIndex + 250).coerceAtMost(points.lastIndex)
        var bestIndex = start
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in start..end) {
            val point = points[i]
            val distance = distanceMeters(latitude, longitude, point.first, point.second)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        return Pair(bestIndex, bestDistance)
    }
}
