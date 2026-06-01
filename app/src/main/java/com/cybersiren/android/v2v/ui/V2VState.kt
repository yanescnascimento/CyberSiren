package com.cybersiren.android.v2v.ui

import android.location.Location
import com.cybersiren.android.v2v.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class V2VState(private val scope: CoroutineScope) {

    private val _alertMode = MutableStateFlow(AlertMode.RECEIVER)
    val alertMode: StateFlow<AlertMode> = _alertMode.asStateFlow()

    private val _isEmergencyActive = MutableStateFlow(false)
    val isEmergencyActive: StateFlow<Boolean> = _isEmergencyActive.asStateFlow()

    private val _selectedVehicleType = MutableStateFlow(VehicleType.AMBULANCE)
    val selectedVehicleType: StateFlow<VehicleType> = _selectedVehicleType.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _currentSpeed = MutableStateFlow(0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _currentHeading = MutableStateFlow(0f)
    val currentHeading: StateFlow<Float> = _currentHeading.asStateFlow()

    private val _activeAlerts = MutableStateFlow<List<ReceivedAlert>>(emptyList())
    val activeAlerts: StateFlow<List<ReceivedAlert>> = _activeAlerts.asStateFlow()

    private val _connectedPeers = MutableStateFlow(0)
    val connectedPeers: StateFlow<Int> = _connectedPeers.asStateFlow()

    private val _connectedDevices = MutableStateFlow(0)
    val connectedDevices: StateFlow<Int> = _connectedDevices.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val speedKmh: Float
        get() = _currentSpeed.value * 3.6f

    val headingDirection: String
        get() {
            val heading = _currentHeading.value
            return when {
                heading >= 337.5 || heading < 22.5 -> "N"
                heading >= 22.5 && heading < 67.5 -> "NE"
                heading >= 67.5 && heading < 112.5 -> "E"
                heading >= 112.5 && heading < 157.5 -> "SE"
                heading >= 157.5 && heading < 202.5 -> "S"
                heading >= 202.5 && heading < 247.5 -> "SW"
                heading >= 247.5 && heading < 292.5 -> "W"
                heading >= 292.5 && heading < 337.5 -> "NW"
                else -> "?"
            }
        }

    fun setAlertMode(mode: AlertMode) {
        _alertMode.value = mode
    }

    fun setEmergencyActive(active: Boolean) {
        _isEmergencyActive.value = active
    }

    fun setSelectedVehicleType(type: VehicleType) {
        _selectedVehicleType.value = type
    }

    fun updateLocation(location: Location?) {
        _currentLocation.value = location
        location?.let {
            _currentSpeed.value = it.speed
            _currentHeading.value = it.bearing
        }
    }

    fun setConnectedPeers(count: Int) {
        _connectedPeers.value = count
    }

    fun setConnectedDevices(count: Int) {
        _connectedDevices.value = count
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun setError(message: String?) {
        _errorMessage.value = message
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun addAlert(alert: ReceivedAlert) {
        val currentList = _activeAlerts.value.toMutableList()

        currentList.removeAll { !it.isValid }

        currentList.removeAll { it.alert.senderPeerId == alert.alert.senderPeerId }

        currentList.add(alert)

        currentList.sortBy { it.distanceMeters }

        _activeAlerts.value = currentList
    }

    fun removeExpiredAlerts() {
        val currentList = _activeAlerts.value.toMutableList()
        val sizeBefore = currentList.size
        currentList.removeAll { !it.isValid }

        if (currentList.size != sizeBefore) {
            _activeAlerts.value = currentList
        }
    }

    fun clearAlerts() {
        _activeAlerts.value = emptyList()
    }

    fun getMostUrgentAlert(): ReceivedAlert? {
        return _activeAlerts.value.firstOrNull()
    }

    fun hasCriticalAlerts(): Boolean {
        return _activeAlerts.value.any { it.urgencyLevel == UrgencyLevel.CRITICAL }
    }
}
