package com.example.mysmartindicator.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TurnEventDao {
    @Insert
    suspend fun insert(location: LocationEntity)

    @Query("SELECT * FROM locations ORDER BY timestamp DESC")
    suspend fun getAll(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE isWarningLocation = 1 ORDER BY timestamp DESC")
    suspend fun getWarningLocations(): List<LocationEntity>

    @Query("SELECT COUNT(*) FROM locations WHERE isWarningLocation = 1")
    suspend fun getWarningLocationCount(): Int

    @Query("SELECT * FROM locations WHERE isWarningLocation = 1 AND latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng")
    suspend fun getWarningLocationsInArea(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<LocationEntity>
}
