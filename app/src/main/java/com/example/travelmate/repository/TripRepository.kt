package com.example.travelmate.repository

import com.example.travelmate.data.Trip
import com.example.travelmate.data.TripDao

class TripRepository(private val tripDao: TripDao) {

    suspend fun insertTrip(trip: Trip) = tripDao.insertTrip(trip)

    suspend fun getAllTrips(): List<Trip> = tripDao.getAllTrips()

    suspend fun updateTrip(trip: Trip) = tripDao.updateTrip(trip)

    suspend fun deleteTrip(trip: Trip) = tripDao.deleteTrip(trip)

    suspend fun updateWeather(id: Int, temp: String, description: String) {
        tripDao.updateWeather(id, temp, description)
    }

    suspend fun getPendingTrips(): List<Trip> = tripDao.getPendingTrips()

    suspend fun updatePendingStatus(id: Int, pending: Boolean) {
        tripDao.updatePendingStatus(id, pending)
    }

    suspend fun getTripsForUser(email: String): List<Trip> {
        return tripDao.getTripsByUser(email)
    }

    suspend fun getTripById(tripId: Int): Trip? {
        return tripDao.getTripById(tripId)
    }
}
