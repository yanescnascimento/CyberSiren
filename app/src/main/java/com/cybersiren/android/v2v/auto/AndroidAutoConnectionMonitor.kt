package com.cybersiren.android.v2v.auto

import android.content.Context
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer

class AndroidAutoConnectionMonitor(
    private val context: Context
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AndroidAutoMonitor"
    }

    private var carConnection: CarConnection? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private var previousConnectionType: Int = CarConnection.CONNECTION_TYPE_NOT_CONNECTED

    private var hasShownNotificationThisSession = false

    private val connectionObserver = Observer<Int> { connectionType ->
        handleConnectionChange(connectionType)
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        lifecycleOwner = owner
        startMonitoring(owner)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        stopMonitoring()
    }

    private fun startMonitoring(owner: LifecycleOwner) {
        Log.d(TAG, "Starting Android Auto connection monitoring")

        try {
            carConnection = CarConnection(context)
            carConnection?.type?.observe(owner, connectionObserver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Android Auto monitoring: ${e.message}")
        }
    }

    private fun stopMonitoring() {
        Log.d(TAG, "Stopping Android Auto connection monitoring")
        try {
            carConnection?.type?.removeObserver(connectionObserver)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing observer: ${e.message}")
        }
        carConnection = null
        lifecycleOwner = null
    }

    private fun handleConnectionChange(connectionType: Int) {
        val previousType = previousConnectionType
        previousConnectionType = connectionType

        Log.d(TAG, "Connection state changed: $previousType -> $connectionType")

        when {

            isConnected(connectionType) && !isConnected(previousType) -> {
                Log.d(TAG, "Android Auto connected")
                onConnected()
            }

            !isConnected(connectionType) && isConnected(previousType) -> {
                Log.d(TAG, "Android Auto disconnected")
                onDisconnected()
            }
        }
    }

    private fun onConnected() {
        if (!hasShownNotificationThisSession) {
            hasShownNotificationThisSession = true
            AndroidAutoNotificationHelper.showConnectedNotification(context)
            Log.d(TAG, "Showed Android Auto connection notification")
        }
    }

    private fun onDisconnected() {

        hasShownNotificationThisSession = false
        AndroidAutoNotificationHelper.cancelNotification(context)
        Log.d(TAG, "Cancelled Android Auto notification, reset session flag")
    }

    private fun isConnected(connectionType: Int): Boolean {
        return connectionType == CarConnection.CONNECTION_TYPE_NATIVE ||
               connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
    }
}
