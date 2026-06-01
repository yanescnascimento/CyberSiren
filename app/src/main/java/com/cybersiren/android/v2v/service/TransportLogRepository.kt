package com.cybersiren.android.v2v.service

import android.content.Context
import android.util.Log
import com.cybersiren.android.v2v.model.TransportDirection
import com.cybersiren.android.v2v.model.TransportLogEntry
import com.cybersiren.android.v2v.model.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransportLogRepository {

    private const val TAG = "TransportLogRepo"
    private const val MAX_ENTRIES = 500

    private val _logs = MutableStateFlow<List<TransportLogEntry>>(emptyList())
    val logs: StateFlow<List<TransportLogEntry>> = _logs.asStateFlow()

    private val _bleAvgLatency = MutableStateFlow(0L)
    val bleAvgLatency: StateFlow<Long> = _bleAvgLatency.asStateFlow()

    private val _firebaseAvgLatency = MutableStateFlow(0L)
    val firebaseAvgLatency: StateFlow<Long> = _firebaseAvgLatency.asStateFlow()

    private val _bleLossPercent = MutableStateFlow(0f)
    val bleLossPercent: StateFlow<Float> = _bleLossPercent.asStateFlow()

    private val _firebaseLossPercent = MutableStateFlow(0f)
    val firebaseLossPercent: StateFlow<Float> = _firebaseLossPercent.asStateFlow()

    private val _bleSendCount = MutableStateFlow(0)
    val bleSendCount: StateFlow<Int> = _bleSendCount.asStateFlow()

    private val _bleRecvCount = MutableStateFlow(0)
    val bleRecvCount: StateFlow<Int> = _bleRecvCount.asStateFlow()

    private val _firebaseSendCount = MutableStateFlow(0)
    val firebaseSendCount: StateFlow<Int> = _firebaseSendCount.asStateFlow()

    private val _firebaseRecvCount = MutableStateFlow(0)
    val firebaseRecvCount: StateFlow<Int> = _firebaseRecvCount.asStateFlow()

    @Volatile private var fileWriter: BufferedWriter? = null
    @Volatile private var sessionFilePath: String? = null
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun startSessionLog(context: Context): String {
        synchronized(this) {
            closeSessionLog()
            val dir = File(context.getExternalFilesDir(null), "transport_logs").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "v2v-transport-$ts.csv")
            val writer = BufferedWriter(FileWriter(file,  true))

            writer.appendLine("timestamp_iso,timestamp_ms,transport,direction,success,latency_ms,payload_bytes,message_id,details")
            writer.flush()
            fileWriter = writer
            sessionFilePath = file.absolutePath
            Log.i(TAG, "Session log opened → ${file.absolutePath}")
            return file.absolutePath
        }
    }

    fun currentSessionLogPath(): String? = sessionFilePath

    fun closeSessionLog() {
        synchronized(this) {
            try { fileWriter?.flush(); fileWriter?.close() } catch (_: Exception) {}
            fileWriter = null
            sessionFilePath = null
        }
    }

    private fun appendToSessionLog(entry: TransportLogEntry) {
        val w = fileWriter ?: return
        try {
            val iso = timestampFmt.format(Date(entry.timestamp))
            val details = entry.details.replace(',', ';').replace('\n', ' ')
            val line = "$iso,${entry.timestamp},${entry.transport.name},${entry.direction.name}," +
                "${entry.success},${entry.latencyMs ?: ""},${entry.payloadBytes}," +
                "${entry.messageId},$details"
            w.appendLine(line)

            w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append session log: ${e.message}")
        }
    }

    fun logSend(
        transport: TransportType,
        messageId: String,
        latencyMs: Long,
        payloadBytes: Int = 0,
        details: String = ""
    ) {
        addEntry(
            TransportLogEntry(
                transport = transport,
                direction = TransportDirection.SEND,
                messageId = messageId,
                latencyMs = latencyMs,
                success = true,
                payloadBytes = payloadBytes,
                details = details
            )
        )
        Log.d(TAG, "SEND ${transport.label} | ${latencyMs}ms | $messageId")
    }

    fun logReceive(
        transport: TransportType,
        messageId: String,
        latencyMs: Long,
        payloadBytes: Int = 0,
        details: String = ""
    ) {
        addEntry(
            TransportLogEntry(
                transport = transport,
                direction = TransportDirection.RECEIVE,
                messageId = messageId,
                latencyMs = latencyMs,
                success = true,
                payloadBytes = payloadBytes,
                details = details
            )
        )
        Log.d(TAG, "RECV ${transport.label} | ${latencyMs}ms | $messageId")
    }

    fun logFailure(
        transport: TransportType,
        direction: TransportDirection,
        messageId: String,
        details: String = ""
    ) {
        addEntry(
            TransportLogEntry(
                transport = transport,
                direction = direction,
                messageId = messageId,
                latencyMs = null,
                success = false,
                details = details
            )
        )
        Log.w(TAG, "FAIL ${transport.label} ${direction.symbol} | $messageId | $details")
    }

    fun clearLogs() {
        synchronized(this) {
            _logs.value = emptyList()
            recomputeMetrics(emptyList())
        }
        Log.i(TAG, "Logs cleared")
    }

    @Synchronized
    private fun addEntry(entry: TransportLogEntry) {
        val current = _logs.value.toMutableList()
        current.add(0, entry)
        if (current.size > MAX_ENTRIES) {
            current.subList(MAX_ENTRIES, current.size).clear()
        }
        _logs.value = current
        recomputeMetrics(current)
        appendToSessionLog(entry)
    }

    private fun recomputeMetrics(entries: List<TransportLogEntry>) {

        val bleSends = entries.filter { it.transport == TransportType.BLE && it.direction == TransportDirection.SEND }
        val bleRecvs = entries.filter { it.transport == TransportType.BLE && it.direction == TransportDirection.RECEIVE }
        val bleAll = entries.filter { it.transport == TransportType.BLE }

        _bleSendCount.value = bleSends.size
        _bleRecvCount.value = bleRecvs.size
        _bleAvgLatency.value = bleAll
            .filter { it.success && it.latencyMs != null }
            .map { it.latencyMs!! }
            .takeIf { it.isNotEmpty() }
            ?.average()?.toLong() ?: 0L
        _bleLossPercent.value = if (bleAll.isNotEmpty()) {
            (bleAll.count { !it.success }.toFloat() / bleAll.size) * 100f
        } else 0f

        val fbSends = entries.filter { it.transport == TransportType.FIREBASE && it.direction == TransportDirection.SEND }
        val fbRecvs = entries.filter { it.transport == TransportType.FIREBASE && it.direction == TransportDirection.RECEIVE }
        val fbAll = entries.filter { it.transport == TransportType.FIREBASE }

        _firebaseSendCount.value = fbSends.size
        _firebaseRecvCount.value = fbRecvs.size
        _firebaseAvgLatency.value = fbAll
            .filter { it.success && it.latencyMs != null }
            .map { it.latencyMs!! }
            .takeIf { it.isNotEmpty() }
            ?.average()?.toLong() ?: 0L
        _firebaseLossPercent.value = if (fbAll.isNotEmpty()) {
            (fbAll.count { !it.success }.toFloat() / fbAll.size) * 100f
        } else 0f
    }
}
