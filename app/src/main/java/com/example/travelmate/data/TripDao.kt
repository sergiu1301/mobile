package com.example.travelmate.data

import androidx.room.*

@Dao
interface TripDao {

    // INSERT ONE
    @Insert
    suspend fun insertTrip(trip: Trip): Long

    // INSERT MANY (for server sync)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMany(trips: List<Trip>)

    // GET ALL (fallback)
    @Query("SELECT * FROM trips ORDER BY id DESC")
    suspend fun getAllTrips(): List<Trip>

    // GET trips for logged in user by EMAIL
    @Query("SELECT * FROM trips WHERE ownerEmail = :email ORDER BY id DESC")
    suspend fun getTripsByUser(email: String): List<Trip>

    // GET by ID
    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    suspend fun getTripById(tripId: Int): Trip?

    // UPDATE one
    @Update
    suspend fun updateTrip(trip: Trip)

    // DELETE one
    @Delete
    suspend fun deleteTrip(trip: Trip)

    // DELETE ALL for a given user (used when replacing local cache)
    @Query("DELETE FROM trips WHERE ownerEmail = :email")
    suspend fun deleteAllForUser(email: String)

    // WEATHER UPDATE ONLY
    @Query("UPDATE trips SET weatherTemp = :temp, weatherDescription = :description WHERE id = :id")
    suspend fun updateWeather(id: Int, temp: String, description: String)
}
