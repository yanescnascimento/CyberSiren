package info.guardianproject.arti

import android.app.Application
import android.util.Log
import org.torproject.arti.ArtiNative
import java.io.File

class ArtiProxy private constructor(
    private val application: Application,
    private val socksPort: Int,
    private val dnsPort: Int,
    private val logListener: ArtiLogListener?
) {
    companion object {
        private const val TAG = "ArtiProxy"
    }

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) {
            Log.w(TAG, "Arti already running")
            return
        }

        try {
            logListener?.let { listener ->
                Log.d(TAG, "Registering log callback")
                ArtiNative.setLogCallback(listener)
            }

            val dataDir = getDataDirectory()
            Log.i(TAG, "Initializing Arti with data directory: $dataDir")

            val initResult = ArtiNative.initialize(dataDir.absolutePath)
            if (initResult != 0) {
                throw ArtiException("Failed to initialize Arti: error code $initResult")
            }

            Log.i(TAG, "Starting SOCKS proxy on port $socksPort (DNS port: $dnsPort)")
            val startResult = ArtiNative.startSocksProxy(socksPort)
            when (startResult) {
                0 -> {
                    isRunning = true
                    Log.i(TAG, "Arti started successfully")
                }
                -1 -> throw ArtiException("Arti client not initialized")
                -2 -> throw ArtiException("Tokio runtime not initialized")
                -3 -> throw ArtiException("Failed to bind SOCKS proxy to port $socksPort (port already in use)")
                else -> throw ArtiException("Failed to start SOCKS proxy: error code $startResult")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Arti", e)
            if (e is ArtiException) {
                throw e
            } else {
                throw ArtiException("Failed to start Arti: ${e.message}", e)
            }
        }
    }

    fun stop() {
        if (!isRunning) {
            Log.w(TAG, "Arti not running")
            return
        }

        try {
            Log.i(TAG, "Stopping Arti...")
            val stopResult = ArtiNative.stop()
            if (stopResult != 0) {
                Log.w(TAG, "Stop returned error code: $stopResult")
            }

            isRunning = false
            Log.i(TAG, "Arti stopped successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Arti", e)
        }
    }

    private fun getDataDirectory(): File {
        val artiDir = File(application.filesDir, "arti")
        if (!artiDir.exists()) {
            artiDir.mkdirs()
        }

        File(artiDir, "cache").apply { if (!exists()) mkdirs() }
        File(artiDir, "state").apply { if (!exists()) mkdirs() }

        return artiDir
    }

    class Builder(private val application: Application) {
        private var socksPort: Int = 9050
        private var dnsPort: Int = 9051
        private var logListener: ArtiLogListener? = null

        fun setSocksPort(port: Int) = apply {
            this.socksPort = port
        }

        fun setDnsPort(port: Int) = apply {
            this.dnsPort = port
        }

        fun setLogListener(listener: ArtiLogListener) = apply {
            this.logListener = listener
        }

        fun build(): ArtiProxy {
            return ArtiProxy(application, socksPort, dnsPort, logListener)
        }
    }
}
