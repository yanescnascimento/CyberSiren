package com.cybersiren.android.mesh

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlinx.coroutines.Job
import com.cybersiren.android.ui.debug.DebugSettingsManager
import com.cybersiren.android.ui.debug.DebugScanResult

class BluetoothGattClientManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?
) {

    companion object {
        private const val TAG = "BluetoothGattClientManager"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    fun connectToAddress(deviceAddress: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        return if (device != null) {
            val rssi = connectionTracker.getBestRSSI(deviceAddress) ?: -50
            connectToDevice(device, rssi)
            true
        } else {
            Log.w(TAG, "connectToAddress: No device for $deviceAddress")
            false
        }
    }

    private var scanCallback: ScanCallback? = null

    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    private var isCurrentlyScanning = false
    private val scanRateLimit = 5000L

    private var rssiMonitoringJob: Job? = null

    private var isActive = false

    fun start(): Boolean {

        try {
            if (!com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value) {
                Log.i(TAG, "Client start skipped: GATT Client disabled in debug settings")
                return false
            }
        } catch (_: Exception) { }

        if (isActive) {
            Log.d(TAG, "GATT client already active; start is a no-op")
            return true
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions - cannot start scanning")
            return false
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled - cannot start scanning")
            return false
        }

        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return false
        }

        isActive = true
        Log.i(TAG, "GATT Client Manager starting - will begin scanning for mesh devices")

        connectionScope.launch {
            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Using power-aware duty cycling")
            } else {
                Log.i(TAG, "Starting continuous BLE scan...")
                startScanning()
            }

            startRSSIMonitoring()
        }

        return true
    }

    fun stop() {
        if (!isActive) {

            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped (already inactive)")
            return
        }

        isActive = false

        connectionScope.launch {

            try {
                val conns = connectionTracker.getConnectedDevices().values.filter { it.isClient && it.gatt != null }
                conns.forEach { dc ->
                    try { dc.gatt?.disconnect() } catch (_: Exception) { }
                }
            } catch (_: Exception) { }

            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped")
        }
    }

    fun onScanStateChanged(shouldScan: Boolean) {
        val enabled = try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        if (shouldScan && enabled) {
            startScanning()
        } else {
            stopScanning()
        }
    }

    private fun startRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = connectionScope.launch {
            while (isActive) {
                try {

                    val connectedDevices = connectionTracker.getConnectedDevices()
                    connectedDevices.values.filter { it.isClient && it.gatt != null }.forEach { deviceConn ->
                        try {
                            Log.d(TAG, "Requesting RSSI from ${deviceConn.device.address}")
                            deviceConn.gatt?.readRemoteRssi()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to request RSSI from ${deviceConn.device.address}: ${e.message}")
                        }
                    }
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in RSSI monitoring: ${e.message}")
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    private fun stopRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = null
    }

    @Suppress("DEPRECATION")
    private fun startScanning() {

        val enabled = try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null || !isActive || !enabled) return

        val currentTime = System.currentTimeMillis()
        if (isCurrentlyScanning) {
            Log.d(TAG, "Scan already in progress, skipping start request")
            return
        }

        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            Log.w(TAG, "Scan rate limited: need to wait ${remainingWait}ms before starting scan")

            connectionScope.launch {
                delay(remainingWait)
                if (isActive && !isCurrentlyScanning) {
                    startScanning()
                }
            }
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .build()

        val scanFilters = listOf(scanFilter)

        Log.d(TAG, "Starting BLE scan with target service UUID: ${AppConstants.Mesh.Gatt.SERVICE_UUID}")

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val rssi = result.rssi
                val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == AppConstants.Mesh.Gatt.SERVICE_UUID } == true
                if (hasService) {
                    Log.i(TAG, "Found Bitchat device: ${device.address} (RSSI: $rssi)")
                }
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Batch scan results: ${results.size} devices")
                results.forEach { result ->
                    handleScanResult(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                isCurrentlyScanning = false
                lastScanStopTime = System.currentTimeMillis()

                when (errorCode) {
                    1 -> Log.e(TAG, "SCAN_FAILED_ALREADY_STARTED")
                    2 -> Log.e(TAG, "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED")
                    3 -> Log.e(TAG, "SCAN_FAILED_INTERNAL_ERROR")
                    4 -> Log.e(TAG, "SCAN_FAILED_FEATURE_UNSUPPORTED")
                    5 -> Log.e(TAG, "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES")
                    6 -> {
                        Log.e(TAG, "SCAN_FAILED_SCANNING_TOO_FREQUENTLY")
                        Log.w(TAG, "Scan failed due to rate limiting - will retry after delay")
                        connectionScope.launch {
                            delay(10000)
                            if (isActive) {
                                startScanning()
                            }
                        }
                    }
                    else -> Log.e(TAG, "Unknown scan failure code: $errorCode")
                }
            }
        }

        try {
            lastScanStartTime = currentTime
            isCurrentlyScanning = true

            bleScanner.startScan(scanFilters, powerManager.getScanSettings(), scanCallback)
            Log.d(TAG, "BLE scan started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            isCurrentlyScanning = false
        }
    }

    @Suppress("DEPRECATION")
    private fun stopScanning() {
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null) return

        if (isCurrentlyScanning) {
            try {
                scanCallback?.let {
                    bleScanner.stopScan(it)
                    Log.d(TAG, "BLE scan stopped successfully")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan: ${e.message}")
            }

            isCurrentlyScanning = false
            lastScanStopTime = System.currentTimeMillis()
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val deviceAddress = device.address
        val scanRecord = result.scanRecord

        val hasOurService = scanRecord?.serviceUuids?.any { it.uuid == AppConstants.Mesh.Gatt.SERVICE_UUID } == true
        if (!hasOurService) {
            return
        }

        val serviceData = scanRecord?.getServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
        val peerID = if (serviceData != null && serviceData.size >= 8) {
            serviceData.joinToString("") { "%02x".format(it) }
        } else {
            null
        }

        Log.i(TAG, "Processing Bitchat device: $deviceAddress (peerID: ${peerID ?: "unknown"}, RSSI: $rssi)")

        if (peerID != null) {
            if (connectionTracker.isPeerConnected(peerID)) {
                 Log.d(TAG, "Peer $peerID already connected (skipping $deviceAddress)")
                 return
            }
        }

        connectionTracker.updateScanRSSI(deviceAddress, rssi)

        try {
            DebugSettingsManager.getInstance().addScanResult(
                DebugScanResult(
                    deviceName = device.name,
                    deviceAddress = deviceAddress,
                    rssi = rssi,
                    peerID = peerID
                )
            )
        } catch (_: Exception) { }

        if (rssi < powerManager.getRSSIThreshold()) {
            Log.d(TAG, "Skipping $deviceAddress: weak signal ($rssi < ${powerManager.getRSSIThreshold()})")

            try {
                DebugSettingsManager.getInstance().addScanResult(
                    DebugScanResult(
                        deviceName = device.name,
                        deviceAddress = deviceAddress,
                        rssi = rssi,
                        peerID = peerID
                    )
                )
            } catch (_: Exception) { }
            return
        }

        if (connectionTracker.isDeviceConnected(deviceAddress)) {
            Log.d(TAG, "Device $deviceAddress already connected")
            return
        }

        if (!connectionTracker.isConnectionAttemptAllowed(deviceAddress)) {
            Log.d(TAG, "⏳ Connection to $deviceAddress blocked (too many recent attempts)")
            return
        }

        val dbg = try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance() } catch (_: Exception) { null }
        val maxOverall = dbg?.maxConnectionsOverall?.value ?: powerManager.getMaxConnections()
        val maxClient = dbg?.maxClientConnections?.value ?: maxOverall

        if (!connectionTracker.canConnectAsClient(maxOverall, maxClient)) {
            Log.w(TAG, "Client connection limit reached (max: $maxOverall, client: $maxClient)")
            return
        }

        if (connectionTracker.addPendingConnection(deviceAddress)) {
            Log.i(TAG, "Initiating connection to $deviceAddress (peerID: ${peerID ?: "unknown"})")
            connectToDevice(device, rssi, peerID)
        } else {
            Log.d(TAG, "⏳ Connection to $deviceAddress already pending")
        }
    }

    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, rssi: Int, peerID: String? = null) {
        if (!permissionManager.hasBluetoothPermissions()) return

        val deviceAddress = device.address
        Log.i(TAG, "Connecting to bitchat device: $deviceAddress (peerID: $peerID)")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Client: Connection state change - Device: $deviceAddress, Status: $status, NewState: $newState")

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Client: Successfully connected to $deviceAddress. Requesting MTU...")

                    connectionScope.launch {
                        delay(200)
                        gatt.requestMtu(517)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Client: Disconnected from $deviceAddress with error status $status")
                        if (status == 147) {
                            Log.e(TAG, "Client: Connection establishment failed (status 147) for $deviceAddress")
                        }
                    } else {
                        Log.d(TAG, "Client: Cleanly disconnected from $deviceAddress")
                        connectionTracker.cleanupDeviceConnection(deviceAddress)
                    }

                    delegate?.onDeviceDisconnected(gatt.device)

                    connectionScope.launch {
                        delay(500)
                        try {
                            gatt.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing GATT: ${e.message}")
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val deviceAddress = gatt.device.address
                Log.i(TAG, "Client: MTU changed for $deviceAddress to $mtu with status $status")

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "MTU successfully negotiated for $deviceAddress. Discovering services.")

                    val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                        device = gatt.device,
                        gatt = gatt,
                        rssi = rssi,
                        isClient = true,
                        peerID = peerID
                    )
                    connectionTracker.addDeviceConnection(deviceAddress, deviceConn)

                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $deviceAddress with status: $status. Disconnecting.")

                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(AppConstants.Mesh.Gatt.SERVICE_UUID)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                                val updatedConn = deviceConn.copy(characteristic = characteristic)
                                connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                                Log.d(TAG, "Client: Updated device connection with characteristic for $deviceAddress")
                            }

                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)

                                connectionScope.launch {
                                    delay(200)
                                    Log.i(TAG, "Client: Connection setup complete for $deviceAddress")
                                    delegate?.onDeviceConnected(device)
                                }
                            } else {
                                Log.e(TAG, "Client: CCCD descriptor not found for $deviceAddress")
                                gatt.disconnect()
                            }
                        } else {
                            Log.e(TAG, "Client: Required characteristic not found for $deviceAddress")
                            gatt.disconnect()
                        }
                    } else {
                        Log.e(TAG, "Client: Required service not found for $deviceAddress")
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Client: Service discovery failed with status $status for $deviceAddress")
                    gatt.disconnect()
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                Log.i(TAG, "Client: Received packet from ${gatt.device.address}, size: ${value.size} bytes")
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "Client: Parsed packet type ${packet.type} from $peerID")
                    delegate?.onPacketReceived(packet, peerID, gatt.device)
                } else {
                    Log.w(TAG, "Client: Failed to parse packet from ${gatt.device.address}, size: ${value.size} bytes")
                    Log.w(TAG, "Client: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                val deviceAddress = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client: RSSI updated for $deviceAddress: $rssi dBm")

                    connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                        val updatedConn = deviceConn.copy(rssi = rssi)
                        connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                    }
                } else {
                    Log.w(TAG, "Client: Failed to read RSSI for $deviceAddress, status: $status")
                }
            }
        }

        try {
            Log.d(TAG, "Client: Attempting GATT connection to $deviceAddress with autoConnect=false")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")

            } else {
                Log.d(TAG, "Client: GATT connection initiated successfully for $deviceAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client: Exception connecting to $deviceAddress: ${e.message}")

        }
    }

    fun restartScanning() {

        val enabled = try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        if (!isActive || !enabled) return

        connectionScope.launch {
            stopScanning()
            delay(1000)

            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Switching to duty cycle scanning mode")

            } else {
                Log.i(TAG, "Switching to continuous scanning mode")
                startScanning()
            }
        }
    }
}
