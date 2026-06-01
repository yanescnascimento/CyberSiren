package com.cybersiren.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class BluetoothConnectionTracker(
    private val connectionScope: CoroutineScope,
    private val powerManager: PowerManager
) {

    companion object {
        private const val TAG = "BluetoothConnectionTracker"
        private const val CONNECTION_RETRY_DELAY = com.cybersiren.android.util.AppConstants.Mesh.CONNECTION_RETRY_DELAY_MS
        private const val MAX_CONNECTION_ATTEMPTS = com.cybersiren.android.util.AppConstants.Mesh.MAX_CONNECTION_ATTEMPTS
        private const val CLEANUP_DELAY = com.cybersiren.android.util.AppConstants.Mesh.CONNECTION_CLEANUP_DELAY_MS
        private const val CLEANUP_INTERVAL = com.cybersiren.android.util.AppConstants.Mesh.CONNECTION_CLEANUP_INTERVAL_MS
    }

    private val connectedDevices = ConcurrentHashMap<String, DeviceConnection>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    val addressPeerMap = ConcurrentHashMap<String, String>()

    private val scanRSSI = ConcurrentHashMap<String, Int>()

    private val pendingConnections = ConcurrentHashMap<String, ConnectionAttempt>()

    private var isActive = false

    data class DeviceConnection(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt? = null,
        val characteristic: BluetoothGattCharacteristic? = null,
        val rssi: Int = Int.MIN_VALUE,
        val isClient: Boolean = false,
        val connectedAt: Long = System.currentTimeMillis(),
        val peerID: String? = null
    )

    data class ConnectionAttempt(
        val attempts: Int,
        val lastAttempt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY * 2

        fun shouldRetry(): Boolean =
            attempts < MAX_CONNECTION_ATTEMPTS &&
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY
    }

    fun start() {
        isActive = true
        startPeriodicCleanup()
    }

    fun stop() {
        isActive = false
        cleanupAllConnections()
        clearAllConnections()
    }

    fun addDeviceConnection(deviceAddress: String, deviceConn: DeviceConnection) {
        Log.d(TAG, "Tracker: Adding device connection for $deviceAddress (isClient: ${deviceConn.isClient}")
        connectedDevices[deviceAddress] = deviceConn
        pendingConnections.remove(deviceAddress)
    }

    fun updateDeviceConnection(deviceAddress: String, deviceConn: DeviceConnection) {
        connectedDevices[deviceAddress] = deviceConn
    }

    fun getDeviceConnection(deviceAddress: String): DeviceConnection? {
        return connectedDevices[deviceAddress]
    }

    fun getConnectedDevices(): Map<String, DeviceConnection> {
        return connectedDevices.toMap()
    }

    fun getSubscribedDevices(): List<BluetoothDevice> {
        return subscribedDevices.toList()
    }

    fun getDeviceRSSI(deviceAddress: String): Int? {
        return connectedDevices[deviceAddress]?.rssi?.takeIf { it != Int.MIN_VALUE }
    }

    fun updateScanRSSI(deviceAddress: String, rssi: Int) {
        scanRSSI[deviceAddress] = rssi
    }

    fun getBestRSSI(deviceAddress: String): Int? {

        connectedDevices[deviceAddress]?.rssi?.takeIf { it != Int.MIN_VALUE }?.let { return it }

        return scanRSSI[deviceAddress]
    }

    fun addSubscribedDevice(device: BluetoothDevice) {
        subscribedDevices.add(device)
    }

    fun removeSubscribedDevice(device: BluetoothDevice) {
        subscribedDevices.remove(device)
    }

    fun isDeviceConnected(deviceAddress: String): Boolean {
        return connectedDevices.containsKey(deviceAddress)
    }

    fun isPeerConnected(peerID: String): Boolean {

        return connectedDevices.values.any { it.peerID == peerID }
    }

    fun isConnectionAttemptAllowed(deviceAddress: String): Boolean {
        val existingAttempt = pendingConnections[deviceAddress]
        return existingAttempt?.let {
            it.isExpired() || it.shouldRetry()
        } ?: true
    }

    fun addPendingConnection(deviceAddress: String): Boolean {
        Log.d(TAG, "Tracker: Adding pending connection for $deviceAddress")
        synchronized(pendingConnections) {

            val currentAttempt = pendingConnections[deviceAddress]
            if (currentAttempt != null && !currentAttempt.isExpired() && !currentAttempt.shouldRetry()) {
                Log.d(TAG, "Tracker: Connection attempt already in progress for $deviceAddress")
                return false
            }
            if (currentAttempt != null) {
                Log.d(TAG, "Tracker: current attempt: $currentAttempt")
            }

            val attempts = if (currentAttempt?.isExpired() == true) 1 else (currentAttempt?.attempts ?: 0) + 1
            pendingConnections[deviceAddress] = ConnectionAttempt(attempts)
            Log.d(TAG, "Tracker: Added pending connection for $deviceAddress (attempts: $attempts)")
            return true
        }
    }

    fun disconnectDevice(deviceAddress: String) {
        connectedDevices[deviceAddress]?.gatt?.let {
            try { it.disconnect() } catch (_: Exception) { }
        }
        cleanupDeviceConnection(deviceAddress)
        Log.d(TAG, "Requested disconnect for $deviceAddress")
    }

    fun removePendingConnection(deviceAddress: String) {
        pendingConnections.remove(deviceAddress)
    }

    fun getConnectedDeviceCount(): Int = connectedDevices.size

    fun canConnectAsClient(maxOverall: Int, maxClient: Int): Boolean {
        val total = connectedDevices.size
        val clients = connectedDevices.values.count { it.isClient }
        return total < maxOverall && clients < maxClient
    }

    fun getConnectionsToEvict(maxOverall: Int, maxServer: Int, maxClient: Int): List<DeviceConnection> {
        val toEvict = mutableSetOf<DeviceConnection>()
        val currentDevices = connectedDevices.values.toList()

        val clients = currentDevices.filter { it.isClient }.sortedBy { it.connectedAt }
        if (clients.size > maxClient) {
            toEvict.addAll(clients.take(clients.size - maxClient))
        }

        val servers = currentDevices.filter { !it.isClient }.sortedBy { it.connectedAt }
        if (servers.size > maxServer) {
            toEvict.addAll(servers.take(servers.size - maxServer))
        }

        val remaining = currentDevices.filter { !toEvict.contains(it) }
        if (remaining.size > maxOverall) {
            val excessCount = remaining.size - maxOverall

            val clientCandidates = remaining.filter { it.isClient }.sortedBy { it.connectedAt }
            val serverCandidates = remaining.filter { !it.isClient }.sortedBy { it.connectedAt }

            var needed = excessCount

            val fromClients = clientCandidates.take(needed)
            toEvict.addAll(fromClients)
            needed -= fromClients.size

            if (needed > 0) {
                val fromServers = serverCandidates.take(needed)
                toEvict.addAll(fromServers)
            }
        }

        return toEvict.toList()
    }

    fun cleanupDeviceConnection(deviceAddress: String) {
        connectedDevices.remove(deviceAddress)?.let { deviceConn ->
            subscribedDevices.removeAll { it.address == deviceAddress }
            addressPeerMap.remove(deviceAddress)
        }
        Log.d(TAG, "Cleaned up device connection for $deviceAddress")
    }

    private fun cleanupAllConnections() {
        connectedDevices.values.forEach { deviceConn ->
            deviceConn.gatt?.disconnect()
        }

        connectionScope.launch {
            delay(CLEANUP_DELAY)

            connectedDevices.values.forEach { deviceConn ->
                try {
                    deviceConn.gatt?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing GATT during cleanup: ${e.message}")
                }
            }
        }
    }

    private fun clearAllConnections() {
        connectedDevices.clear()
        subscribedDevices.clear()
        addressPeerMap.clear()
        pendingConnections.clear()
        scanRSSI.clear()
    }

    private fun startPeriodicCleanup() {
        connectionScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)

                if (!isActive) break

                try {

                    val expiredConnections = pendingConnections.filter { it.value.isExpired() }
                    expiredConnections.keys.forEach { pendingConnections.remove(it) }

                    if (expiredConnections.isNotEmpty()) {
                        Log.d(TAG, "Cleaned up ${expiredConnections.size} expired connection attempts")
                    }

                    Log.d(TAG, "Periodic cleanup: ${connectedDevices.size} connections, ${pendingConnections.size} pending")

                } catch (e: Exception) {
                    Log.w(TAG, "Error in periodic cleanup: ${e.message}")
                }
            }
        }
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("Connected Devices: ${connectedDevices.size} / ${powerManager.getMaxConnections()}")
            connectedDevices.forEach { (address, deviceConn) ->
                val age = (System.currentTimeMillis() - deviceConn.connectedAt) / 1000
                appendLine("  - $address (we're ${if (deviceConn.isClient) "client" else "server"}, ${age}s, RSSI: ${deviceConn.rssi})")
            }
            appendLine()
            appendLine("Subscribed Devices (server mode): ${subscribedDevices.size}")
            appendLine()
            appendLine("Pending Connections: ${pendingConnections.size}")
            val now = System.currentTimeMillis()
            pendingConnections.forEach { (address, attempt) ->
                val elapsed = (now - attempt.lastAttempt) / 1000
                appendLine("  - $address: ${attempt.attempts} attempts, last ${elapsed}s ago")
            }
            appendLine()
            appendLine("Scan RSSI Cache: ${scanRSSI.size}")
            scanRSSI.forEach { (address, rssi) ->
                appendLine("  - $address: $rssi dBm")
            }
        }
    }
}
