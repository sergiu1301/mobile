package com.example.travelmate.repository

import com.example.travelmate.data.Trip
import com.example.travelmate.data.TripDao

class TripRepository(private val tripDao: TripDao) {

    suspend fun insertTrip(trip: Trip): Long {
        return tripDao.insertTrip(trip)
    }

    suspend fun getAllTrips(): List<Trip> = tripDao.getAllTrips()

    suspend fun updateTrip(trip: Trip) = tripDao.updateTrip(trip)

    suspend fun deleteTrip(trip: Trip) = tripDao.deleteTrip(trip)

    suspend fun updateWeather(id: Int, temp: String, description: String) {
        tripDao.updateWeather(id, temp, description)
    }

    suspend fun getTripsForUser(email: String): List<Trip> {
        return tripDao.getTripsByUser(email)
    }

    suspend fun getTripById(tripId: Int): Trip? {
        return tripDao.getTripById(tripId)
    }

    suspend fun replaceAllForUser(email: String, newTrips: List<Trip>) {
        tripDao.deleteAllForUser(email)
        tripDao.insertMany(newTrips)
    }

}
