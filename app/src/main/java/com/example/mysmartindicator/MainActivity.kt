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



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load ONNX model
        ONNXPredictor.loadModel(this)
        setContent {
            TurnTrackerUI()
        }
    }
}

data class TurnStep(val lat: Double, val lng: Double, val maneuver: String)
data class SimpleLocation(val latitude: Double, val longitude: Double)
data class LocationPoint(val location: SimpleLocation, val timestamp: Long, val bearing: Float = 0f)

class TurnDetector {
    private val locationHistory = mutableListOf<LocationPoint>()
    private val TURN_THRESHOLD = 25.0
    private val MIN_SPEED_THRESHOLD = 2.0 // km/h
    private var lastTurnLocation: SimpleLocation? = null

    fun detectTurn(newLocation: SimpleLocation, speed: Float): String {
        val currentTime = System.currentTimeMillis()
        locationHistory.add(LocationPoint(newLocation, currentTime))

        // Keep only recent locations
        if (locationHistory.size > 10) locationHistory.removeAt(0)
        if (locationHistory.size < 3) return "straight"

        // Don't detect turns if moving too slowly
        if (speed < MIN_SPEED_THRESHOLD) return "straight"

        // Check if we're too close to the last turn location
        lastTurnLocation?.let {
            if (haversineDistance(newLocation, it) < 30) return "straight"
        }

        val recent = locationHistory.takeLast(3)
        if (recent.size < 3) return "straight"

        val bearing1 = calculateBearing(recent[0], recent[1])
        val bearing2 = calculateBearing(recent[1], recent[2])
        val angleDiff = calculateAngleDifference(bearing1, bearing2)

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

    private fun calculateBearing(from: LocationPoint, to: LocationPoint): Double {
        val lat1 = Math.toRadians(from.location.latitude)
        val lat2 = Math.toRadians(to.location.latitude)
        val deltaLon = Math.toRadians(to.location.longitude - from.location.longitude)
        val x = sin(deltaLon) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return Math.toDegrees(atan2(x, y))
    }

    private fun calculateAngleDifference(bearing1: Double, bearing2: Double): Double {
        var diff = bearing2 - bearing1
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return diff
    }
}

fun haversineDistance(loc1: SimpleLocation, loc2: SimpleLocation): Double {
    val R = 6371e3
    val dLat = Math.toRadians(loc2.latitude - loc1.latitude)
    val dLon = Math.toRadians(loc2.longitude - loc1.longitude)
    val lat1 = Math.toRadians(loc1.latitude)
    val lat2 = Math.toRadians(loc2.latitude)
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun calculateBearing(from: SimpleLocation, to: SimpleLocation): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(deltaLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun angleBetween(a: Float, b: Double): Double {
    val diff = abs(a - b)
    return if (diff > 180) 360 - diff else diff
}

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

@Composable
fun SavedLocationsScreen(context: android.content.Context) {
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.TurnEventDao() }
    var locations by remember { mutableStateOf<List<LocationEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US) }

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

    Card(
        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Saved Locations (${locations.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                error != null -> {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                locations.isEmpty() -> {
                    Text(
                        text = "No saved locations yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(locations.take(5)) { location ->
                            LocationCard(location, dateFormat)
                        }
                        if (locations.size > 5) {
                            item {
                                Text(
                                    text = "... and ${locations.size - 5} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocationCard(location: LocationEntity, dateFormat: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "📍 ${String.format(Locale.US, "%.6f", location.latitude)}, ${String.format(Locale.US, "%.6f", location.longitude)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "⏰ ${dateFormat.format(Date(location.timestamp))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            location.tag?.let {
                Text(
                    text = "🔖 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            location.direction?.let {
                Text(
                    text = "➡️ Direction: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            location.speed?.let {
                Text(
                    text = "🚗 Speed: ${it.toInt()} km/h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            location.distanceToTurn?.let {
                Text(
                    text = "📏 Distance to Turn: ${it}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            location.indicatorOn?.let {
                Text(
                    text = if (it) "✅ Indicator ON" else "❌ Indicator OFF",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun TurnTrackerUI() {
    val context = LocalContext.current
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

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
    //destination bar
    var destinationAddress by remember { mutableStateOf("") }
    var destinationError by remember { mutableStateOf<String?>(null) }
    var mlWarning by remember { mutableStateOf("") }//ml training


    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
        if (granted) {
            startLocationUpdates(
                fusedClient = fusedClient,
                context = context,
                onLocationUpdate = { lat, lng, spd, bearing ->
                    currentLat = String.format(Locale.US, "%.6f", lat)
                    currentLng = String.format(Locale.US, "%.6f", lng)
                    speed = spd

                    val currentLoc = SimpleLocation(lat, lng)

                    // Save location to database
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = AppDatabase.getDatabase(context)
                            val dao = db.TurnEventDao()
                            val locationEntity = LocationEntity(
                                latitude = lat,
                                longitude = lng
                            )
                            dao.insert(locationEntity)
                            withContext(Dispatchers.Main) {
                                locationSaveCounter++
                            }
                        } catch (e: Exception) {
                            Log.e("LocationSave", "Error saving location", e)
                        }
                    }

                    // Update route if needed
                    val currentTime = System.currentTimeMillis()
                    if (steps.isEmpty() || (currentTime - lastRouteUpdate > 30000)) {
                        if (!isRouteLoading) {
                            isRouteLoading = true
                            CoroutineScope(Dispatchers.IO).launch {
                                val newSteps = fetchOSRMSteps(lat, lng, destinationLat, destinationLng)
                                withContext(Dispatchers.Main) {
                                    steps = newSteps
                                    lastRouteUpdate = currentTime
                                    isRouteLoading = false
                                }
                            }
                        }
                    }


                    // Detect turns
                    val previousTurn = detectedTurn
                    detectedTurn = if (speed > 5) turnDetector.detectTurn(currentLoc, speed) else "straight"

                    // Log behavior when turn is detected
                    if ((detectedTurn == "left" || detectedTurn == "right") && previousTurn != detectedTurn) {
                        CoroutineScope(Dispatchers.IO).launch {
                            logBehavior(
                                context = context,
                                lat = lat,
                                lng = lng,
                                tag = "Turn Detected",
                                direction = detectedTurn,
                                speed = speed,
                                distanceToTurn = 0,
                                indicatorOn = indicatorOn
                            )
                            withContext(Dispatchers.Main) { locationSaveCounter++ }
                        }
                    }

                    // Process upcoming turns
                    processUpcomingTurns(
                        currentLoc = currentLoc,
                        steps = steps,
                        speed = speed,
                        bearing = bearing,
                        indicatorOn = indicatorOn,
                        lastWarnedTurnLocation = lastWarnedTurnLocation,
                        onWarningUpdate = { newWarning, newWarnedLocation ->
                            warning = newWarning
                            lastWarnedTurnLocation = newWarnedLocation
                        },
                        onFutureDirectionUpdate = { direction, distance ->
                            futureDirection = direction
                            futureDistance = distance
                        },
                        onBehaviorLog = { tag, direction, distanceToTurn ->
                            CoroutineScope(Dispatchers.IO).launch {
                                logBehavior(
                                    context = context,
                                    lat = lat,
                                    lng = lng,
                                    tag = tag,
                                    direction = direction,
                                    speed = speed,
                                    distanceToTurn = distanceToTurn,
                                    indicatorOn = indicatorOn
                                )
                                withContext(Dispatchers.Main) { locationSaveCounter++ }
                            }
                        }
                    )
                    // ✅ ML prediction goes here
                    val directionCode = when (detectedTurn) {
                        "left" -> 1f
                        "right" -> 2f
                        else -> 0f
                    }

                    val predictedForget = ONNXPredictor.predict(
                        lat.toFloat(),
                        lng.toFloat(),
                        speed,
                        directionCode,
                        futureDistance.toFloat()
                    )

                    if (predictedForget) {
                        mlWarning = "🤖 AI predicts: You may forget to use indicator!"
                    } else {
                        mlWarning = ""
                    }
                },
                onCallbackSet = { callback ->
                    locationCallback = callback
                }
            )
        }
    }

    // Check permission on startup
    LaunchedEffect(Unit) {
        hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            // Start location updates if we already have permission
            startLocationUpdates(
                fusedClient = fusedClient,
                context = context,
                onLocationUpdate = { lat, lng, spd, bearing ->
                    currentLat = String.format(Locale.US, "%.6f", lat)
                    currentLng = String.format(Locale.US, "%.6f", lng)
                    speed = spd

                    val currentLoc = SimpleLocation(lat, lng)

                    // Save location to database
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = AppDatabase.getDatabase(context)
                            val dao = db.TurnEventDao()
                            val locationEntity = LocationEntity(
                                latitude = lat,
                                longitude = lng
                            )
                            dao.insert(locationEntity)
                            withContext(Dispatchers.Main) {
                                locationSaveCounter++
                            }
                        } catch (e: Exception) {
                            Log.e("LocationSave", "Error saving location", e)
                        }
                    }

                    // Update route if needed
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastRouteUpdate > 60000 && destinationLat != null && destinationLng != null) {
                        isRouteLoading = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val newSteps = fetchOSRMSteps(lat, lng, destinationLat, destinationLng)
                            withContext(Dispatchers.Main) {
                                steps = newSteps
                                lastRouteUpdate = currentTime
                                isRouteLoading = false
                            }
                        }
                    }


                    // Detect turns
                    val previousTurn = detectedTurn
                    detectedTurn = if (speed > 5) turnDetector.detectTurn(currentLoc, speed) else "straight"

                    // Log behavior when turn is detected
                    if ((detectedTurn == "left" || detectedTurn == "right") && previousTurn != detectedTurn) {
                        CoroutineScope(Dispatchers.IO).launch {
                            logBehavior(
                                context = context,
                                lat = lat,
                                lng = lng,
                                tag = "Turn Detected",
                                direction = detectedTurn,
                                speed = speed,
                                distanceToTurn = 0,
                                indicatorOn = indicatorOn
                            )
                            withContext(Dispatchers.Main) { locationSaveCounter++ }
                        }
                    }

                    // Process upcoming turns
                    processUpcomingTurns(
                        currentLoc = currentLoc,
                        steps = steps,
                        speed = speed,
                        bearing = bearing,
                        indicatorOn = indicatorOn,
                        lastWarnedTurnLocation = lastWarnedTurnLocation,
                        onWarningUpdate = { newWarning, newWarnedLocation ->
                            Log.d("WarningDebug", "Warning set to: $newWarning")
                            warning = newWarning
                            lastWarnedTurnLocation = newWarnedLocation
                        },
                        onFutureDirectionUpdate = { direction, distance ->
                            futureDirection = direction
                            futureDistance = distance
                        },
                        onBehaviorLog = { tag, direction, distanceToTurn ->
                            CoroutineScope(Dispatchers.IO).launch {
                                logBehavior(
                                    context = context,
                                    lat = lat,
                                    lng = lng,
                                    tag = tag,
                                    direction = direction,
                                    speed = speed,
                                    distanceToTurn = distanceToTurn,
                                    indicatorOn = indicatorOn
                                )
                                withContext(Dispatchers.Main) { locationSaveCounter++ }
                            }
                        }
                    )
                },
                onCallbackSet = { callback ->
                    locationCallback = callback
                }
            )
        }
    }

    // Clean up location updates when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            locationCallback?.let { callback ->
                try {
                    fusedClient.removeLocationUpdates(callback)
                } catch (e: Exception) {
                    Log.e("LocationCleanup", "Error removing location updates", e)
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Current Location", style = MaterialTheme.typography.titleMedium)
                    Text("Lat: $currentLat")
                    Text("Lng: $currentLng")
                    Text("Speed: ${speed.toInt()} km/h")
                    Text("Route Steps: ${steps.size}")
                    if (isRouteLoading) {
                        Text("Loading route...", color = MaterialTheme.colorScheme.primary)
                    }
                    if (!hasLocationPermission) {
                        Text("Location permission required", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        //destination bar
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Set Destination", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = destinationAddress,
                        onValueChange = { destinationAddress = it },
                        label = { Text("Enter destination address") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val geocoder = Geocoder(context, Locale.getDefault())
                                    val results = geocoder.getFromLocationName(destinationAddress, 1)

                                    withContext(Dispatchers.Main) {
                                        if (!results.isNullOrEmpty()) {
                                            val loc = results[0]
                                            destinationLat = loc.latitude
                                            destinationLng = loc.longitude
                                            destinationError = null

                                            // Trigger route update immediately with current location
                                            currentLat.toDoubleOrNull()?.let { lat ->
                                                currentLng.toDoubleOrNull()?.let { lng ->
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        val newSteps = fetchOSRMSteps(lat, lng, destinationLat, destinationLng)
                                                        withContext(Dispatchers.Main) {
                                                            steps = newSteps
                                                            lastRouteUpdate = System.currentTimeMillis()

                                                            isRouteLoading = false
                                                        }
                                                    }

                                                }
                                            }
                                        } else {
                                            destinationError = "No results found for address"
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        destinationError = "Error finding location"
                                    }
                                    Log.e("Geocoder", "Error geocoding address", e)
                                }
                            }
                        }
                    ) {
                        Text("Set Destination")
                    }

                    if (destinationError != null) {
                        Text(
                            text = destinationError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }



        /*item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (detectedTurn) {
                        "left" -> MaterialTheme.colorScheme.primaryContainer
                        "right" -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Current Direction", style = MaterialTheme.typography.labelMedium)
                    Text(detectedTurn.uppercase(), style = MaterialTheme.typography.headlineMedium)
                }
            }
        }*/

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (futureDirection) {
                        "left" -> MaterialTheme.colorScheme.tertiaryContainer
                        "right" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Future Turn", style = MaterialTheme.typography.labelMedium)
                    Text(futureDirection.uppercase(), style = MaterialTheme.typography.headlineMedium)
                    if (futureDistance > 0) {
                        Text("in ${futureDistance}m", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            if (warning.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = warning,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        item {
            if (mlWarning.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(
                        text = mlWarning,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }


        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Turn Indicator:", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = indicatorOn,
                        onCheckedChange = { newValue ->
                            indicatorOn = newValue
                            // Log behavior when indicator is toggled
                            CoroutineScope(Dispatchers.IO).launch {
                                logBehavior(
                                    context = context,
                                    lat = currentLat.toDoubleOrNull() ?: 0.0,
                                    lng = currentLng.toDoubleOrNull() ?: 0.0,
                                    tag = "Indicator Toggled",
                                    direction = detectedTurn,
                                    speed = speed,
                                    distanceToTurn = futureDistance,
                                    indicatorOn = newValue
                                )
                                withContext(Dispatchers.Main) { locationSaveCounter++ }
                            }
                        }
                    )
                }
            }
        }

        item {
            key(locationSaveCounter) {
                SavedLocationsScreen(context)
            }
        }
    }
}

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
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            onCallbackSet(callback)
        }
    } catch (e: SecurityException) {
        Log.e("LocationUpdates", "Security exception requesting location updates", e)
    } catch (e: Exception) {
        Log.e("LocationUpdates", "Error requesting location updates", e)
    }
}

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
    // Clear warning if we've moved away from the warned location
    lastWarnedTurnLocation?.let {
        if (haversineDistance(currentLoc, it) > 10) {
            onWarningUpdate("", null)
        }
    }

    val speedMetersPerSec = max(speed / 3.6f, 0.1f)
    val sortedSteps = steps.map { step ->
        val stepLoc = SimpleLocation(step.lat, step.lng)
        val distance = haversineDistance(currentLoc, stepLoc)
        Pair(step, distance)
    }.sortedBy { it.second }

    var futureDirection = "straight"
    var futureDistance = 0

    for ((step, distance) in sortedSteps) {
        if (distance <= 300) {
            val etaSeconds = distance / speedMetersPerSec

            val stepLoc = SimpleLocation(step.lat, step.lng)
            val turnBearing = calculateBearing(currentLoc, stepLoc)
            val angleDiff = angleBetween(bearing, turnBearing)

            // Skip if the turn is behind us
            if (angleDiff > 90) continue

            val (type, modifier) = step.maneuver.split("-", limit = 2)
            val turnType = when {
                modifier.equals("slight left", true) -> "slight left"
                modifier.equals("sharp left", true) -> "sharp left"
                modifier.contains("left", true) -> "left"
                modifier.equals("slight right", true) -> "slight right"
                modifier.equals("sharp right", true) -> "sharp right"
                modifier.contains("right", true) -> "right"
                else -> "straight"
            }

            if (turnType != "straight") {
                if (futureDirection == "straight") {
                    futureDirection = turnType
                    futureDistance = distance.toInt()
                }

                val adaptiveThreshold = when {
                    speed > 60 -> 6.0
                    speed > 40 -> 8.0
                    else -> 12.0
                }

                if (!indicatorOn && etaSeconds <= adaptiveThreshold) {
                    val warningText = "⚠️ $turnType TURN in ${distance.toInt()}m (~${etaSeconds.toInt()}s) - Indicator OFF!"
                    onWarningUpdate(warningText, stepLoc)
                    onBehaviorLog("Warning Triggered", turnType, distance.toInt())
                    break
                }
            }
        }
    }

    onFutureDirectionUpdate(futureDirection, futureDistance)
}
val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

//changes made
suspend fun fetchOSRMSteps(
    startLat: Double,
    startLng: Double,
    endLat: Double,
    endLng: Double
): List<TurnStep> {
    val url = "https://router.project-osrm.org/route/v1/driving/$startLng,$startLat;$endLng,$endLat?overview=full&steps=true"

    val request = Request.Builder()
        .url(url)
        .addHeader("User-Agent", "SmartIndicator/1.0")
        .build()

    return withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    if (!json.isNullOrEmpty()) {
                        parseSteps(json)
                    } else {
                        Log.e("OSRM", "Empty response body for $url")
                        emptyList()
                    }
                } else {
                    Log.e("OSRM", "HTTP error: ${response.code} for $url")
                    emptyList()
                }
            }
        } catch (e: IOException) {
            Log.e("OSRM", "Network error for $url", e)
            emptyList()
        } catch (e: Exception) {
            Log.e("OSRM", "Unexpected error for $url", e)
            emptyList()
        }
    }
}


fun parseSteps(json: String): List<TurnStep> {
    val list = mutableListOf<TurnStep>()
    try {
        val obj = JSONObject(json)

        // Check if we have a valid response
        if (!obj.has("routes") || obj.getJSONArray("routes").length() == 0) {
            Log.w("OSRM", "No routes found in response")
            return list
        }

        val routes = obj.getJSONArray("routes")
        val route = routes.getJSONObject(0)

        if (!route.has("legs") || route.getJSONArray("legs").length() == 0) {
            Log.w("OSRM", "No legs found in route")
            return list
        }

        val legs = route.getJSONArray("legs")
        val leg = legs.getJSONObject(0)

        if (!leg.has("steps")) {
            Log.w("OSRM", "No steps found in leg")
            return list
        }

        val steps = leg.getJSONArray("steps")

        for (i in 0 until steps.length()) {
            try {
                val step = steps.getJSONObject(i)
                val maneuver = step.getJSONObject("maneuver")
                val location = maneuver.getJSONArray("location")

                val lat = location.getDouble(1)
                val lng = location.getDouble(0)
                val type = maneuver.getString("type")
                val modifier = maneuver.optString("modifier", "straight")

                list.add(TurnStep(lat, lng, "$type-$modifier"))
            } catch (e: Exception) {
                Log.e("OSRM", "Error parsing step $i", e)
            }
        }
    } catch (e: Exception) {
        Log.e("OSRM", "Error parsing JSON", e)
    }
    return list
}
