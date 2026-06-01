package com.cybersiren.android.service

import android.app.Application
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import com.cybersiren.android.mesh.BluetoothMeshService
import com.cybersiren.android.net.ArtiTorManager
import com.cybersiren.android.net.TorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

object AppShutdownCoordinator {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val shutdownToken = AtomicLong(0L)
    @Volatile
    private var shutdownJob: Job? = null

    fun cancelPendingShutdown() {
        shutdownToken.incrementAndGet()
        shutdownJob?.cancel()
        shutdownJob = null
    }

    fun requestFullShutdownAndKill(
        app: Application,
        mesh: BluetoothMeshService?,
        notificationManager: NotificationManagerCompat,
        stopForeground: () -> Unit,
        stopService: () -> Unit
    ) {
        val token = shutdownToken.incrementAndGet()
        shutdownJob?.cancel()
        val job = scope.launch {

            try {
                val intent = android.content.Intent(com.cybersiren.android.util.AppConstants.UI.ACTION_FORCE_FINISH)
                    .setPackage(app.packageName)
                app.sendBroadcast(intent, com.cybersiren.android.util.AppConstants.UI.PERMISSION_FORCE_FINISH)
            } catch (_: Exception) { }

            try { mesh?.stopServices() } catch (_: Exception) { }

            val torProvider = ArtiTorManager.getInstance()
            val torStop = async {
                try { torProvider.applyMode(app, TorMode.OFF) } catch (_: Exception) { }
            }

            try { com.cybersiren.android.services.AppStateStore.clear() } catch (_: Exception) { }

            try { stopForeground() } catch (_: Exception) { }
            try { notificationManager.cancel(10001) } catch (_: Exception) { }

            withTimeoutOrNull(5000) {
                try { torStop.await() } catch (_: Exception) { }
                delay(100)
            }

            if (!isActive || shutdownToken.get() != token) return@launch
            try { stopService() } catch (_: Exception) { }

            if (!isActive || shutdownToken.get() != token) return@launch
            try { Process.killProcess(Process.myPid()) } catch (_: Exception) { }
            try { System.exit(0) } catch (_: Exception) { }
        }
        shutdownJob = job
        job.invokeOnCompletion {
            if (shutdownJob === job) {
                shutdownJob = null
            }
        }
    }
}
