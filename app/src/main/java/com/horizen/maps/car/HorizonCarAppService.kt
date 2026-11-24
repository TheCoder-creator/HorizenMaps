package com.horizen.maps.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

private const val TAG = "HorizonMapsCar"
private const val TILE_RETRY_MS = 20_000L

class HorizonCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = HorizonCarSession()
}

class HorizonCarSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen = HorizonCarScreen(carContext)
}

class HorizonCarScreen(carContext: androidx.car.app.CarContext) : Screen(carContext), SurfaceCallback {
    private var mapSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var centerLat = 37.3382
    private var centerLon = -121.8863
    private val zoom = 15
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tileExecutor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "HorizonMapTiles").apply { isDaemon = true }
    }
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
    private val locationManager = carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val locationListener = LocationListener { location ->
        centerLat = location.latitude
        centerLon = location.longitude
        drawMap()
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        updateLastKnownLocation()
        startLocationUpdates()
    }

    override fun onGetTemplate(): Template {
        val recenter = Action.Builder()
            .setTitle("Recenter")
            .setOnClickListener {
                updateLastKnownLocation()
                drawMap()
                invalidate()
            }
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(recenter).build())
            .build()
    }

    private fun hasLocationPermission(): Boolean {
        return carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            carContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateLastKnownLocation() {
        if (!hasLocationPermission()) return
        try {
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location != null) {
                centerLat = location.latitude
                centerLon = location.longitude
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission is unavailable", e)
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2500L,
                5f,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Location updates are not permitted", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "GPS provider is unavailable", e)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val nextSurface = surfaceContainer.surface
        if (mapSurface !== nextSurface) {
            mapSurface?.release()
        }
        mapSurface = nextSurface
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        renderError = null
        Log.i(TAG, "Map surface available: ${surfaceWidth}x${surfaceHeight}")
        drawMap()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        val destroyedSurface = surfaceContainer.surface
        if (mapSurface === destroyedSurface) {
            mapSurface = null
            surfaceWidth = 0
            surfaceHeight = 0
        }
        destroyedSurface?.release()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val center = project(centerLat, centerLon)
        val next = unproject(center.first + distanceX, center.second + distanceY)
        centerLat = next.first
        centerLon = next.second
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
            var visibleTiles = 0

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
                        visibleTiles++
                    } else {
                        canvas.drawRect(destination, backgroundPaint)
                        loadTile(key)
                    }
                }
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

            if (visibleTiles == 0) {
                val message = tileError ?: "Loading map tiles…"
                val messageWidth = textPaint.measureText(message)
                canvas.drawText(message, (surfaceWidth - messageWidth) / 2f, cy + 58f, textPaint)
            } else if (tileError != null) {
                canvas.drawText("Some map tiles could not load", 24f, surfaceHeight - 52f, smallTextPaint)
            }

            if (renderError != null) {
                canvas.drawText("Map drawing error", 24f, 42f, textPaint)
            }

            canvas.drawText("© OpenStreetMap contributors", 20f, surfaceHeight - 18f, smallTextPaint)
        } catch (e: Exception) {
            renderError = e.javaClass.simpleName + ": " + (e.message ?: "unknown")
            Log.e(TAG, "Could not draw map surface", e)
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not post map canvas", e)
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
                    setRequestProperty(
                        "User-Agent",
                        "HorizenMaps/1.0 (+https://maps.horizensearch.com/)"
                    )
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
            } catch (e: Exception) {
                failure = failure ?: "Map tiles unavailable: ${e.javaClass.simpleName}"
                tileError = failure
                failedTiles[key] = System.currentTimeMillis()
                Log.w(TAG, "Tile request failed for $key: $failure", e)
            } finally {
                connection?.disconnect()
                loadingTiles.remove(key)
            }
            mainHandler.post { drawMap() }
        }
    }
}
