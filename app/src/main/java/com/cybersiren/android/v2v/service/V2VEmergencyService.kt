package com.cybersiren.android.v2v.service

import android.content.Context
import android.location.Location
import android.util.Log
import com.cybersiren.android.geohash.LocationProvider
import com.cybersiren.android.mesh.BluetoothMeshService
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.protocol.MessageType
import com.cybersiren.android.protocol.SpecialRecipients
import com.cybersiren.android.v2v.model.*
import com.cybersiren.android.v2v.model.TransportType
import com.cybersiren.android.v2v.model.TransportDirection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.cybersiren.android.online.FirebaseTransport
import com.cybersiren.android.online.EmergencyAlert as FirebaseEmergencyAlert

class V2VEmergencyService(
    private val context: Context,
    private val meshService: BluetoothMeshService,
    private val locationProvider: LocationProvider,
    private val myPeerId: String
) {
    companion object {
        private const val TAG = "V2VEmergencyService"
        private const val BROADCAST_INTERVAL_MS = 1000L
        private const val LOCATION_UPDATE_INTERVAL_MS = 500L
        private const val LOCATION_MIN_DISTANCE_M = 1f
        private const val EMERGENCY_TTL: UByte = 7u
    }

    private val deduplicationService = AlertDeduplicationService()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var broadcastJob: Job? = null
    private var locationUpdateJob: Job? = null

    private val _isEmergencyActive = MutableStateFlow(false)
    val isEmergencyActive: StateFlow<Boolean> = _isEmergencyActive.asStateFlow()

    private val _currentVehicleType = MutableStateFlow(VehicleType.AMBULANCE)
    val currentVehicleType: StateFlow<VehicleType> = _currentVehicleType.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _currentSpeed = MutableStateFlow(0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _currentHeading = MutableStateFlow(0f)
    val currentHeading: StateFlow<Float> = _currentHeading.asStateFlow()

    private val _receivedAlerts = MutableStateFlow<List<ReceivedAlert>>(emptyList())
    val receivedAlerts: StateFlow<List<ReceivedAlert>> = _receivedAlerts.asStateFlow()

    var delegate: V2VEmergencyDelegate? = null

    private var locationCallback: ((Location) -> Unit)? = null

    init {
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        locationCallback = { location ->
            _currentLocation.value = location
            _currentSpeed.value = location.speed
            _currentHeading.value = location.bearing
        }

        locationCallback?.let { callback ->
            locationProvider.requestLocationUpdates(
                intervalMs = LOCATION_UPDATE_INTERVAL_MS,
                minDistanceMeters = LOCATION_MIN_DISTANCE_M,
                callback = callback
            )
        }

        Log.d(TAG, "Location updates started")
    }

    fun setVehicleType(vehicleType: VehicleType) {
        _currentVehicleType.value = vehicleType
        Log.d(TAG, "Vehicle type set to: ${vehicleType.displayName}")
    }

    fun startEmergencyBroadcast(vehicleType: VehicleType = _currentVehicleType.value) {
        if (_isEmergencyActive.value) {
            Log.w(TAG, "Emergency broadcast already active")
            return
        }

        _currentVehicleType.value = vehicleType
        _isEmergencyActive.value = true

        broadcastJob = serviceScope.launch {
            Log.i(TAG, "Starting emergency broadcast as ${vehicleType.displayName}")

            while (isActive && _isEmergencyActive.value) {
                val location = _currentLocation.value
                if (location != null) {
                    broadcastEmergencyAlert(location)
                } else {
                    Log.w(TAG, "No location available for broadcast")
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        }

        delegate?.onEmergencyBroadcastStarted(vehicleType)
    }

    fun stopEmergencyBroadcast() {
        if (!_isEmergencyActive.value) {
            Log.w(TAG, "Emergency broadcast not active")
            return
        }

        _isEmergencyActive.value = false
        broadcastJob?.cancel()
        broadcastJob = null

        Log.i(TAG, "Emergency broadcast stopped")
        delegate?.onEmergencyBroadcastStopped()
    }

    fun toggleEmergencyBroadcast(): Boolean {
        if (_isEmergencyActive.value) {
            stopEmergencyBroadcast()
        } else {
            startEmergencyBroadcast()
        }
        return _isEmergencyActive.value
    }

    private fun broadcastEmergencyAlert(location: Location) {
        val alert = EmergencyAlert(
            vehicleType = _currentVehicleType.value,
            alertType = determineAlertType(location),
            latitude = location.latitude,
            longitude = location.longitude,
            speed = location.speed,
            heading = location.bearing,
            senderPeerId = myPeerId
        )

        val packet = createAlertPacket(alert)
        val bleStart = System.currentTimeMillis()
        try {
            meshService.broadcastPacket(packet)
            val bleLatency = System.currentTimeMillis() - bleStart
            TransportLogRepository.logSend(
                transport = TransportType.BLE,
                messageId = alert.messageId,
                latencyMs = bleLatency,
                payloadBytes = packet.payload.size,
                details = "peer=${myPeerId.take(8)}"
            )
            Log.d(TAG, "V2V: Broadcast via BLE Mesh (${bleLatency}ms)")
        } catch (e: Exception) {
            TransportLogRepository.logFailure(
                transport = TransportType.BLE,
                direction = TransportDirection.SEND,
                messageId = alert.messageId,
                details = "BLE send error: ${e.message}"
            )
            Log.w(TAG, "V2V: BLE send failed: ${e.message}")
        }

        serviceScope.launch {
            val fbStart = System.currentTimeMillis()
            try {
                val firebaseTransport = FirebaseTransport.getInstance(context)
                if (firebaseTransport.isAvailable) {

                    val firebaseAlert = FirebaseEmergencyAlert(
                        messageId = alert.messageId,
                        type = FirebaseEmergencyAlert.AlertType.EMERGENCY,
                        vehicleType = when (alert.vehicleType) {
                            VehicleType.AMBULANCE -> FirebaseEmergencyAlert.VehicleType.AMBULANCE
                            VehicleType.POLICE_CAR -> FirebaseEmergencyAlert.VehicleType.POLICE
                            VehicleType.FIRE_TRUCK -> FirebaseEmergencyAlert.VehicleType.FIRE_TRUCK
                            VehicleType.EMERGENCY -> FirebaseEmergencyAlert.VehicleType.OTHER
                        },
                        latitude = alert.latitude,
                        longitude = alert.longitude,
                        speedKmh = (alert.speed * 3.6).toInt(),
                        heading = alert.heading.toInt()
                    )

                    firebaseTransport.sendEmergencyAlert(firebaseAlert)
                    val fbLatency = System.currentTimeMillis() - fbStart
                    TransportLogRepository.logSend(
                        transport = TransportType.FIREBASE,
                        messageId = alert.messageId,
                        latencyMs = fbLatency,
                        payloadBytes = alert.toPayload().size,
                        details = "channel=emergency"
                    )
                    Log.i(TAG, "V2N: Broadcast via Firebase Cloud (${fbLatency}ms)")
                } else {
                    Log.d(TAG, "V2N: Firebase not available (offline mode)")
                }
            } catch (e: Exception) {
                TransportLogRepository.logFailure(
                    transport = TransportType.FIREBASE,
                    direction = TransportDirection.SEND,
                    messageId = alert.messageId,
                    details = "Firebase send error: ${e.message}"
                )
                Log.w(TAG, "V2N: Firebase send failed: ${e.message}")
            }
        }

        Log.d(TAG, "Dual broadcast: ${alert.vehicleType.displayName} at ${alert.latitude}, ${alert.longitude}")
    }

    private fun determineAlertType(location: Location): AlertType {
        return when {
            location.speed < 1f -> AlertType.STATIONARY
            location.speed < 5f -> AlertType.PASSING
            else -> AlertType.APPROACHING
        }
    }

    private fun createAlertPacket(alert: EmergencyAlert): BitchatPacket {
        return BitchatPacket(
            version = 1u,
            type = MessageType.EMERGENCY_ALERT.value,
            senderID = hexStringToByteArray(myPeerId),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = alert.timestamp.toULong(),
            payload = alert.toPayload(),
            signature = null,
            ttl = EMERGENCY_TTL
        )
    }

    fun processIncomingAlert(packet: BitchatPacket, fromPeerId: String): EmergencyAlert? {
        val receiveTimestamp = System.currentTimeMillis()

        val alert = EmergencyAlert.fromPayload(packet.payload)
        if (alert == null) {
            Log.w(TAG, "Failed to decode emergency alert from $fromPeerId")
            TransportLogRepository.logFailure(
                transport = TransportType.BLE,
                direction = TransportDirection.RECEIVE,
                messageId = "unknown",
                details = "Decode failed from $fromPeerId"
            )
            return null
        }

        if (!deduplicationService.checkAndMark(alert.messageId)) {
            Log.d(TAG, "Duplicate alert ignored: ${alert.messageId}")
            return null
        }

        val bleLatency = receiveTimestamp - alert.timestamp
        TransportLogRepository.logReceive(
            transport = TransportType.BLE,
            messageId = alert.messageId,
            latencyMs = bleLatency,
            payloadBytes = packet.payload.size,
            details = "from=${fromPeerId.take(8)}"
        )

        val myLocation = _currentLocation.value
        val distance = if (myLocation != null) {
            calculateDistance(
                myLocation.latitude, myLocation.longitude,
                alert.latitude, alert.longitude
            )
        } else {
            Float.MAX_VALUE
        }

        val relativeDirection = if (myLocation != null) {
            calculateRelativeDirection(myLocation, alert)
        } else {
            ""
        }

        val receivedAlert = ReceivedAlert(
            alert = alert,
            distanceMeters = distance,
            relativeDirection = relativeDirection
        )

        addReceivedAlert(receivedAlert)

        delegate?.onEmergencyAlertReceived(receivedAlert)

        Log.i(TAG, "Received emergency alert: ${alert.vehicleType.displayName} at ${distance}m (latency: ${bleLatency}ms)")

        return alert
    }

    private fun addReceivedAlert(alert: ReceivedAlert) {
        val currentList = _receivedAlerts.value.toMutableList()

        currentList.removeAll { !it.isValid }

        currentList.removeAll { it.alert.senderPeerId == alert.alert.senderPeerId }

        currentList.add(alert)

        currentList.sortBy { it.distanceMeters }

        _receivedAlerts.value = currentList
    }

    fun cleanupExpiredAlerts() {
        val currentList = _receivedAlerts.value.toMutableList()
        val sizeBefore = currentList.size
        currentList.removeAll { !it.isValid }

        if (currentList.size != sizeBefore) {
            _receivedAlerts.value = currentList
            Log.d(TAG, "Cleaned up ${sizeBefore - currentList.size} expired alerts")
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    private fun calculateRelativeDirection(myLocation: Location, alert: EmergencyAlert): String {
        val bearing = calculateBearing(
            myLocation.latitude, myLocation.longitude,
            alert.latitude, alert.longitude
        )

        val myHeading = myLocation.bearing
        var relativeBearing = bearing - myHeading
        if (relativeBearing < 0) relativeBearing += 360
        if (relativeBearing > 180) relativeBearing -= 360

        return when {
            relativeBearing in -45.0..45.0 -> "ahead"
            relativeBearing in 45.0..135.0 -> "right"
            relativeBearing in -135.0..-45.0 -> "left"
            else -> "behind"
        }
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val lonDiff = Math.toRadians(lon2 - lon1)

        val x = Math.sin(lonDiff) * Math.cos(lat2Rad)
        val y = Math.cos(lat1Rad) * Math.sin(lat2Rad) -
                Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(lonDiff)

        var bearing = Math.toDegrees(Math.atan2(x, y))
        if (bearing < 0) bearing += 360

        return bearing
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0

        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }

        return result
    }

    fun getDeduplicationService(): AlertDeduplicationService = deduplicationService

    fun shutdown() {
        stopEmergencyBroadcast()

        locationCallback?.let { callback ->
            locationProvider.removeLocationUpdates(callback)
        }
        locationCallback = null

        deduplicationService.shutdown()
        serviceScope.cancel()

        Log.i(TAG, "V2VEmergencyService shutdown")
    }
}

interface V2VEmergencyDelegate {
    fun onEmergencyAlertReceived(alert: ReceivedAlert)
    fun onEmergencyBroadcastStarted(vehicleType: VehicleType)
    fun onEmergencyBroadcastStopped()
}
