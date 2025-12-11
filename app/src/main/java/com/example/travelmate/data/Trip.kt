package com.example.travelmate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,

    var title: String,
    var destination: String,
    var startDate: String,
    var endDate: String,
    var notes: String,

    var  ownerEmail: String,

    var weatherTemp: String? = null,
    var weatherDescription: String? = null,

    var isSynced: Boolean = false
)

