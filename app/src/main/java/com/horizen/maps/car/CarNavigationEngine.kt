package com.horizen.maps.car

import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.*

internal data class CarPoint(val lat: Double, val lon: Double, val label: String = "")
internal data class CarStep(
    val instruction: String,
    val road: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: String,
    val modifier: String,
    val lat: Double,
    val lon: Double
)
internal data class CarRoute(
    val coordinates: List<CarPoint>,
    val steps: List<CarStep>,
    val distanceMeters: Double,
    val durationSeconds: Double
)
internal data class CarPlace(val point: CarPoint, val category: String)

internal object CarNavigationEngine {
    private const val GEOCODER = "https://nominatim.openstreetmap.org/search"
    private const val ROUTER = "https://router.project-osrm.org/route/v1/driving"
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

    fun searchPlaces(query: String): List<CarPoint> {
        require(query.trim().length >= 3) { "Enter at least 3 characters." }
        val url = URL("$GEOCODER?format=jsonv2&limit=6&q=" + encode(query.trim()))
        val data = request(url)
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            CarPoint(lat, lon, item.optString("display_name"))
        }
    }

    fun routes(origin: CarPoint, destination: CarPoint, alternatives: Boolean = true): List<CarRoute> {
        val url = URL("$ROUTER/${origin.lon},${origin.lat};${destination.lon},${destination.lat}?overview=full&geometries=geojson&steps=true&alternatives=$alternatives")
        val root = JSONObject(requestText(url))
        if (root.optString("code") != "Ok") throw IllegalStateException("No driving route was found.")
        val list = root.optJSONArray("routes") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            val route = list.optJSONObject(i) ?: return@mapNotNull null
            val geometry = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: JSONArray()
            val points = (0 until geometry.length()).mapNotNull { j ->
                val pair = geometry.optJSONArray(j) ?: return@mapNotNull null
                if (pair.length() < 2) return@mapNotNull null
                CarPoint(pair.optDouble(1), pair.optDouble(0))
            }
            val steps = mutableListOf<CarStep>()
            val legs = route.optJSONArray("legs") ?: JSONArray()
            for (legIndex in 0 until legs.length()) {
                val leg = legs.optJSONObject(legIndex) ?: continue
                val legSteps = leg.optJSONArray("steps") ?: JSONArray()
                for (stepIndex in 0 until legSteps.length()) {
                    val step = legSteps.optJSONObject(stepIndex) ?: continue
                    val maneuver = step.optJSONObject("maneuver") ?: JSONObject()
                    val location = maneuver.optJSONArray("location") ?: JSONArray()
                    val type = maneuver.optString("type", "continue")
                    val modifier = maneuver.optString("modifier", "straight")
                    steps += CarStep(
                        instruction(type, modifier),
                        step.optString("name"),
                        step.optDouble("distance"),
                        step.optDouble("duration"),
                        type,
                        modifier,
                        location.optDouble(1),
                        location.optDouble(0)
                    )
                }
            }
            CarRoute(points, steps, route.optDouble("distance"), route.optDouble("duration"))
        }.filter { it.coordinates.size > 1 && it.steps.isNotEmpty() }
    }

    fun nearby(point: CarPoint): List<CarPlace> {
        val query = "[out:json][timeout:12];(node(around:1200,${point.lat},${point.lon})[amenity];node(around:1200,${point.lat},${point.lon})[shop];node(around:1200,${point.lat},${point.lon})[tourism];);out 30;"
        val connection = URL(OVERPASS).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 7000
            connection.readTimeout = 14000
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "HorizonMaps/1.0 (Android Auto)")
            connection.outputStream.use { it.write(("data=" + encode(query)).toByteArray()) }
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val elements = root.optJSONArray("elements") ?: JSONArray()
            return (0 until elements.length()).mapNotNull { i ->
                val item = elements.optJSONObject(i) ?: return@mapNotNull null
                val tags = item.optJSONObject("tags") ?: JSONObject()
                val lat = item.optDouble("lat", Double.NaN)
                val lon = item.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
                val category = tags.optString("amenity").ifBlank {
                    tags.optString("shop").ifBlank { tags.optString("tourism").ifBlank { "Place" } }
                }
                val name = tags.optString("name").ifBlank { category.replace('_', ' ') }
                CarPlace(CarPoint(lat, lon, name), category)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun speedLimit(point: CarPoint): Int? {
        val query = "[out:json][timeout:8];way(around:35,${point.lat},${point.lon})[highway][maxspeed];out tags geom;"
        val connection = URL(OVERPASS).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 9000
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "HorizonMaps/1.0 (Android Auto)")
            connection.outputStream.use { it.write(("data=" + encode(query)).toByteArray()) }
            val elements = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("elements") ?: JSONArray()
            var best: Int? = null
            var nearest = Double.MAX_VALUE
            for (i in 0 until elements.length()) {
                val way = elements.optJSONObject(i) ?: continue
                val raw = way.optJSONObject("tags")?.optString("maxspeed").orEmpty()
                val match = Regex("^(\\d+(?:\\.\\d+)?)\\s*(mph|km/?h|kph)?$", RegexOption.IGNORE_CASE).find(raw.substringBefore(';').trim()) ?: continue
                var value = match.groupValues[1].toDoubleOrNull() ?: continue
                val unit = match.groupValues[2].lowercase(Locale.US)
                if (unit.isBlank() || unit.startsWith("km") || unit == "kph") value *= 0.621371
                val geometry = way.optJSONArray("geometry") ?: continue
                for (j in 0 until geometry.length()) {
                    val p = geometry.optJSONObject(j) ?: continue
                    val d = distanceMeters(point.lat, point.lon, p.optDouble("lat"), p.optDouble("lon"))
                    if (d < nearest) {
                        nearest = d
                        best = value.roundToInt()
                    }
                }
            }
            return best?.takeIf { nearest <= 45.0 && it in 1..150 }
        } finally {
            connection.disconnect()
        }
    }

    fun distanceMeters(a: CarPoint, b: CarPoint) = distanceMeters(a.lat, a.lon, b.lat, b.lon)

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    fun instruction(type: String, modifier: String): String = when {
        type == "arrive" -> "You have arrived"
        type == "depart" -> "Start driving"
        type == "uturn" -> "Make a U-turn"
        type == "roundabout" || type == "rotary" -> "Take the roundabout"
        modifier.contains("left") && type == "merge" -> "Merge left"
        modifier.contains("right") && type == "merge" -> "Merge right"
        modifier.contains("left") -> "Turn left"
        modifier.contains("right") -> "Turn right"
        type == "merge" -> "Merge"
        type == "fork" -> "At the fork"
        type == "on ramp" -> "Take the ramp"
        type == "off ramp" -> "Take the exit"
        else -> "Continue straight"
    }

    private fun request(url: URL): JSONArray = JSONArray(requestText(url))

    private fun requestText(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "HorizenMaps/1.0 (https://maps.horizensearch.com)")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Map service returned HTTP $code.")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
