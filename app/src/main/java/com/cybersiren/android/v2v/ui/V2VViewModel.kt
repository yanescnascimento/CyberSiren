package com.cybersiren.android.v2v.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cybersiren.android.geohash.FusedLocationProvider
import com.cybersiren.android.mesh.BluetoothMeshDelegate
import com.cybersiren.android.mesh.BluetoothMeshService
import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.v2v.auto.V2VCarNotifier
import com.cybersiren.android.v2v.auto.V2VCarServiceHolder
import com.cybersiren.android.v2v.auto.V2VCarServiceInterface
import com.cybersiren.android.v2v.model.*
import com.cybersiren.android.v2v.service.V2VEmergencyDelegate
import com.cybersiren.android.v2v.service.V2VEmergencyService
import com.cybersiren.android.v2v.ui.mock.V2VMockEngine
import com.cybersiren.android.v2v.service.TransportLogRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class V2VViewModel(
    application: Application,
    private val meshService: BluetoothMeshService
) : AndroidViewModel(application), V2VEmergencyDelegate, V2VCarServiceInterface, BluetoothMeshDelegate {

    companion object {
        private const val TAG = "V2VViewModel"
        private const val ALERT_CLEANUP_INTERVAL_MS = 5000L

        private const val ALERT_REACTION_THROTTLE_MS = 5000L
    }

    private val lastReactionAtBySender = mutableMapOf<String, Long>()
    private val lastUrgencyBySender = mutableMapOf<String, UrgencyLevel>()

    private val state = V2VState(viewModelScope)

    private val prefs = application.getSharedPreferences("v2v_prefs", Context.MODE_PRIVATE)

    private val _mockEnabled = MutableStateFlow(false)
    val mockEnabled: StateFlow<Boolean> = _mockEnabled.asStateFlow()
    private var mockJob: Job? = null

    private val _silentMode = MutableStateFlow(false)
    val silentMode: StateFlow<Boolean> = _silentMode.asStateFlow()

    private val locationProvider = FusedLocationProvider(application)
    private val v2vService: V2VEmergencyService

    private var cleanupJob: Job? = null

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val alertMode: StateFlow<AlertMode> = state.alertMode
    val isEmergencyActive: StateFlow<Boolean> = state.isEmergencyActive
    val selectedVehicleType: StateFlow<VehicleType> = state.selectedVehicleType
    val currentLocation = state.currentLocation
    val currentSpeed = state.currentSpeed
    val currentHeading = state.currentHeading
    val activeAlerts: StateFlow<List<ReceivedAlert>> = state.activeAlerts
    val connectedPeers: StateFlow<Int> = state.connectedPeers
    val connectedDevices: StateFlow<Int> = state.connectedDevices
    val isServiceRunning: StateFlow<Boolean> = state.isServiceRunning
    val errorMessage: StateFlow<String?> = state.errorMessage

    val transportLogs = TransportLogRepository.logs
    val bleAvgLatency = TransportLogRepository.bleAvgLatency
    val firebaseAvgLatency = TransportLogRepository.firebaseAvgLatency
    val bleLossPercent = TransportLogRepository.bleLossPercent
    val firebaseLossPercent = TransportLogRepository.firebaseLossPercent
    val bleSendCount = TransportLogRepository.bleSendCount
    val bleRecvCount = TransportLogRepository.bleRecvCount
    val firebaseSendCount = TransportLogRepository.firebaseSendCount
    val firebaseRecvCount = TransportLogRepository.firebaseRecvCount

    init {

        meshService.connectionManager.powerManager.forcePerformanceMode = true
        Log.i(TAG, "V2V: Enabled continuous scanning (forcePerformanceMode)")

        v2vService = V2VEmergencyService(
            context = application,
            meshService = meshService,
            locationProvider = locationProvider,
            myPeerId = meshService.myPeerID
        )
        v2vService.delegate = this

        viewModelScope.launch {
            v2vService.currentLocation.collect { location ->
                if (!_mockEnabled.value) {
                    state.updateLocation(location)
                }
            }
        }

        viewModelScope.launch {
            v2vService.isEmergencyActive.collect { active ->
                state.setEmergencyActive(active)
            }
        }

        viewModelScope.launch {
            v2vService.receivedAlerts.collect { alerts ->

            }
        }

        startAlertCleanup()

        viewModelScope.launch {
            while (true) {
                try {

                    val deviceCount = meshService.connectionManager.getConnectedDeviceCount()
                    state.setConnectedDevices(deviceCount)
                    Log.d(TAG, "Device count: $deviceCount, Peer count: ${state.connectedPeers.value}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating device count: ${e.message}")
                }
                delay(2000)
            }
        }

        V2VCarServiceHolder.setService(this)

        runCatching { TransportLogRepository.startSessionLog(application) }
            .onSuccess { Log.i(TAG, "Session log: $it") }
            .onFailure { Log.w(TAG, "Could not open session log: ${it.message}") }

        setMockEnabled(prefs.getBoolean("mock_enabled", false))

        _silentMode.value = prefs.getBoolean("silent_mode", false)

        state.setServiceRunning(true)
        Log.i(TAG, "V2VViewModel initialized")
    }

    fun toggleMode() {
        val newMode = if (state.alertMode.value == AlertMode.SENDER) {
            AlertMode.RECEIVER
        } else {
            AlertMode.SENDER
        }
        setMode(newMode)
    }

    override fun setMode(mode: AlertMode) {

        if (state.alertMode.value == AlertMode.SENDER && mode == AlertMode.RECEIVER) {
            if (state.isEmergencyActive.value) {
                stopEmergencyBroadcast()
            }
        }
        state.setAlertMode(mode)
        Log.d(TAG, "Mode changed to: $mode")
    }

    override fun selectVehicleType(type: VehicleType) {
        state.setSelectedVehicleType(type)
        v2vService.setVehicleType(type)
        Log.d(TAG, "Vehicle type selected: ${type.displayName}")
    }

    fun startEmergencyBroadcast() {
        if (state.alertMode.value != AlertMode.SENDER) {
            state.setError("Mude para modo SENDER primeiro")
            return
        }

        v2vService.startEmergencyBroadcast(state.selectedVehicleType.value)
        triggerHapticFeedback(HapticType.BROADCAST_START)
    }

    override fun stopEmergencyBroadcast() {
        v2vService.stopEmergencyBroadcast()
        triggerHapticFeedback(HapticType.BROADCAST_STOP)
    }

    override fun toggleEmergencyBroadcast() {
        if (state.isEmergencyActive.value) {
            stopEmergencyBroadcast()
        } else {
            startEmergencyBroadcast()
        }
    }

    fun processIncomingAlert(packet: BitchatPacket, fromPeerId: String) {
        v2vService.processIncomingAlert(packet, fromPeerId)
    }

    fun updateConnectedPeers(count: Int) {
        state.setConnectedPeers(count)
    }

    fun clearError() {
        state.clearError()
    }

    fun clearTransportLogs() {
        TransportLogRepository.clearLogs()
    }

    fun sessionLogPath(): String? = TransportLogRepository.currentSessionLogPath()

    fun setSilentMode(enabled: Boolean) {
        _silentMode.value = enabled
        prefs.edit { putBoolean("silent_mode", enabled) }
        if (enabled) {

            runCatching { V2VCarNotifier.cancelAll(getApplication()) }
        }
    }

    override fun onEmergencyAlertReceived(alert: ReceivedAlert) {
        Log.i(TAG, "Emergency alert received: ${alert.alert.vehicleType.displayName} at ${alert.distanceDisplay}")

        state.addAlert(alert)

        if (_silentMode.value) {
            lastUrgencyBySender[alert.alert.senderPeerId] = alert.urgencyLevel
            return
        }

        val senderId = alert.alert.senderPeerId
        val now = System.currentTimeMillis()
        val lastAt = lastReactionAtBySender[senderId] ?: 0L
        val lastUrgency = lastUrgencyBySender[senderId]
        val urgencyEscalated = lastUrgency != null && alert.urgencyLevel.ordinal < lastUrgency.ordinal
        val firstContact = lastUrgency == null
        val shouldAlert = firstContact || urgencyEscalated || (now - lastAt) >= ALERT_REACTION_THROTTLE_MS

        runCatching { V2VCarNotifier.notifyAlert(getApplication(), alert, alertUser = shouldAlert) }
            .onFailure { Log.w(TAG, "Failed to publish car alert notification: ${it.message}") }

        if (shouldAlert) {
            when (alert.urgencyLevel) {
                UrgencyLevel.CRITICAL -> {
                    triggerHapticFeedback(HapticType.CRITICAL_ALERT)
                    playAlertSound(AlertSoundType.CRITICAL)
                }
                UrgencyLevel.HIGH -> {
                    triggerHapticFeedback(HapticType.HIGH_ALERT)
                    playAlertSound(AlertSoundType.HIGH)
                }
                UrgencyLevel.MEDIUM -> {
                    triggerHapticFeedback(HapticType.MEDIUM_ALERT)
                    playAlertSound(AlertSoundType.MEDIUM)
                }
                UrgencyLevel.LOW -> {
                    triggerHapticFeedback(HapticType.LOW_ALERT)
                }
            }
            lastReactionAtBySender[senderId] = now
        }
        lastUrgencyBySender[senderId] = alert.urgencyLevel
    }

    override fun onEmergencyBroadcastStarted(vehicleType: VehicleType) {
        Log.i(TAG, "Emergency broadcast started: ${vehicleType.displayName}")
        state.setEmergencyActive(true)
    }

    override fun onEmergencyBroadcastStopped() {
        Log.i(TAG, "Emergency broadcast stopped")
        state.setEmergencyActive(false)
    }

    private fun startAlertCleanup() {
        cleanupJob = viewModelScope.launch {
            while (isActive) {
                delay(ALERT_CLEANUP_INTERVAL_MS)
                state.removeExpiredAlerts()
                v2vService.cleanupExpiredAlerts()

                val activeIds = state.activeAlerts.value.map { it.alert.senderPeerId }.toSet()
                lastReactionAtBySender.keys.retainAll(activeIds)
                lastUrgencyBySender.keys.retainAll(activeIds)

                runCatching { V2VCarNotifier.syncWithActive(getApplication(), state.activeAlerts.value) }
            }
        }
    }

    fun setMockEnabled(enabled: Boolean) {
        if (_mockEnabled.value == enabled) return
        _mockEnabled.value = enabled
        prefs.edit { putBoolean("mock_enabled", enabled) }

        if (enabled) {
            startMocking()
        } else {
            stopMocking()
        }
    }

    private fun startMocking() {
        mockJob?.cancel()
        mockJob = viewModelScope.launch {
            while (isActive) {
                val snap = V2VMockEngine.nextSnapshot(seedLocation = state.currentLocation.value)
                state.updateLocation(snap.location)
                state.setConnectedPeers(snap.peers)
                state.clearAlerts()
                snap.alerts.forEach { state.addAlert(it) }
                delay(1500L)
            }
        }
    }

    private fun stopMocking() {
        mockJob?.cancel()
        mockJob = null
        state.clearAlerts()
        state.setConnectedPeers(0)
    }

    private fun triggerHapticFeedback(type: HapticType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (type) {
                    HapticType.CRITICAL_ALERT -> VibrationEffect.createWaveform(
                        longArrayOf(0, 200, 100, 200, 100, 200),
                        -1
                    )
                    HapticType.HIGH_ALERT -> VibrationEffect.createWaveform(
                        longArrayOf(0, 150, 100, 150),
                        -1
                    )
                    HapticType.MEDIUM_ALERT -> VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                    HapticType.LOW_ALERT -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    HapticType.BROADCAST_START -> VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                    HapticType.BROADCAST_STOP -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                when (type) {
                    HapticType.CRITICAL_ALERT -> vibrator.vibrate(longArrayOf(0, 200, 100, 200, 100, 200), -1)
                    HapticType.HIGH_ALERT -> vibrator.vibrate(longArrayOf(0, 150, 100, 150), -1)
                    HapticType.MEDIUM_ALERT -> vibrator.vibrate(150)
                    HapticType.LOW_ALERT -> vibrator.vibrate(50)
                    HapticType.BROADCAST_START -> vibrator.vibrate(100)
                    HapticType.BROADCAST_STOP -> vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger haptic feedback: ${e.message}")
        }
    }

    private fun playAlertSound(type: AlertSoundType) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val toneType = when (type) {
                    AlertSoundType.CRITICAL -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                    AlertSoundType.HIGH -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                    AlertSoundType.MEDIUM -> ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE
                }
                val duration = when (type) {
                    AlertSoundType.CRITICAL -> 1000
                    AlertSoundType.HIGH -> 500
                    AlertSoundType.MEDIUM -> 300
                }

                val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                toneGenerator.startTone(toneType, duration)
                delay(duration.toLong() + 100)
                toneGenerator.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play alert sound: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanupJob?.cancel()
        v2vService.shutdown()

        runCatching { V2VCarNotifier.cancelAll(getApplication()) }
        mockJob?.cancel()

        V2VCarServiceHolder.setService(null)

        TransportLogRepository.closeSessionLog()

        Log.i(TAG, "V2VViewModel cleared")
    }

    override fun getActiveAlerts(): List<ReceivedAlert> = state.activeAlerts.value

    override fun getAlertMode(): AlertMode = state.alertMode.value

    override fun isEmergencyActive(): Boolean = state.isEmergencyActive.value

    override fun getConnectedPeers(): Int = state.connectedPeers.value

    override fun startEmergencyBroadcast(vehicleType: VehicleType) {
        selectVehicleType(vehicleType)
        startEmergencyBroadcast()
    }

    override fun getSelectedVehicleType(): VehicleType = state.selectedVehicleType.value

    override fun getCurrentLatitude(): Double? = state.currentLocation.value?.latitude

    override fun getCurrentLongitude(): Double? = state.currentLocation.value?.longitude

    override fun getCurrentSpeedKmh(): Float = state.currentSpeed.value * 3.6f

    override fun getCurrentHeadingDegrees(): Float = state.currentHeading.value

    override fun didReceiveMessage(message: BitchatMessage) {

        Log.d(TAG, "Received message (ignored in V2V mode): ${message.content.take(20)}")
    }

    override fun didUpdatePeerList(peers: List<String>) {
        Log.d(TAG, "Peer list updated: ${peers.size} peers")
        state.setConnectedPeers(peers.size)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {

    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {

    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {

    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {

    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {

    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {

        return null
    }

    override fun getNickname(): String? {
        return "V2V-${meshService.myPeerID.take(6)}"
    }

    override fun isFavorite(peerID: String): Boolean {
        return false
    }

    override fun didReceiveEmergencyAlert(packet: BitchatPacket, fromPeerID: String) {
        Log.i(TAG, "Received emergency alert from $fromPeerID")

        v2vService.processIncomingAlert(packet, fromPeerID)
    }

    private enum class HapticType {
        CRITICAL_ALERT,
        HIGH_ALERT,
        MEDIUM_ALERT,
        LOW_ALERT,
        BROADCAST_START,
        BROADCAST_STOP
    }

    private enum class AlertSoundType {
        CRITICAL,
        HIGH,
        MEDIUM
    }
}
