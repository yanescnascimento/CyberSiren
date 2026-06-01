package com.cybersiren.android.v2v.auto

import com.cybersiren.android.v2v.model.AlertMode
import com.cybersiren.android.v2v.model.ReceivedAlert
import com.cybersiren.android.v2v.model.VehicleType

interface V2VCarServiceInterface {
    fun getActiveAlerts(): List<ReceivedAlert>
    fun getAlertMode(): AlertMode
    fun setMode(mode: AlertMode)
    fun isEmergencyActive(): Boolean
    fun toggleEmergencyBroadcast()
    fun startEmergencyBroadcast(vehicleType: VehicleType)
    fun stopEmergencyBroadcast()
    fun getConnectedPeers(): Int

    fun getSelectedVehicleType(): VehicleType
    fun selectVehicleType(type: VehicleType)
    fun getCurrentLatitude(): Double?
    fun getCurrentLongitude(): Double?
    fun getCurrentSpeedKmh(): Float
    fun getCurrentHeadingDegrees(): Float
}

object V2VCarServiceHolder {
    @Volatile
    private var service: V2VCarServiceInterface? = null

    fun setService(service: V2VCarServiceInterface?) {
        this.service = service
    }

    fun getService(): V2VCarServiceInterface? = service

    fun isServiceAvailable(): Boolean = service != null
}
