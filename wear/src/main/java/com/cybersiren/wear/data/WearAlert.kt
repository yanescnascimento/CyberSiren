package com.cybersiren.wear.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WearVehicleType(val displayName: String, val emoji: String) {
    AMBULANCE("Ambulance", ""),
    POLICE("Police", ""),
    FIRE_TRUCK("Fire Truck", ""),
    EMERGENCY("Emergency", "")
}

enum class WearUrgency { CRITICAL, HIGH, MEDIUM, LOW }

data class WearAlert(
    val id: String,
    val vehicleType: WearVehicleType,
    val distanceMeters: Float,
    val direction: String,
    val urgency: WearUrgency,
    val ageSeconds: Int = 0
) {
    val distanceLabel: String
        get() = if (distanceMeters < 1000f) "${distanceMeters.toInt()}m"
        else "%.1fkm".format(distanceMeters / 1000f)
}

object WearAlertRepository {
    private val _alerts = MutableStateFlow<List<WearAlert>>(emptyList())
    val alerts: StateFlow<List<WearAlert>> = _alerts.asStateFlow()

    fun update(list: List<WearAlert>) {
        _alerts.value = list
    }
}
