package com.cybersiren.android.v2v.service

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class AlertDeduplicationService {

    companion object {
        private const val TAG = "AlertDeduplication"
        private const val EXPIRY_MS = 5 * 60 * 1000L
        private const val CLEANUP_INTERVAL_MS = 60 * 1000L
    }

    private val processedAlerts = ConcurrentHashMap<String, Long>()

    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startCleanupTask()
    }

    fun isDuplicate(messageId: String): Boolean {
        val existingTimestamp = processedAlerts[messageId]
        if (existingTimestamp != null) {
            val age = System.currentTimeMillis() - existingTimestamp
            if (age < EXPIRY_MS) {
                Log.d(TAG, "Duplicate alert detected: $messageId (age: ${age}ms)")
                return true
            }

        }
        return false
    }

    fun markProcessed(messageId: String) {
        processedAlerts[messageId] = System.currentTimeMillis()
        Log.d(TAG, "Marked alert as processed: $messageId (total: ${processedAlerts.size})")
    }

    fun checkAndMark(messageId: String): Boolean {
        val now = System.currentTimeMillis()
        val existing = processedAlerts.putIfAbsent(messageId, now)

        if (existing != null) {
            val age = now - existing
            if (age < EXPIRY_MS) {
                return false
            }

            processedAlerts[messageId] = now
        }

        return true
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        val expiredKeys = processedAlerts.entries
            .filter { now - it.value > EXPIRY_MS }
            .map { it.key }

        expiredKeys.forEach { processedAlerts.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expiredKeys.size} expired alerts (remaining: ${processedAlerts.size})")
        }
    }

    private fun startCleanupTask() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanup()
            }
        }
    }

    fun getCacheSize(): Int = processedAlerts.size

    fun clear() {
        processedAlerts.clear()
        Log.d(TAG, "Cleared all cached alerts")
    }

    fun shutdown() {
        cleanupJob?.cancel()
        scope.cancel()
        processedAlerts.clear()
        Log.d(TAG, "AlertDeduplicationService shutdown")
    }
}
