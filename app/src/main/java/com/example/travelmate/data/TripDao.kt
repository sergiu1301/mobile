package com.example.travelmate.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete

@Dao
interface TripDao {
    @Insert
    suspend fun insertTrip(trip: Trip)

    @Query("SELECT * FROM trips ORDER BY id DESC")
    suspend fun getAllTrips(): List<Trip>

    @Update
    suspend fun updateTrip(trip: Trip)

    @Delete
    suspend fun deleteTrip(trip: Trip)

    @Query("UPDATE trips SET weatherTemp = :temp, weatherDescription = :description WHERE id = :id")
    suspend fun updateWeather(id: Int, temp: String, description: String)

    @Query("SELECT * FROM trips WHERE ownerEmail = :email")
    suspend fun getTripsByUser(email: String): List<Trip>

    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    suspend fun getTripById(tripId: Int): Trip?
}
