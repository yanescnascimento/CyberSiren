package com.cybersiren.android.v2v

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.cybersiren.android.mesh.BluetoothMeshService
import com.cybersiren.android.onboarding.*
import com.cybersiren.android.ui.OrientationAwareActivity
import com.cybersiren.android.ui.theme.BitchatTheme
import com.cybersiren.android.v2v.auto.AndroidAutoConnectionMonitor
import com.cybersiren.android.v2v.ui.V2VScreen
import com.cybersiren.android.v2v.ui.V2VViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.cybersiren.android.online.FirebaseTransport
import com.cybersiren.android.online.MessageOrchestrator
import com.cybersiren.android.online.ConnectivityObserver
import com.cybersiren.android.online.TransportChannel
import com.cybersiren.android.v2v.service.TransportLogRepository
import com.cybersiren.android.v2v.model.TransportType
import com.cybersiren.android.v2v.model.TransportDirection

class V2VActivity : OrientationAwareActivity() {

    companion object {
        private const val TAG = "V2VActivity"
    }

    private lateinit var permissionManager: PermissionManager
    private lateinit var onboardingCoordinator: OnboardingCoordinator
    private lateinit var bluetoothStatusManager: BluetoothStatusManager
    private lateinit var locationStatusManager: LocationStatusManager
    private lateinit var batteryOptimizationManager: BatteryOptimizationManager

    private lateinit var meshService: BluetoothMeshService
    private lateinit var v2vViewModel: V2VViewModel

    private lateinit var androidAutoMonitor: AndroidAutoConnectionMonitor

    private val _onboardingState = mutableStateOf(OnboardingState.CHECKING)
    private val _bluetoothStatus = mutableStateOf(BluetoothStatus.DISABLED)
    private val _locationStatus = mutableStateOf(LocationStatus.DISABLED)
    private val _errorMessage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "V2VActivity onCreate")

        enableEdgeToEdge()

        permissionManager = PermissionManager(this)

        try {
            com.cybersiren.android.service.MeshForegroundService.start(applicationContext)
        } catch (_: Exception) { }

        meshService = com.cybersiren.android.service.MeshServiceHolder.getOrCreate(applicationContext)

        bluetoothStatusManager = BluetoothStatusManager(
            activity = this,
            context = this,
            onBluetoothEnabled = ::handleBluetoothEnabled,
            onBluetoothDisabled = ::handleBluetoothDisabled
        )

        locationStatusManager = LocationStatusManager(
            activity = this,
            context = this,
            onLocationEnabled = ::handleLocationEnabled,
            onLocationDisabled = ::handleLocationDisabled
        )

        batteryOptimizationManager = BatteryOptimizationManager(
            activity = this,
            context = this,
            onBatteryOptimizationDisabled = ::handleBatteryOptimizationDisabled,
            onBatteryOptimizationFailed = ::handleBatteryOptimizationFailed
        )

        androidAutoMonitor = AndroidAutoConnectionMonitor(this)
        lifecycle.addObserver(androidAutoMonitor)

        onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            permissionManager = permissionManager,
            onOnboardingComplete = ::handleOnboardingComplete,
            onBackgroundLocationRequired = {
                _onboardingState.value = OnboardingState.BACKGROUND_LOCATION_EXPLANATION
            },
            onOnboardingFailed = ::handleOnboardingFailed
        )

        setContent {
            BitchatTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    V2VOnboardingFlow(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }

        if (_onboardingState.value == OnboardingState.CHECKING) {
            checkOnboardingStatus()
        }
    }

    @Composable
    private fun V2VOnboardingFlow(modifier: Modifier = Modifier) {
        val onboardingState by remember { _onboardingState }
        val bluetoothStatus by remember { _bluetoothStatus }
        val locationStatus by remember { _locationStatus }
        val errorMessage by remember { _errorMessage }

        when (onboardingState) {
            OnboardingState.CHECKING, OnboardingState.INITIALIZING -> {
                InitializingScreen(modifier)
            }

            OnboardingState.BLUETOOTH_CHECK -> {
                BluetoothCheckScreen(
                    modifier = modifier,
                    status = bluetoothStatus,
                    onEnableBluetooth = {
                        bluetoothStatusManager.requestEnableBluetooth()
                    },
                    onRetry = { checkBluetoothAndProceed() },
                    isLoading = false
                )
            }

            OnboardingState.LOCATION_CHECK -> {
                LocationCheckScreen(
                    modifier = modifier,
                    status = locationStatus,
                    onEnableLocation = {
                        locationStatusManager.requestEnableLocation()
                    },
                    onRetry = { checkLocationAndProceed() },
                    isLoading = false
                )
            }

            OnboardingState.PERMISSION_EXPLANATION -> {
                PermissionExplanationScreen(
                    modifier = modifier,
                    permissionCategories = permissionManager.getCategorizedPermissions(),
                    onContinue = {
                        _onboardingState.value = OnboardingState.PERMISSION_REQUESTING
                        onboardingCoordinator.requestPermissions()
                    }
                )
            }

            OnboardingState.PERMISSION_REQUESTING -> {
                InitializingScreen(modifier)
            }

            OnboardingState.BACKGROUND_LOCATION_EXPLANATION -> {
                BackgroundLocationPermissionScreen(
                    modifier = modifier,
                    onContinue = {
                        onboardingCoordinator.requestBackgroundLocation()
                    },
                    onRetry = {
                        onboardingCoordinator.checkBackgroundLocationAndProceed()
                    },
                    onSkip = {
                        onboardingCoordinator.skipBackgroundLocation()
                    }
                )
            }

            OnboardingState.BATTERY_OPTIMIZATION_CHECK -> {
                BatteryOptimizationScreen(
                    modifier = modifier,
                    status = BatteryOptimizationStatus.ENABLED,
                    onDisableBatteryOptimization = {
                        batteryOptimizationManager.requestDisableBatteryOptimization()
                    },
                    onRetry = { checkBatteryOptimizationAndProceed() },
                    onSkip = { proceedWithPermissionCheck() },
                    isLoading = false
                )
            }

            OnboardingState.COMPLETE -> {

                V2VScreen(viewModel = v2vViewModel, modifier = modifier)
            }

            OnboardingState.ERROR -> {
                InitializationErrorScreen(
                    modifier = modifier,
                    errorMessage = errorMessage ?: "Erro desconhecido",
                    onRetry = {
                        _onboardingState.value = OnboardingState.CHECKING
                        checkOnboardingStatus()
                    },
                    onOpenSettings = {
                        onboardingCoordinator.openAppSettings()
                    }
                )
            }
        }
    }

    private fun checkOnboardingStatus() {
        Log.d(TAG, "Checking onboarding status")
        lifecycleScope.launch {
            delay(500)
            checkBluetoothAndProceed()
        }
    }

    private fun checkBluetoothAndProceed() {
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }

        _bluetoothStatus.value = bluetoothStatusManager.checkBluetoothStatus()

        when (_bluetoothStatus.value) {
            BluetoothStatus.ENABLED -> checkLocationAndProceed()
            BluetoothStatus.DISABLED, BluetoothStatus.NOT_SUPPORTED -> {
                _onboardingState.value = OnboardingState.BLUETOOTH_CHECK
            }
        }
    }

    private fun checkLocationAndProceed() {
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }

        _locationStatus.value = locationStatusManager.checkLocationStatus()

        when (_locationStatus.value) {
            LocationStatus.ENABLED -> checkBatteryOptimizationAndProceed()
            LocationStatus.DISABLED, LocationStatus.NOT_AVAILABLE -> {
                _onboardingState.value = OnboardingState.LOCATION_CHECK
            }
        }
    }

    private fun checkBatteryOptimizationAndProceed() {
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }

        if (BatteryOptimizationPreferenceManager.isSkipped(this)) {
            proceedWithPermissionCheck()
            return
        }

        if (batteryOptimizationManager.isBatteryOptimizationDisabled() ||
            !batteryOptimizationManager.isBatteryOptimizationSupported()) {
            proceedWithPermissionCheck()
        } else {
            _onboardingState.value = OnboardingState.BATTERY_OPTIMIZATION_CHECK
        }
    }

    private fun proceedWithPermissionCheck() {
        lifecycleScope.launch {
            delay(200)

            when {
                permissionManager.isFirstTimeLaunch() -> {
                    _onboardingState.value = OnboardingState.PERMISSION_EXPLANATION
                }
                permissionManager.areRequiredPermissionsGranted() -> {
                    if (permissionManager.needsBackgroundLocationPermission() &&
                        !permissionManager.isBackgroundLocationGranted() &&
                        !BackgroundLocationPreferenceManager.isSkipped(this@V2VActivity)) {
                        _onboardingState.value = OnboardingState.BACKGROUND_LOCATION_EXPLANATION
                    } else {
                        _onboardingState.value = OnboardingState.INITIALIZING
                        initializeApp()
                    }
                }
                else -> {
                    _onboardingState.value = OnboardingState.PERMISSION_EXPLANATION
                }
            }
        }
    }

    private fun handleBluetoothEnabled() {
        _bluetoothStatus.value = BluetoothStatus.ENABLED
        checkLocationAndProceed()
    }

    private fun handleBluetoothDisabled(message: String) {
        Log.w(TAG, "Bluetooth disabled: $message")
        _bluetoothStatus.value = bluetoothStatusManager.checkBluetoothStatus()
        _onboardingState.value = OnboardingState.BLUETOOTH_CHECK
    }

    private fun handleLocationEnabled() {
        _locationStatus.value = LocationStatus.ENABLED
        checkBatteryOptimizationAndProceed()
    }

    private fun handleLocationDisabled(message: String) {
        Log.w(TAG, "Location disabled: $message")
        _locationStatus.value = locationStatusManager.checkLocationStatus()
        _onboardingState.value = OnboardingState.LOCATION_CHECK
    }

    private fun handleBatteryOptimizationDisabled() {
        proceedWithPermissionCheck()
    }

    private fun handleBatteryOptimizationFailed(message: String) {
        Log.w(TAG, "Battery optimization failed: $message")
        _onboardingState.value = OnboardingState.BATTERY_OPTIMIZATION_CHECK
    }

    private fun handleOnboardingComplete() {
        Log.d(TAG, "Onboarding complete")

        val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
        val currentLocationStatus = locationStatusManager.checkLocationStatus()

        when {
            currentBluetoothStatus != BluetoothStatus.ENABLED -> {
                _bluetoothStatus.value = currentBluetoothStatus
                _onboardingState.value = OnboardingState.BLUETOOTH_CHECK
            }
            currentLocationStatus != LocationStatus.ENABLED -> {
                _locationStatus.value = currentLocationStatus
                _onboardingState.value = OnboardingState.LOCATION_CHECK
            }
            else -> {
                _onboardingState.value = OnboardingState.INITIALIZING
                initializeApp()
            }
        }
    }

    private fun handleOnboardingFailed(message: String) {
        Log.e(TAG, "Onboarding failed: $message")
        _errorMessage.value = message
        _onboardingState.value = OnboardingState.ERROR
    }

    private fun initializeApp() {
        Log.d(TAG, "Initializing V2V app")

        lifecycleScope.launch {
            try {
                delay(1000)

                if (!permissionManager.areAllPermissionsGranted()) {
                    handleOnboardingFailed("Algumas permissões foram revogadas.")
                    return@launch
                }

                Log.i(TAG, "Initializing Firebase V2N Transport...")

                val connectivityObserver = ConnectivityObserver.getInstance(this@V2VActivity)
                Log.i(TAG, "Connectivity status: ${if (connectivityObserver.isOnline) "ONLINE" else "OFFLINE"}")

                val firebaseTransport = FirebaseTransport.getInstance(this@V2VActivity)

                firebaseTransport.subscribeToGeohash("emergency")
                firebaseTransport.subscribeToGeohash("test")
                Log.i(TAG, "Subscribed to 'emergency' and 'test' channels")

                firebaseTransport.start()
                Log.i(TAG, "FirebaseTransport started")

                val orchestrator = MessageOrchestrator.getInstance(this@V2VActivity)
                orchestrator.registerTransport(firebaseTransport)
                Log.i(TAG, "MessageOrchestrator initialized")

                orchestrator.start()
                Log.i(TAG, "V2V/V2N hybrid system active!")

                lifecycleScope.launch {
                    firebaseTransport.observeIncoming().collectLatest { incomingPacket ->
                        Log.i(TAG, "Received message from Firebase (${incomingPacket.metadata["source"]})")

                        val result = orchestrator.processIncomingPacket(incomingPacket)

                        when (result) {
                            is com.cybersiren.android.online.MessageProcessResult.Processed -> {
                                Log.i(TAG, "Message processed (first arrival via ${result.channel})")
                                TransportLogRepository.logReceive(
                                    transport = TransportType.FIREBASE,
                                    messageId = result.messageId,
                                    latencyMs = result.latencyMs,
                                    payloadBytes = incomingPacket.data.size,
                                    details = "channel=${result.channel}"
                                )

                                try {
                                    val alertJson = String(incomingPacket.data, Charsets.UTF_8)
                                    if (alertJson.contains("\"type\"") && alertJson.contains("\"lat\"")) {

                                        Log.i(TAG, "Emergency alert from cloud - forwarding to V2V service")

                                        val firebaseAlert = com.cybersiren.android.online.EmergencyAlert.fromBytes(incomingPacket.data)
                                        if (firebaseAlert != null) {

                                            processFirebaseAlert(firebaseAlert, incomingPacket.metadata["sender"] ?: "unknown")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not parse Firebase message: ${e.message}")
                                }
                            }
                            is com.cybersiren.android.online.MessageProcessResult.Duplicate -> {
                                Log.d(TAG, "Duplicate ignored (first via ${result.originalChannel}, dup via ${result.duplicateChannel})")
                            }
                            is com.cybersiren.android.online.MessageProcessResult.Invalid -> {
                                Log.w(TAG, "Invalid message: ${result.reason}")
                                TransportLogRepository.logFailure(
                                    transport = TransportType.FIREBASE,
                                    direction = TransportDirection.RECEIVE,
                                    messageId = result.messageId ?: "unknown",
                                    details = result.reason
                                )
                            }
                        }
                    }
                }
                Log.i(TAG, "Firebase receiver started - listening for cloud alerts")

                delay(2000)
                try {
                    firebaseTransport.sendTestMessage()
                    Log.i(TAG, "Test message sent to Firebase!")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send test message: ${e.message}")
                }

                v2vViewModel = ViewModelProvider(this@V2VActivity, object : ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return V2VViewModel(application, meshService) as T
                    }
                })[V2VViewModel::class.java]

                meshService.delegate = v2vViewModel

                meshService.startServices()

                delay(500)
                Log.d(TAG, "V2V app initialized successfully")
                _onboardingState.value = OnboardingState.COMPLETE

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize V2V app", e)
                handleOnboardingFailed("Falha ao inicializar: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_onboardingState.value == OnboardingState.COMPLETE) {

            val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
            if (currentBluetoothStatus != BluetoothStatus.ENABLED) {
                _bluetoothStatus.value = currentBluetoothStatus
                _onboardingState.value = OnboardingState.BLUETOOTH_CHECK
                return
            }

            val currentLocationStatus = locationStatusManager.checkLocationStatus()
            if (currentLocationStatus != LocationStatus.ENABLED) {
                _locationStatus.value = currentLocationStatus
                _onboardingState.value = OnboardingState.LOCATION_CHECK
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationStatusManager.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up: ${e.message}")
        }
    }

    private fun processFirebaseAlert(
        firebaseAlert: com.cybersiren.android.online.EmergencyAlert,
        senderUid: String
    ) {
        Log.i(TAG, "Processing Firebase alert: ${firebaseAlert.vehicleType} from $senderUid")

        try {

            val v2vVehicleType = when (firebaseAlert.vehicleType) {
                com.cybersiren.android.online.EmergencyAlert.VehicleType.AMBULANCE ->
                    com.cybersiren.android.v2v.model.VehicleType.AMBULANCE
                com.cybersiren.android.online.EmergencyAlert.VehicleType.POLICE ->
                    com.cybersiren.android.v2v.model.VehicleType.POLICE_CAR
                com.cybersiren.android.online.EmergencyAlert.VehicleType.FIRE_TRUCK ->
                    com.cybersiren.android.v2v.model.VehicleType.FIRE_TRUCK
                else ->
                    com.cybersiren.android.v2v.model.VehicleType.EMERGENCY
            }

            val v2vAlert = com.cybersiren.android.v2v.model.EmergencyAlert(
                messageId = firebaseAlert.messageId,
                vehicleType = v2vVehicleType,
                alertType = com.cybersiren.android.v2v.model.AlertType.APPROACHING,
                latitude = firebaseAlert.latitude,
                longitude = firebaseAlert.longitude,
                speed = firebaseAlert.speedKmh / 3.6f,
                heading = firebaseAlert.heading.toFloat(),
                senderPeerId = senderUid.take(16)
            )

            val packet = com.cybersiren.android.protocol.BitchatPacket(
                version = 1u,
                type = com.cybersiren.android.protocol.MessageType.EMERGENCY_ALERT.value,
                senderID = senderUid.take(16).toByteArray().copyOf(8),
                recipientID = com.cybersiren.android.protocol.SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = v2vAlert.toPayload(),
                signature = null,
                ttl = 5u
            )

            if (::v2vViewModel.isInitialized) {
                v2vViewModel.processIncomingAlert(packet, senderUid.take(16))
                Log.i(TAG, "Alert forwarded to V2VViewModel")
            }

            lifecycleScope.launch {
                try {

                    val orchestrator = MessageOrchestrator.getInstance(this@V2VActivity)
                    orchestrator.markAsProcessed(firebaseAlert.messageId, TransportChannel.FIREBASE_CLOUD)

                    meshService.broadcastPacket(packet)
                    Log.i(TAG, "Alert relayed via BLE Mesh for offline devices")

                } catch (e: Exception) {
                    Log.w(TAG, "Failed to relay via BLE Mesh: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Firebase alert: ${e.message}", e)
        }
    }
}
