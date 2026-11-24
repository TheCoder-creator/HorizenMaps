package com.horizen.maps.car

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

data class MapPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: String,
    val maneuverModifier: String,
    val location: Pair<Double, Double>
)

data class DrivingRoute(
    val coordinates: List<Pair<Double, Double>>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

object NavigationCore {
    private const val GEOCODER = "https://nominatim.openstreetmap.org/search"
    private const val ROUTER = "https://router.project-osrm.org/route/v1/driving/"
    private const val USER_AGENT = "HorizenMaps/1.0 (+https://maps.horizensearch.com/)"

    fun searchPlaces(query: String): List<MapPlace> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = request("$GEOCODER?format=jsonv2&limit=6&q=$encoded")
        val array = org.json.JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            MapPlace(item.optString("display_name", "Place"), lat, lon)
        }
    }

    fun drivingRoutes(origin: MapPlace, destination: MapPlace): List<DrivingRoute> {
        val coordinates = "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}"
        val url = ROUTER + coordinates +
            "?overview=full&geometries=geojson&steps=true&alternatives=true"
        val root = JSONObject(request(url))
        if (root.optString("code") != "Ok") {
            throw IllegalStateException(root.optString("message", "No driving route was found"))
        }
        val routes = root.optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { index ->
            val route = routes.optJSONObject(index) ?: return@mapNotNull null
            val geometry = route.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: return@mapNotNull null
            val points = (0 until geometry.length()).mapNotNull { pointIndex ->
                val point = geometry.optJSONArray(pointIndex) ?: return@mapNotNull null
                if (point.length() < 2) return@mapNotNull null
                Pair(point.optDouble(1), point.optDouble(0))
            }
            val steps = mutableListOf<RouteStep>()
            val legs = route.optJSONArray("legs")
            if (legs != null) {
                for (legIndex in 0 until legs.length()) {
                    val leg = legs.optJSONObject(legIndex) ?: continue
                    val legSteps = leg.optJSONArray("steps") ?: continue
                    for (stepIndex in 0 until legSteps.length()) {
                        val step = legSteps.optJSONObject(stepIndex) ?: continue
                        val maneuver = step.optJSONObject("maneuver") ?: JSONObject()
                        val location = maneuver.optJSONArray("location")
                        val locationPair = if (location != null && location.length() >= 2) {
                            Pair(location.optDouble(1), location.optDouble(0))
                        } else Pair(0.0, 0.0)
                        val type = maneuver.optString("type", "continue")
                        val modifier = maneuver.optString("modifier", "")
                        steps += RouteStep(
                            instruction = instruction(type, modifier, step.optString("name", "")),
                            distanceMeters = step.optDouble("distance", 0.0),
                            durationSeconds = step.optDouble("duration", 0.0),
                            maneuverType = type,
                            maneuverModifier = modifier,
                            location = locationPair
                        )
                    }
                }
            }
            DrivingRoute(
                coordinates = points,
                steps = steps,
                distanceMeters = route.optDouble("distance", 0.0),
                durationSeconds = route.optDouble("duration", 0.0)
            )
        }.filter { it.coordinates.size >= 2 }
    }

    fun nearestRoutePoint(location: Pair<Double, Double>, route: DrivingRoute, startAt: Int = 0): Int {
        var bestIndex = startAt.coerceIn(0, (route.coordinates.size - 1).coerceAtLeast(0))
        var bestDistance = Double.MAX_VALUE
        for (index in bestIndex until route.coordinates.size) {
            val distance = distanceMeters(location, route.coordinates[index])
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    fun distanceToRouteMeters(location: Pair<Double, Double>, route: DrivingRoute, startAt: Int = 0): Double {
        val index = nearestRoutePoint(location, route, startAt)
        return distanceMeters(location, route.coordinates[index])
    }

    fun remainingDistanceMeters(route: DrivingRoute, progressIndex: Int): Double {
        if (route.coordinates.isEmpty()) return route.distanceMeters
        val index = progressIndex.coerceIn(0, route.coordinates.lastIndex)
        var remaining = 0.0
        for (i in index until route.coordinates.lastIndex) {
            remaining += distanceMeters(route.coordinates[i], route.coordinates[i + 1])
        }
        return remaining
    }

    private fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val radius = 6371000.0
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return radius * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun instruction(type: String, modifier: String, road: String): String {
        val direction = when (modifier) {
            "left", "slight left", "sharp left" -> "left"
            "right", "slight right", "sharp right" -> "right"
            "straight" -> "straight"
            "uturn" -> "make a U-turn"
            else -> ""
        }
        val action = when (type) {
            "depart" -> "Head out"
            "arrive" -> "Arrive at your destination"
            "turn" -> if (direction.isEmpty()) "Continue" else "Turn $direction"
            "continue" -> "Continue"
            "merge" -> "Merge"
            "on ramp" -> "Take the ramp"
            "off ramp" -> "Take the exit"
            "fork" -> if (direction.isEmpty()) "At the fork, continue" else "At the fork, keep $direction"
            "roundabout", "rotary" -> "Enter the roundabout"
            "new name" -> "Continue"
            "end of road" -> if (direction.isEmpty()) "At the end of the road, continue" else "At the end of the road, turn $direction"
            else -> "Continue"
        }
        return if (road.isBlank() || type == "arrive") action else "$action onto $road"
    }

    private fun request(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Map service returned HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
