package com.horizen.maps.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.validation.HostValidator
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

private const val TAG = "HorizenMapsCar"
private const val TILE_RETRY_MS = 20_000L

class HorizonCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(sessionInfo: SessionInfo): Session = HorizonCarSession()
}

class HorizonCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen =
        HorizonCarScreen(carContext).also { it.handleNavigationIntent(intent) }
}

private enum class CarScreenMode { SEARCH, PLACES, ROUTES, NAVIGATION }

class HorizonCarScreen(carContext: androidx.car.app.CarContext) :
    Screen(carContext), SurfaceCallback {

    private var mapSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var centerLat = 37.3382
    private var centerLon = -121.8863
    private val zoom = 15
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newFixedThreadPool(2)
    private val tileExecutor = Executors.newFixedThreadPool(3)
    private val tileCache = ConcurrentHashMap<String, Bitmap>()
    private val loadingTiles = ConcurrentHashMap.newKeySet<String>()
    private val failedTiles = ConcurrentHashMap<String, Long>()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint().apply { color = Color.rgb(24, 29, 36) }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 126, 255)
        style = Paint.Style.FILL
    }
    private val markerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 126, 255)
        style = Paint.Style.STROKE
        strokeWidth = 13f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 27f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val markerPath = Path()
    private var tileError: String? = null
    private var renderError: String? = null
    private var mode = CarScreenMode.SEARCH
    private var statusMessage: String? = null
    private var searching = false
    private var places: List<MapPlace> = emptyList()
    private var routes: List<DrivingRoute> = emptyList()
    private var selectedPlace: MapPlace? = null
    private var selectedRoute: DrivingRoute? = null
    private var currentLocation: Location? = null
    private var stepIndex = 0
    private var offRouteCount = 0
    private var lastRerouteAt = 0L
    private var rerouting = false
    private var navigationActive = false
    private var userPanned = false
    private val locationManager =
        carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val navigationManager = carContext.getCarService(NavigationManager::class.java)
    private val locationListener = LocationListener { location -> onLocationChanged(location) }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        updateLastKnownLocation()
        startLocationUpdates()
    }

    fun handleNavigationIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW &&
            intent.action != "android.intent.action.NAVIGATE" &&
            intent.action != "androidx.car.app.action.NAVIGATE"
        ) return

        val query = data.getQueryParameter("q") ?: data.getQueryParameter("query")
        val coordinateText = data.schemeSpecificPart.substringBefore("?")
        val parts = coordinateText.split(",")
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                chooseDestination(MapPlace("Destination", lat, lon))
                return
            }
        }
        if (!query.isNullOrBlank()) searchDestination(query)
    }

    override fun onGetTemplate(): Template = when (mode) {
        CarScreenMode.SEARCH -> searchTemplate()
        CarScreenMode.PLACES -> placesTemplate()
        CarScreenMode.ROUTES -> routesTemplate()
        CarScreenMode.NAVIGATION -> navigationTemplate()
    }

    private fun searchTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                searchDestination(searchText)
            }
        })
            .setSearchHint("Search for a destination")
            .setShowKeyboardByDefault(true)
            .build()

    private fun placesTemplate(): Template {
        val builder = ItemList.Builder()
        places.forEach { place ->
            builder.addItem(
                Row.Builder()
                    .setTitle(place.name)
                    .addText("${place.latitude}, ${place.longitude}")
                    .setOnClickListener { chooseDestination(place) }
                    .build()
            )
        }
        if (places.isEmpty()) {
            builder.addItem(Row.Builder().setTitle(statusMessage ?: "No places found").build())
        }
        return ListTemplate.Builder()
            .setSingleList(builder.build())
            .setHeader(
                Header.Builder()
                    .setTitle("Choose destination")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun routesTemplate(): Template {
        val builder = ItemList.Builder()
        routes.forEachIndexed { index, route ->
            val fastest = routes.minByOrNull { it.durationSeconds } === route
            val shortest = routes.minByOrNull { it.distanceMeters } === route
            val labels = buildList {
                if (fastest) add("Fastest")
                if (shortest) add("Shortest")
            }.joinToString(" • ").ifBlank { "Alternative route" }
            builder.addItem(
                Row.Builder()
                    .setTitle("Route ${index + 1} • ${formatDuration(route.durationSeconds)}")
                    .addText("${formatDistance(route.distanceMeters)} • $labels")
                    .setOnClickListener { startNavigation(route) }
                    .build()
            )
        }
        if (routes.isEmpty()) {
            builder.addItem(Row.Builder().setTitle(statusMessage ?: "No driving routes found").build())
        }
        return ListTemplate.Builder()
            .setSingleList(builder.build())
            .setHeader(
                Header.Builder()
                    .setTitle("Choose a route")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun navigationTemplate(): Template {
        val route = selectedRoute
        val location = currentLocation
        val currentStep = route?.steps?.getOrNull(stepIndex)
        val nextStep = route?.steps?.getOrNull(stepIndex + 1)
        val remainingMeters = if (route != null && location != null) {
            NavigationCore.remainingDistanceMeters(
                route,
                NavigationCore.nearestRoutePoint(Pair(location.latitude, location.longitude), route)
            )
        } else route?.distanceMeters ?: 0.0
        val remainingSeconds = if (route != null && route.distanceMeters > 0.0) {
            route.durationSeconds * (remainingMeters / route.distanceMeters).coerceIn(0.0, 1.0)
        } else route?.durationSeconds ?: 0.0
        val arrival = DateTimeWithZone.create(
            System.currentTimeMillis() + (remainingSeconds * 1000).toLong(),
            TimeZone.getDefault()
        )
        val estimate = TravelEstimate.Builder(
            Distance.create(remainingMeters, Distance.UNIT_METERS),
            arrival
        ).build()
        val step = Step.Builder(currentStep?.instruction ?: "Continue on the route").build()
        val routing = RoutingInfo.Builder()
            .setCurrentStep(
                step,
                Distance.create(
                    if (location != null && currentStep != null) {
                        distanceBetween(
                            Pair(location.latitude, location.longitude),
                            currentStep.location
                        )
                    } else 0.0,
                    Distance.UNIT_METERS
                )
            )
            .apply {
                if (nextStep != null) setNextStep(Step.Builder(nextStep.instruction).build())
            }
            .build()

        return NavigationTemplate.Builder()
            .setNavigationInfo(routing)
            .setDestinationTravelEstimate(estimate)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setTitle("End")
                            .setOnClickListener { endNavigation() }.build()
                    )
                    .addAction(
                        Action.Builder().setTitle("Search")
                            .setOnClickListener {
                                mode = CarScreenMode.SEARCH
                                invalidate()
                            }.build()
                    )
                    .build()
            )
            .build()
    }

    private fun searchDestination(query: String) {
        val clean = query.trim()
        if (clean.length < 2 || searching) return
        searching = true
        statusMessage = "Searching…"
        mode = CarScreenMode.PLACES
        places = emptyList()
        invalidate()
        networkExecutor.execute {
            try {
                val found = NavigationCore.searchPlaces(clean)
                mainHandler.post {
                    places = found
                    statusMessage = if (found.isEmpty()) "No matching places found" else null
                    searching = false
                    mode = CarScreenMode.PLACES
                    invalidate()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    places = emptyList()
                    statusMessage = error.message ?: "Destination search failed"
                    searching = false
                    mode = CarScreenMode.PLACES
                    invalidate()
                }
            }
        }
    }

    private fun chooseDestination(place: MapPlace) {
        val origin = currentLocation
        if (origin == null) {
            statusMessage = "Waiting for a GPS location. Check location permission and GPS."
            mode = CarScreenMode.PLACES
            invalidate()
            return
        }
        selectedPlace = place
        statusMessage = "Finding driving routes…"
        routes = emptyList()
        mode = CarScreenMode.ROUTES
        invalidate()
        val start = MapPlace("Current location", origin.latitude, origin.longitude)
        networkExecutor.execute {
            try {
                val found = NavigationCore.drivingRoutes(start, place)
                mainHandler.post {
                    routes = found
                    statusMessage = if (found.isEmpty()) "No driving routes found" else null
                    mode = CarScreenMode.ROUTES
                    invalidate()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    routes = emptyList()
                    statusMessage = error.message ?: "Route calculation failed"
                    mode = CarScreenMode.ROUTES
                    invalidate()
                }
            }
        }
    }

    private fun startNavigation(route: DrivingRoute) {
        selectedRoute = route
        stepIndex = if (route.steps.size > 1) 1 else 0
        offRouteCount = 0
        rerouting = false
        navigationActive = true
        mode = CarScreenMode.NAVIGATION
        userPanned = false
        try {
            navigationManager.navigationStarted()
            sendTripUpdate()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start navigation with the car host", error)
            statusMessage = "The car host did not accept navigation"
        }
        drawMap()
        invalidate()
    }

    private fun endNavigation() {
        navigationActive = false
        selectedRoute = null
        selectedPlace = null
        routes = emptyList()
        try {
            navigationManager.navigationEnded()
        } catch (error: Exception) {
            Log.w(TAG, "Navigation end notification failed", error)
        }
        mode = CarScreenMode.SEARCH
        invalidate()
        drawMap()
    }

    private fun onLocationChanged(location: Location) {
        currentLocation = location
        if (!userPanned) {
            centerLat = location.latitude
            centerLon = location.longitude
        }
        if (navigationActive) updateRouteProgress(location)
        drawMap()
        if (navigationActive) invalidate()
    }

    private fun updateRouteProgress(location: Location) {
        val route = selectedRoute ?: return
        val point = Pair(location.latitude, location.longitude)
        val nearest = NavigationCore.nearestRoutePoint(point, route)
        val offRouteDistance = NavigationCore.distanceToRouteMeters(point, route)
        if (offRouteDistance > 75.0) {
            offRouteCount++
            if (offRouteCount >= 3) requestReroute(location)
        } else {
            offRouteCount = 0
        }

        while (stepIndex < route.steps.lastIndex) {
            if (distanceBetween(point, route.steps[stepIndex].location) > 28.0) break
            stepIndex++
        }
        if (nearest >= route.coordinates.lastIndex - 2 &&
            distanceBetween(point, route.coordinates.last()) < 35.0
        ) {
            endNavigation()
            statusMessage = "You have arrived"
        } else {
            sendTripUpdate()
        }
    }

    private fun requestReroute(location: Location) {
        val destination = selectedPlace ?: return
        if (rerouting || System.currentTimeMillis() - lastRerouteAt < 20_000L) return
        rerouting = true
        lastRerouteAt = System.currentTimeMillis()
        val origin = MapPlace("Current location", location.latitude, location.longitude)
        networkExecutor.execute {
            try {
                val alternatives = NavigationCore.drivingRoutes(origin, destination)
                mainHandler.post {
                    val replacement = alternatives.firstOrNull()
                    if (replacement != null) {
                        selectedRoute = replacement
                        stepIndex = if (replacement.steps.size > 1) 1 else 0
                        offRouteCount = 0
                        statusMessage = "Route updated"
                        sendTripUpdate()
                    } else {
                        statusMessage = "Could not find a new route"
                    }
                    rerouting = false
                    invalidate()
                    drawMap()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    statusMessage = "Rerouting failed. Staying on the current route."
                    rerouting = false
                    invalidate()
                }
            }
        }
    }

    private fun sendTripUpdate() {
        if (!navigationActive) return
        val route = selectedRoute ?: return
        val location = currentLocation
        val currentStep = route.steps.getOrNull(stepIndex) ?: return
        val remaining = if (location != null) {
            NavigationCore.remainingDistanceMeters(
                route,
                NavigationCore.nearestRoutePoint(Pair(location.latitude, location.longitude), route)
            )
        } else route.distanceMeters
        val seconds = if (route.distanceMeters > 0.0) {
            route.durationSeconds * (remaining / route.distanceMeters).coerceIn(0.0, 1.0)
        } else 0.0
        val estimate = TravelEstimate.Builder(
            Distance.create(currentStep.distanceMeters.coerceAtLeast(0.0), Distance.UNIT_METERS),
            DateTimeWithZone.create(
                System.currentTimeMillis() + (seconds * 1000).toLong(),
                TimeZone.getDefault()
            )
        ).build()
        val step = Step.Builder(currentStep.instruction).build()
        try {
            navigationManager.updateTrip(
                Trip.Builder()
                    .setCurrentRoad(currentStep.instruction)
                    .addStep(step, estimate)
                    .build()
            )
        } catch (error: Exception) {
            Log.w(TAG, "Navigation metadata update failed", error)
        }
    }

    private fun hasLocationPermission(): Boolean =
        carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            carContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun updateLastKnownLocation() {
        if (!hasLocationPermission()) return
        try {
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location != null) {
                currentLocation = location
                centerLat = location.latitude
                centerLon = location.longitude
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Location permission is unavailable", error)
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2500L, 5f, locationListener, Looper.getMainLooper()
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Location updates are not permitted", error)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "GPS provider is unavailable", error)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        mapSurface = surfaceContainer.surface
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        renderError = null
        Log.i(TAG, "Map surface available: ${surfaceWidth}x${surfaceHeight}")
        drawMap()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (mapSurface === surfaceContainer.surface) {
            mapSurface = null
            surfaceWidth = 0
            surfaceHeight = 0
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val center = project(centerLat, centerLon)
        val next = unproject(center.first + distanceX, center.second + distanceY)
        centerLat = next.first
        centerLon = next.second
        userPanned = true
        drawMap()
    }

    private fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val worldSize = 256.0 * 2.0.pow(zoom)
        val x = (lon + 180.0) / 360.0 * worldSize
        val safeLat = lat.coerceIn(-85.0511, 85.0511)
        val rad = Math.toRadians(safeLat)
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * worldSize
        return Pair(x, y)
    }

    private fun unproject(x: Double, y: Double): Pair<Double, Double> {
        val worldSize = 256.0 * 2.0.pow(zoom)
        val lon = x / worldSize * 360.0 - 180.0
        val mercator = PI * (1.0 - 2.0 * y / worldSize)
        val lat = Math.toDegrees(atan(sinh(mercator)))
        return Pair(lat.coerceIn(-85.0, 85.0), lon)
    }

    private fun drawMap() {
        val surface = mapSurface ?: return
        if (!surface.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null) ?: return
            canvas.drawColor(Color.rgb(24, 29, 36))
            val center = project(centerLat, centerLon)
            val left = floor((center.first - surfaceWidth / 2.0) / 256.0).toInt()
            val right = floor((center.first + surfaceWidth / 2.0) / 256.0).toInt()
            val top = floor((center.second - surfaceHeight / 2.0) / 256.0).toInt()
            val bottom = floor((center.second + surfaceHeight / 2.0) / 256.0).toInt()
            val tileCount = 2.0.pow(zoom).toInt()

            for (tileY in top..bottom) {
                if (tileY !in 0 until tileCount) continue
                for (tileX in left..right) {
                    val wrappedX = ((tileX % tileCount) + tileCount) % tileCount
                    val key = "$zoom/$wrappedX/$tileY"
                    val x = (tileX * 256.0 - center.first + surfaceWidth / 2.0).toFloat()
                    val y = (tileY * 256.0 - center.second + surfaceHeight / 2.0).toFloat()
                    val destination = RectF(x, y, x + 256f, y + 256f)
                    val bitmap = tileCache[key]
                    if (bitmap != null && !bitmap.isRecycled) {
                        canvas.drawBitmap(bitmap, null, destination, tilePaint)
                    } else {
                        canvas.drawRect(destination, backgroundPaint)
                        loadTile(key)
                    }
                }
            }

            selectedRoute?.let { route ->
                val routePath = Path()
                var started = false
                route.coordinates.forEach { coordinate ->
                    val point = project(coordinate.first, coordinate.second)
                    val x = (point.first - center.first + surfaceWidth / 2.0).toFloat()
                    val y = (point.second - center.second + surfaceHeight / 2.0).toFloat()
                    if (!started) {
                        routePath.moveTo(x, y)
                        started = true
                    } else {
                        routePath.lineTo(x, y)
                    }
                }
                canvas.drawPath(routePath, routePaint)
            }

            val cx = surfaceWidth / 2f
            val cy = surfaceHeight / 2f
            markerPath.reset()
            markerPath.moveTo(cx, cy - 27f)
            markerPath.lineTo(cx - 17f, cy + 18f)
            markerPath.lineTo(cx, cy + 10f)
            markerPath.lineTo(cx + 17f, cy + 18f)
            markerPath.close()
            canvas.drawPath(markerPath, markerPaint)
            canvas.drawPath(markerPath, markerBorder)

            if (currentLocation == null) canvas.drawText("Waiting for GPS location", 24f, 42f, textPaint)
            if (tileError != null) canvas.drawText("Some map tiles could not load", 24f, surfaceHeight - 52f, smallTextPaint)
            if (renderError != null) canvas.drawText("Map drawing error", 24f, 42f, textPaint)
            if (rerouting) canvas.drawText("Updating route…", 24f, 76f, textPaint)
            canvas.drawText("© OpenStreetMap contributors", 20f, surfaceHeight - 18f, smallTextPaint)
        } catch (error: Exception) {
            renderError = error.javaClass.simpleName + ": " + (error.message ?: "unknown")
            Log.e(TAG, "Could not draw map surface", error)
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not post map canvas", error)
                }
            }
        }
    }

    private fun loadTile(key: String) {
        val now = System.currentTimeMillis()
        val failedAt = failedTiles[key]
        if (failedAt != null && now - failedAt < TILE_RETRY_MS) return
        if (!loadingTiles.add(key)) return
        tileExecutor.execute {
            var bitmap: Bitmap? = null
            var failure: String? = null
            var connection: HttpURLConnection? = null
            try {
                val parts = key.split("/")
                val url = URL("https://tile.openstreetmap.org/${parts[0]}/${parts[1]}/${parts[2]}.png")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    useCaches = true
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "HorizenMaps/1.0 (+https://maps.horizensearch.com/)")
                    setRequestProperty("Accept", "image/png")
                }
                val response = connection.responseCode
                if (response != HttpURLConnection.HTTP_OK) {
                    failure = "Map tile server returned HTTP $response"
                    throw IllegalStateException(failure)
                }
                connection.inputStream.use { bitmap = BitmapFactory.decodeStream(it) }
                if (bitmap == null) {
                    failure = "Map tile response was not a readable image"
                    throw IllegalStateException(failure)
                }
                tileCache[key] = bitmap!!
                failedTiles.remove(key)
                tileError = null
            } catch (error: Exception) {
                failure = failure ?: "Map tiles unavailable: ${error.javaClass.simpleName}"
                tileError = failure
                failedTiles[key] = System.currentTimeMillis()
                Log.w(TAG, "Tile request failed for $key: $failure", error)
            } finally {
                connection?.disconnect()
                loadingTiles.remove(key)
            }
            mainHandler.post { drawMap() }
        }
    }

    private fun distanceBetween(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val radius = 6371000.0
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return radius * 2 * kotlin.math.atan2(sqrt(h), sqrt(1 - h))
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000.0) String.format("%.1f km", meters / 1000.0) else "${meters.toInt()} m"

    private fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60.0).toInt().coerceAtLeast(1)
        return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
    }
}
