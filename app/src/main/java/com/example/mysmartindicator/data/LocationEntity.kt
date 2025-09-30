package com.example.mysmartindicator.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String? = null,
    val direction: String? = null,
    val speed: Float? = null,
    val distanceToTurn: Int? = null,
    val indicatorOn: Boolean? = null,
    // NEW FIELDS for warning locations
    val warningLatitude: Double? = null,    // Latitude where warning was triggered
    val warningLongitude: Double? = null,   // Longitude where warning was triggered
    val isWarningLocation: Boolean = false  // Flag to identify warning locations
)

