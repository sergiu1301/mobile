package com.example.travelmate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val destination: String,
    val startDate: String,
    val endDate: String,
    val notes: String,
    val ownerEmail: String,
    var weatherTemp: String? = null,
    var weatherDescription: String? = null
)
