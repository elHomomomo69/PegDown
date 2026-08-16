package com.dpm.pegdown.model

// Aufzeichnungs- und Automatik-Modus ---
enum class RecordingMode {
    MANUAL,        // Komplett manuell
    AUTO_IDLE,     // Automatik-Modus aktiv, wartet auf > 7 km/h
    AUTO_RECORDING // Automatik-Modus hat automatisch gestartet
}