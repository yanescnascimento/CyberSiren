package com.cybersiren.android.onboarding

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class BluetoothStatusManager(
    private val activity: ComponentActivity,
    private val context: Context,
    private val onBluetoothEnabled: () -> Unit,
    private val onBluetoothDisabled: (String) -> Unit
) {

    companion object {
        private const val TAG = "BluetoothStatusManager"
    }

    private var bluetoothEnableLauncher: ActivityResultLauncher<Intent>? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    init {
        setupBluetoothAdapter()
        setupBluetoothEnableLauncher()
    }

    private fun setupBluetoothAdapter() {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            Log.d(TAG, "Bluetooth adapter initialized: ${bluetoothAdapter != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Bluetooth adapter", e)
            bluetoothAdapter = null
        }
    }

    private fun setupBluetoothEnableLauncher() {
        bluetoothEnableLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val isEnabled = bluetoothAdapter?.isEnabled == true
            Log.d(TAG, "Bluetooth enable request result: $isEnabled (result code: ${result.resultCode})")
            if (isEnabled) {
                onBluetoothEnabled()
            } else {
                onBluetoothDisabled("Bluetooth is required for bitchat to discover and connect to nearby users. Please enable Bluetooth to continue.")
            }
        }
    }

    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }

    fun isBluetoothEnabled(): Boolean {
        return try {
            bluetoothAdapter?.isEnabled == true
        } catch (securityException: SecurityException) {

            Log.w(TAG, "Cannot check Bluetooth enabled state due to missing permissions")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Bluetooth enabled state: ${e.message}")
            false
        }
    }

    fun checkBluetoothStatus(): BluetoothStatus {

        return when {
            bluetoothAdapter == null -> {
                Log.e(TAG, "Bluetooth not supported on this device")
                BluetoothStatus.NOT_SUPPORTED
            }
            !isBluetoothEnabled() -> {
                Log.w(TAG, "Bluetooth is disabled or cannot be checked")
                BluetoothStatus.DISABLED
            }
            else -> {

                BluetoothStatus.ENABLED
            }
        }
    }

    fun requestEnableBluetooth() {
        Log.d(TAG, "Requesting user to enable Bluetooth")

        try {
            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher?.launch(enableBluetoothIntent)
        } catch (securityException: SecurityException) {

            Log.w(TAG, "Cannot request Bluetooth enable due to missing BLUETOOTH_CONNECT permission")
            onBluetoothDisabled("Bluetooth permissions are required before enabling Bluetooth. Please grant permissions first.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Bluetooth enable", e)
            onBluetoothDisabled("Failed to request Bluetooth enable: ${e.message}")
        }
    }

    fun handleBluetoothStatus(status: BluetoothStatus) {
        when (status) {
            BluetoothStatus.ENABLED -> {
                Log.d(TAG, "Bluetooth is enabled, proceeding")
                onBluetoothEnabled()
            }
            BluetoothStatus.DISABLED -> {
                Log.d(TAG, "Bluetooth is disabled, requesting enable")
                requestEnableBluetooth()
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                Log.e(TAG, "Bluetooth not supported")
                onBluetoothDisabled("This device doesn't support Bluetooth, which is required for bitchat to function.")
            }
        }
    }

    fun getStatusMessage(status: BluetoothStatus): String {
        return when (status) {
            BluetoothStatus.ENABLED -> "Bluetooth is enabled and ready"
            BluetoothStatus.DISABLED -> "Bluetooth is disabled. Please enable Bluetooth to use bitchat."
            BluetoothStatus.NOT_SUPPORTED -> "This device doesn't support Bluetooth."
        }
    }

    fun getDiagnostics(): String {
        return buildString {
            appendLine("Bluetooth Status Diagnostics:")
            appendLine("Adapter available: ${bluetoothAdapter != null}")
            appendLine("Bluetooth supported: ${isBluetoothSupported()}")
            appendLine("Bluetooth enabled: ${isBluetoothEnabled()}")
            appendLine("Current status: ${checkBluetoothStatus()}")

            bluetoothAdapter?.let { adapter ->
                try {

                    appendLine("Adapter name: ${adapter.name ?: "Unknown"}")
                    appendLine("Adapter address: ${adapter.address ?: "Unknown"}")
                } catch (securityException: SecurityException) {

                    appendLine("Adapter details: [Permission required]")
                } catch (e: Exception) {
                    appendLine("Adapter details: [Error: ${e.message}]")
                }
                appendLine("Adapter state: ${getAdapterStateName(adapter.state)}")
            }
        }
    }

    private fun getAdapterStateName(state: Int): String {
        return when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN($state)"
        }
    }

    fun logBluetoothStatus() {
        Log.d(TAG, getDiagnostics())
    }

    fun monitorBluetoothState(
        context: Context,
        bluetoothStatusManager: BluetoothStatusManager,
        onBluetoothStateChanged: (BluetoothStatus) -> Unit
    ): BroadcastReceiver {

        Log.d(TAG, "Starting Bluetooth State Monitoring")

        if (!bluetoothStatusManager.isBluetoothSupported()) {
            Log.e(TAG, "Bluetooth is not supported")
            onBluetoothStateChanged(BluetoothStatus.NOT_SUPPORTED)
            bluetoothStatusManager.handleBluetoothStatus(BluetoothStatus.NOT_SUPPORTED)
            return object : BroadcastReceiver() { override fun onReceive(p0: Context?, p1: Intent?) {}
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON")
                        onBluetoothStateChanged(BluetoothStatus.ENABLED)
                        bluetoothStatusManager.handleBluetoothStatus(BluetoothStatus.ENABLED)
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF")
                        onBluetoothStateChanged(BluetoothStatus.DISABLED)
                        bluetoothStatusManager.onBluetoothDisabled("User has turned off their Blue")
                    }
                    BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth state transitioning: ${bluetoothStatusManager.getAdapterStateName(intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))}")
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val initialStatus = bluetoothStatusManager.checkBluetoothStatus()
        onBluetoothStateChanged(initialStatus)

        return receiver
    }
}

enum class BluetoothStatus {
    ENABLED,
    DISABLED,
    NOT_SUPPORTED
}
