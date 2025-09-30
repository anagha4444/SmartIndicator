package com.example.mysmartindicator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.math.*
import com.example.mysmartindicator.data.AppDatabase
import com.example.mysmartindicator.data.LocationEntity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import java.text.SimpleDateFormat
import java.util.*
import android.content.Context
import android.util.Log
import android.location.Geocoder

// Main Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load the ONNX ML model for indicator prediction
        ONNXPredictor.loadModel(this)
        setContent {
            TurnTrackerUI() // Launch the main UI
        }
    }
}

// Data class for navigation steps
data class TurnStep(val lat: Double, val lng: Double, val maneuver: String)

// Simple latitude/longitude representation
data class SimpleLocation(val latitude: Double, val longitude: Double)

// Location with timestamp and bearing
data class LocationPoint(val location: SimpleLocation, val timestamp: Long, val bearing: Float = 0f)

// Class to detect turns based on recent GPS points
class TurnDetector {
    private val locationHistory = mutableListOf<LocationPoint>()
    private val TURN_THRESHOLD = 25.0        // Minimum angle difference to count as a turn
    private val MIN_SPEED_THRESHOLD = 2.0    // km/h, ignore very slow movement
    private var lastTurnLocation: SimpleLocation? = null

    // Detect turn direction based on recent locations
    fun detectTurn(newLocation: SimpleLocation, speed: Float): String {
        val currentTime = System.currentTimeMillis()
        locationHistory.add(LocationPoint(newLocation, currentTime))

        // Keep only last 10 locations
        if (locationHistory.size > 10) locationHistory.removeAt(0)
        if (locationHistory.size < 3) return "straight"

        // Ignore turns if moving too slowly
        if (speed < MIN_SPEED_THRESHOLD) return "straight"

        // Avoid repeated detection near last turn
        lastTurnLocation?.let {
            if (haversineDistance(newLocation, it) < 30) return "straight"
        }

        val recent = locationHistory.takeLast(3)
        if (recent.size < 3) return "straight"

        // Calculate bearings between last 3 points
        val bearing1 = calculateBearing(recent[0], recent[1])
        val bearing2 = calculateBearing(recent[1], recent[2])
        val angleDiff = calculateAngleDifference(bearing1, bearing2)

        // Determine turn direction
        return when {
            angleDiff > TURN_THRESHOLD -> {
                lastTurnLocation = newLocation
                "right"
            }
            angleDiff < -TURN_THRESHOLD -> {
                lastTurnLocation = newLocation
                "left"
            }
            else -> "straight"
        }
    }

    // Calculate bearing between two points
    private fun calculateBearing(from: LocationPoint, to: LocationPoint): Double {
        val lat1 = Math.toRadians(from.location.latitude)
        val lat2 = Math.toRadians(to.location.latitude)
        val deltaLon = Math.toRadians(to.location.longitude - from.location.longitude)
        val x = sin(deltaLon) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return Math.toDegrees(atan2(x, y))
    }

    // Calculate angle difference for turn detection
    private fun calculateAngleDifference(bearing1: Double, bearing2: Double): Double {
        var diff = bearing2 - bearing1
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return diff
    }
}

// Haversine distance between two GPS points in meters
fun haversineDistance(loc1: SimpleLocation, loc2: SimpleLocation): Double {
    val R = 6371e3 // Earth radius in meters
    val dLat = Math.toRadians(loc2.latitude - loc1.latitude)
    val dLon = Math.toRadians(loc2.longitude - loc1.longitude)
    val lat1 = Math.toRadians(loc1.latitude)
    val lat2 = Math.toRadians(loc2.latitude)
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

// Bearing calculation between two simple locations
fun calculateBearing(from: SimpleLocation, to: SimpleLocation): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(deltaLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

// Angle difference helper
fun angleBetween(a: Float, b: Double): Double {
    val diff = abs(a - b)
    return if (diff > 180) 360 - diff else diff
}

// Log driver behavior into Room database
suspend fun logBehavior(
    context: Context,
    lat: Double,
    lng: Double,
    tag: String,
    direction: String,
    speed: Float,
    distanceToTurn: Int,
    indicatorOn: Boolean
) {
    try {
        val db = AppDatabase.getDatabase(context)
        val dao = db.TurnEventDao()
        val log = LocationEntity(
            latitude = lat,
            longitude = lng,
            tag = tag,
            direction = direction,
            speed = speed,
            distanceToTurn = distanceToTurn,
            indicatorOn = indicatorOn
        )
        dao.insert(log)
    } catch (e: Exception) {
        Log.e("LogBehavior", "Error logging behavior", e)
    }
}

// UI component to display saved locations from database
@Composable
fun SavedLocationsScreen(context: android.content.Context) {
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.TurnEventDao() }
    var locations by remember { mutableStateOf<List<LocationEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US) }

    // Fetch locations from DB
    LaunchedEffect(true) {
        try {
            val allLocations = withContext(Dispatchers.IO) { dao.getAll() }
            locations = allLocations
            isLoading = false
        } catch (e: Exception) {
            Log.e("SavedLocations", "Error loading locations", e)
            error = "Failed to load locations"
            isLoading = false
        }
    }

    // Display saved locations
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Saved Locations (${locations.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                error != null -> Text(text = error!!, color = MaterialTheme.colorScheme.error)
                locations.isEmpty() -> Text("No saved locations yet")
                else -> LazyColumn {
                    items(locations.take(5)) { location -> LocationCard(location, dateFormat) }
                    if (locations.size > 5) item { Text("... and ${locations.size - 5} more") }
                }
            }
        }
    }
}

// UI card for individual location
@Composable
fun LocationCard(location: LocationEntity, dateFormat: SimpleDateFormat) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📍 ${location.latitude}, ${location.longitude}")
            Text("⏰ ${dateFormat.format(Date(location.timestamp))}")
            location.tag?.let { Text("🔖 $it") }
            location.direction?.let { Text("➡️ Direction: $it") }
            location.speed?.let { Text("🚗 Speed: ${it.toInt()} km/h") }
            location.distanceToTurn?.let { Text("📏 Distance to Turn: ${it}m") }
            location.indicatorOn?.let { Text(if (it) "✅ Indicator ON" else "❌ Indicator OFF") }
        }
    }
}

// Main UI composable
@Composable
fun TurnTrackerUI() {
    val context = LocalContext.current
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // State variables for location, speed, turn detection, warnings, etc.
    var currentLat by remember { mutableStateOf("...") }
    var currentLng by remember { mutableStateOf("...") }
    var warning by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf<List<TurnStep>>(emptyList()) }
    var indicatorOn by remember { mutableStateOf(false) }
    var destinationLat by remember { mutableStateOf(18.5206) }
    var destinationLng by remember { mutableStateOf(73.8569) }
    var lastRouteUpdate by remember { mutableStateOf(0L) }
    var isRouteLoading by remember { mutableStateOf(false) }
    val turnDetector = remember { TurnDetector() }
    var detectedTurn by remember { mutableStateOf("straight") }
    var speed by remember { mutableStateOf(0f) }
    var futureDirection by remember { mutableStateOf("straight") }
    var futureDistance by remember { mutableStateOf(0) }
    var lastWarnedTurnLocation by remember { mutableStateOf<SimpleLocation?>(null) }
    var locationSaveCounter by remember { mutableStateOf(0) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var locationCallback by remember { mutableStateOf<LocationCallback?>(null) }
    var destinationAddress by remember { mutableStateOf("") }
    var destinationError by remember { mutableStateOf<String?>(null) }
    var mlWarning by remember { mutableStateOf("") }

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocationPermission = granted
        if (granted) startLocationUpdates(fusedClient, context,
            onLocationUpdate = { lat, lng, spd, bearing ->
                // Handle location updates (detailed logic)
            },
            onCallbackSet = { callback -> locationCallback = callback }
        )
    }

    // Check permissions on startup
    LaunchedEffect(Unit) {
        hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // UI Layout
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { /* Display current location, speed, steps */ }
        item { /* Destination input bar */ }
        item { /* Future turn info */ }
        item { /* Warning card */ }
        item { /* ML warning card */ }
        item { /* Indicator switch */ }
        item { key(locationSaveCounter) { SavedLocationsScreen(context) } }
    }
}

// Function to start GPS location updates
fun startLocationUpdates(
    fusedClient: FusedLocationProviderClient,
    context: Context,
    onLocationUpdate: (lat: Double, lng: Double, speed: Float, bearing: Float) -> Unit,
    onCallbackSet: (LocationCallback) -> Unit
) {
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
        .setMinUpdateIntervalMillis(1000)
        .setMaxUpdateDelayMillis(5000)
        .build()

    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                val speed = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
                val bearing = if (loc.hasBearing()) loc.bearing else 0f
                onLocationUpdate(loc.latitude, loc.longitude, speed, bearing)
            }
        }
    }

    try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            onCallbackSet(callback)
        }
    } catch (e: SecurityException) {
        Log.e("LocationUpdates", "Security exception requesting location updates", e)
    }
}

// Process upcoming turns and trigger warnings if indicator is OFF
fun processUpcomingTurns(
    currentLoc: SimpleLocation,
    steps: List<TurnStep>,
    speed: Float,
    bearing: Float,
    indicatorOn: Boolean,
    lastWarnedTurnLocation: SimpleLocation?,
    onWarningUpdate: (String, SimpleLocation?) -> Unit,
    onFutureDirectionUpdate: (String, Int) -> Unit,
    onBehaviorLog: (String, String, Int) -> Unit
) {
    // Detailed logic for calculating distance, ETA, and triggering warning
}

// OkHttp client for OSRM API requests
val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

// Fetch route steps from OSRM API
suspend fun fetchOSRMSteps(
    startLat: Double, startLng: Double, endLat: Double, endLng: Double
): List<TurnStep> {
    val url = "https://router.project-osrm.org/route/v1/driving/$startLng,$startLat;$endLng,$endLat?overview=full&steps=true"
    val request = Request.Builder().url(url).addHeader("User-Agent", "SmartIndicator/1.0").build()

    return withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    if (!json.isNullOrEmpty()) parseSteps(json) else emptyList()
                } else emptyList()
            }
        } catch (e: IOException) { emptyList() } catch (e: Exception) { emptyList() }
    }
}

// Parse OSRM JSON response into TurnStep list
fun parseSteps(json: String): List<TurnStep> {
    val list = mutableListOf<TurnStep>()
    try {
        val obj = JSONObject(json)
        val routes = obj.getJSONArray("routes")
        val legs = routes.getJSONObject(0).getJSONArray("legs")
        val steps = legs.getJSONObject(0).getJSONArray("steps")

        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val maneuver = step.getJSONObject("maneuver")
            val loc = maneuver.getJSONArray("location")
            val lat = loc.getDouble(1)
            val lng = loc.getDouble(0)
            val type = maneuver.getString("type")
            val modifier = maneuver.optString("modifier", "straight")
            list.add(TurnStep(lat, lng, "$type-$modifier"))
        }
    } catch (e: Exception) { Log.e("OSRM", "Error parsing JSON", e) }
    return list
}
