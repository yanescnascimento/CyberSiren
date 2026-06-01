package com.cybersiren.android.mesh

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.cybersiren.android.model.RoutedPacket
import com.cybersiren.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

class BluetoothConnectionManager(
    private val context: Context,
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null
) : PowerManagerDelegate {

    companion object {
        private const val TAG = "BluetoothConnectionManager"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    val powerManager = PowerManager(context.applicationContext)

    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val permissionManager = BluetoothPermissionManager(context)
    private val connectionTracker = BluetoothConnectionTracker(connectionScope, powerManager)
    private val packetBroadcaster = BluetoothPacketBroadcaster(connectionScope, connectionTracker, fragmentManager, myPeerID)

    private val componentDelegate = object : BluetoothConnectionManagerDelegate {
        override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?) {
            Log.d(TAG, "onPacketReceived: Packet received from ${device?.address} ($peerID)")
            device?.let { bluetoothDevice ->

                val currentRSSI = connectionTracker.getBestRSSI(bluetoothDevice.address)
                if (currentRSSI != null) {
                    delegate?.onRSSIUpdated(bluetoothDevice.address, currentRSSI)
                }
            }

            if (peerID == myPeerID) return

            delegate?.onPacketReceived(packet, peerID, device)
        }

        override fun onDeviceConnected(device: BluetoothDevice) {

            enforceStrictLimits()
            delegate?.onDeviceConnected(device)
        }

        override fun onDeviceDisconnected(device: BluetoothDevice) {
            delegate?.onDeviceDisconnected(device)
        }

        override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
            delegate?.onRSSIUpdated(deviceAddress, rssi)
        }
    }

    private val serverManager = BluetoothGattServerManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate, myPeerID
    )
    private val clientManager = BluetoothGattClientManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate
    )

    private var isActive = false

    var delegate: BluetoothConnectionManagerDelegate? = null

    val addressPeerMap get() = connectionTracker.addressPeerMap

    init {
        powerManager.delegate = this

        try {
            val dbg = com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance()

            connectionScope.launch {
                dbg.gattServerEnabled.collect { enabled ->
                    if (!isActive) return@collect
                    if (enabled) startServer() else stopServer()
                }
            }
            connectionScope.launch {
                dbg.gattClientEnabled.collect { enabled ->
                    if (!isActive) return@collect
                    if (enabled) startClient() else stopClient()
                }
            }

            connectionScope.launch {
                combine(
                    dbg.maxConnectionsOverall,
                    dbg.maxServerConnections,
                    dbg.maxClientConnections
                ) { _, _, _ ->

                    Unit
                }.collect {
                    if (isActive) {
                        enforceStrictLimits()
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun enforceStrictLimits() {
        if (!isActive) return

        try {
            val dbg = com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance()
            val maxOverall = dbg.maxConnectionsOverall.value
            val maxServer = dbg.maxServerConnections.value
            val maxClient = dbg.maxClientConnections.value

            val toEvict = connectionTracker.getConnectionsToEvict(maxOverall, maxServer, maxClient)

            if (toEvict.isNotEmpty()) {
                Log.i(TAG, "Enforcing limits (max: $maxOverall, s: $maxServer, c: $maxClient) - evicting ${toEvict.size} connections")

                toEvict.forEach { conn ->
                    if (conn.isClient) {
                        Log.d(TAG, "Evicting client ${conn.device.address}")
                        try { conn.gatt?.disconnect() } catch (_: Exception) { }
                    } else {
                        Log.d(TAG, "Evicting server ${conn.device.address}")
                        serverManager.disconnectDevice(conn.device)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing limits: ${e.message}")
        }
    }

    fun startServices(): Boolean {
        Log.i(TAG, "Starting power-optimized Bluetooth services...")

        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions - cannot start services")
            return false
        }
        Log.d(TAG, "Bluetooth permissions OK")

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled - cannot start services")
            return false
        }
        Log.d(TAG, "Bluetooth adapter enabled")

        try {
            isActive = true
            Log.d(TAG, "ConnectionManager activated, launching coroutine to start components...")

            connectionScope.launch {
                Log.d(TAG, "Coroutine started - initializing components")

                connectionTracker.start()
                Log.d(TAG, "ConnectionTracker started")

                powerManager.start()
                Log.d(TAG, "PowerManager started")

                val dbg = try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance() } catch (_: Exception) { null }
                val startServer = dbg?.gattServerEnabled?.value != false
                val startClient = dbg?.gattClientEnabled?.value != false
                Log.d(TAG, "Debug settings: server=$startServer, client=$startClient")

                if (startServer) {
                    Log.d(TAG, "Starting GATT Server...")
                    if (!serverManager.start()) {
                        Log.e(TAG, "Failed to start server manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                    Log.i(TAG, "GATT Server started successfully")
                } else {
                    Log.w(TAG, "GATT Server disabled by debug settings; not starting")
                }

                if (startClient) {
                    Log.d(TAG, "Starting GATT Client...")
                    if (!clientManager.start()) {
                        Log.e(TAG, "Failed to start client manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                    Log.i(TAG, "GATT Client started successfully")
                } else {
                    Log.w(TAG, "GATT Client disabled by debug settings; not starting")
                }

                Log.i(TAG, "All Bluetooth services started successfully!")
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth services: ${e.message}", e)
            isActive = false
            return false
        }
    }

    fun stopServices() {
        Log.i(TAG, "Stopping power-optimized Bluetooth services")

        isActive = false

        connectionScope.launch {
            Log.d(TAG, "Stopping client/server and power components...")

            clientManager.stop()
            serverManager.stop()

            powerManager.stop()

            connectionTracker.stop()

            connectionScope.cancel()

            Log.i(TAG, "All Bluetooth services stopped")
        }
    }

    fun isReusable(): Boolean {
        val active = connectionScope.isActive
        if (!active) {
            Log.d(TAG, "BluetoothConnectionManager isReusable=false (scope cancelled)")
        }
        return active
    }

    fun broadcastPacket(routed: RoutedPacket) {
        if (!isActive) return

        packetBroadcaster.broadcastPacket(
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (!isActive) return false
        return packetBroadcaster.sendToPeer(
            peerID,
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun cancelTransfer(transferId: String): Boolean {
        return packetBroadcaster.cancelTransfer(transferId)
    }

    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        if (!isActive) return false
        return packetBroadcaster.sendPacketToPeer(
            RoutedPacket(packet),
            peerID,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun startServer() { connectionScope.launch { serverManager.start() } }
    fun stopServer() { connectionScope.launch { serverManager.stop() } }
    fun startClient() { connectionScope.launch { clientManager.start() } }
    fun stopClient() { connectionScope.launch { clientManager.stop() } }

    fun setNicknameResolver(resolver: (String) -> String?) { packetBroadcaster.setNicknameResolver(resolver) }

    fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> {
        return try {
            connectionTracker.getConnectedDevices().values.map { dc ->
                val rssi = if (dc.rssi != Int.MIN_VALUE) dc.rssi else null
                Triple(dc.device.address, dc.isClient, rssi)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getLocalAdapterAddress(): String? = try { bluetoothAdapter?.address } catch (e: Exception) { null }

    fun isClientConnection(address: String): Boolean? {
        return try { connectionTracker.getConnectedDevices()[address]?.isClient } catch (e: Exception) { null }
    }

    fun connectToAddress(address: String): Boolean = clientManager.connectToAddress(address)
    fun disconnectAddress(address: String) { connectionTracker.disconnectDevice(address) }

    fun disconnectAll() {
        connectionScope.launch {

            clientManager.stop()
            serverManager.stop()
            delay(200)
            if (isActive) {

                serverManager.start()
                clientManager.start()
            }
        }
    }

    fun getConnectedDeviceCount(): Int = connectionTracker.getConnectedDeviceCount()

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Bluetooth Connection Manager ===")
            appendLine("Bluetooth MAC Address: ${bluetoothAdapter?.address}")
            appendLine("Active: $isActive")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            appendLine("Has Permissions: ${permissionManager.hasBluetoothPermissions()}")
            appendLine("GATT Server Active: ${serverManager.getGattServer() != null}")
            appendLine()
            appendLine(powerManager.getPowerInfo())
            appendLine()
            appendLine(connectionTracker.getDebugInfo())
        }
    }

    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        Log.i(TAG, "Power mode changed to: $newMode")

        connectionScope.launch {

            val wasUsingDutyCycle = powerManager.shouldUseDutyCycle()

            val serverEnabled = try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true }
            if (serverEnabled) {
                serverManager.restartAdvertising()
            } else {
                serverManager.stop()
            }

            val nowUsingDutyCycle = powerManager.shouldUseDutyCycle()
            if (wasUsingDutyCycle != nowUsingDutyCycle) {
                Log.d(TAG, "Duty cycle behavior changed (${wasUsingDutyCycle} -> ${nowUsingDutyCycle}), restarting scan")
                val clientEnabled = try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
                if (clientEnabled) {
                    clientManager.restartScanning()
                } else {
                    clientManager.stop()
                }
            } else {
                Log.d(TAG, "Duty cycle behavior unchanged, keeping existing scan state")
            }

            enforceStrictLimits()
        }
    }

    override fun onScanStateChanged(shouldScan: Boolean) {
        clientManager.onScanStateChanged(shouldScan)
    }

}

interface BluetoothConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?)
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice)
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)
}
