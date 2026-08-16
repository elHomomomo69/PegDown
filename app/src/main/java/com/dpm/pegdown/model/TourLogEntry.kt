package com.dpm.pegdown.model

// Datenklasse für gespeicherte Events (Peaks / Tour-Max)
data class TourLogEntry(
    val timestamp: String,
    val leanAngleLeft: Double,
    val leanAngleRight: Double,
    val acceleration: Double,
    val braking: Double,
    val lat: Double,
    val lon: Double,
    val speed: Double
)