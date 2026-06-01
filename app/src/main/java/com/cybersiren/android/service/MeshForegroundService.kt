package com.cybersiren.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cybersiren.android.MainActivity
import com.cybersiren.android.R
import com.cybersiren.android.mesh.BluetoothMeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "bitchat_mesh_service"
        private const val NOTIFICATION_ID = 10001

        const val ACTION_START = "com.cybersiren.android.service.START"
        const val ACTION_STOP = "com.cybersiren.android.service.STOP"
        const val ACTION_QUIT = "com.cybersiren.android.service.QUIT"
        const val ACTION_UPDATE_NOTIFICATION = "com.cybersiren.android.service.UPDATE_NOTIFICATION"
        const val ACTION_NOTIFICATION_PERMISSION_GRANTED = "com.cybersiren.android.action.NOTIFICATION_PERMISSION_GRANTED"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_START }

            val bgEnabled = MeshServicePreferences.isBackgroundEnabled(true)
            val hasNotifPerm = hasNotificationPermissionStatic(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (bgEnabled && hasNotifPerm) {
                    context.startForegroundService(intent)
                } else {

                    android.util.Log.i(
                        "MeshForegroundService",
                        "Not starting service on API>=26 (bgEnabled=$bgEnabled, hasNotifPerm=$hasNotifPerm)"
                    )
                }
            } else {
                if (bgEnabled) {
                    context.startService(intent)
                } else {
                    android.util.Log.i("MeshForegroundService", "Background disabled; not starting service (pre-O)")
                }
            }
        }

        fun onNotificationPermissionGranted(context: Context) {

            val hasNotifPerm = hasNotificationPermissionStatic(context)
            if (!MeshServicePreferences.isBackgroundEnabled(true) || !hasNotifPerm) return

            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_UPDATE_NOTIFICATION }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        private fun shouldStartAsForeground(context: Context): Boolean {
            return MeshServicePreferences.isBackgroundEnabled(true) &&
                    hasBluetoothPermissionsStatic(context) &&
                    hasNotificationPermissionStatic(context)
        }

        private fun hasBluetoothPermissionsStatic(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                val fine = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarse = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                fine || coarse
            }
        }

        private fun hasNotificationPermissionStatic(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        }
    }

    private lateinit var notificationManager: NotificationManagerCompat
    private var updateJob: Job? = null
    private val meshService: BluetoothMeshService?
        get() = MeshServiceHolder.meshService
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var isInForeground: Boolean = false
    private var isShuttingDown: Boolean = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        createChannel()

        val existing = MeshServiceHolder.meshService
        if (existing != null) {
            Log.d("MeshForegroundService", "Using existing BluetoothMeshService from holder")
        } else {
            val created = MeshServiceHolder.getOrCreate(applicationContext)
            Log.i("MeshForegroundService", "Created new BluetoothMeshService via holder")
            MeshServiceHolder.attach(created)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isShuttingDown && intent?.action == ACTION_START) {
            AppShutdownCoordinator.cancelPendingShutdown()
            isShuttingDown = false
        }
        if (isShuttingDown && intent?.action != ACTION_QUIT) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {

                try { meshService?.stopServices() } catch (_: Exception) { }
                try { MeshServiceHolder.clear() } catch (_: Exception) { }
                try { stopForeground(true) } catch (_: Exception) { }
                notificationManager.cancel(NOTIFICATION_ID)
                isInForeground = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                isShuttingDown = true
                updateJob?.cancel()
                updateJob = null
                try { stopForeground(true) } catch (_: Exception) { }
                notificationManager.cancel(NOTIFICATION_ID)
                isInForeground = false

                AppShutdownCoordinator.requestFullShutdownAndKill(
                    app = application,
                    mesh = meshService,
                    notificationManager = notificationManager,
                    stopForeground = {
                        try { stopForeground(true) } catch (_: Exception) { }
                        isInForeground = false
                    },
                    stopService = { stopSelf() }
                )
                return START_NOT_STICKY
            }
            ACTION_UPDATE_NOTIFICATION -> {

                if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions() && !isInForeground) {
                    val n = buildNotification(meshService?.getActivePeerCount() ?: 0)
                    startForegroundCompat(n)
                    isInForeground = true
                } else {
                    updateNotification(force = true)
                }
            }
            else -> {  }
        }

        ensureMeshStarted()

        if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions() && !isInForeground) {
            val notification = buildNotification(meshService?.getActivePeerCount() ?: 0)
            startForegroundCompat(notification)
            isInForeground = true
        }

        if (updateJob == null) {
            updateJob = scope.launch {
                while (isActive) {

                    ensureMeshStarted()
                    val eligible = MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions()
                    if (eligible) {

                        updateNotification(force = false)
                    } else {

                        if (isInForeground) {
                            try { stopForeground(false) } catch (_: Exception) { }
                            isInForeground = false
                        }
                        notificationManager.cancel(NOTIFICATION_ID)
                    }
                    delay(5000)
                }
            }
        }

        return START_STICKY
    }

    private fun ensureMeshStarted() {
        if (isShuttingDown) return
        if (!hasBluetoothPermissions()) return
        try {
            android.util.Log.d("MeshForegroundService", "Ensuring mesh service is started")
            val service = MeshServiceHolder.getOrCreate(applicationContext)
            service.startServices()
        } catch (e: Exception) {
            android.util.Log.e("MeshForegroundService", "Failed to start mesh service: ${e.message}")
        }
    }

    private fun updateNotification(force: Boolean) {
        if (isShuttingDown) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        val count = meshService?.getActivePeerCount() ?: 0
        val notification = buildNotification(count)
        if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions()) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } else if (force) {

            try { stopForeground(false) } catch (_: Exception) { }
            notificationManager.cancel(NOTIFICATION_ID)
            isInForeground = false
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {

        return hasBluetoothPermissions() && hasNotificationPermission()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {

            val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun buildNotification(activePeers: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val quitIntent = Intent(this, MeshForegroundService::class.java).apply { action = ACTION_QUIT }
        val quitPendingIntent = PendingIntent.getService(
            this, 1, quitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = getString(R.string.app_name)
        val content = getString(R.string.mesh_service_notification_content, activePeers)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)

            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_quit_bitchat),
                quitPendingIntent
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mesh_service_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (hasLocationPermission()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: SecurityException) {

                if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) {
                     android.util.Log.w("MeshForegroundService", "Failed to start with LOCATION type, falling back to CONNECTED_DEVICE: ${e.message}")
                     startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    throw e
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        updateJob?.cancel()
        updateJob = null

        try { serviceJob.cancel() } catch (_: Exception) { }

        if (isInForeground) {
            try { stopForeground(true) } catch (_: Exception) { }
            isInForeground = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
