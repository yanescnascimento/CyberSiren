package com.cybersiren.android.online

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class MessageOrchestrator private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "MessageOrchestrator"

        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        private const val MAX_CACHE_SIZE = 1000

        private const val CLEANUP_INTERVAL_MS = 60 * 1000L

        @Volatile
        private var INSTANCE: MessageOrchestrator? = null

        fun getInstance(context: Context): MessageOrchestrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageOrchestrator(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        fun tryGetInstance(): MessageOrchestrator? = INSTANCE
    }

    private val orchestratorScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineName("MessageOrchestrator")
    )

    data class ProcessedMessage(
        val messageId: String,
        val channel: TransportChannel,
        val processedAtMs: Long,
        val packetHash: String
    )

    data class ChannelStats(
        var firstArrivalCount: Int = 0,
        var duplicateCount: Int = 0,
        var totalLatencyMs: Long = 0,
        var minLatencyMs: Long = Long.MAX_VALUE,
        var maxLatencyMs: Long = 0
    ) {
        val averageLatencyMs: Double
            get() = if (firstArrivalCount > 0) totalLatencyMs.toDouble() / firstArrivalCount else 0.0
    }

    private val processedMessages = ConcurrentHashMap<String, ProcessedMessage>()

    private val channelStats = ConcurrentHashMap<TransportChannel, ChannelStats>()

    private val _processedMessagesFlow = MutableSharedFlow<Pair<IncomingPacket, MessageProcessResult.Processed>>()
    val processedMessagesFlow: SharedFlow<Pair<IncomingPacket, MessageProcessResult.Processed>> = _processedMessagesFlow

    private val _analyticsFlow = MutableSharedFlow<AnalyticsEvent>()
    val analyticsFlow: SharedFlow<AnalyticsEvent> = _analyticsFlow

    private val transports = mutableListOf<MessageTransport>()

    private var isRunning = false
    private var cleanupJob: Job? = null

    init {

        TransportChannel.values().forEach { channel ->
            channelStats[channel] = ChannelStats()
        }
    }

    fun start() {
        if (isRunning) {
            Log.w(TAG, "Orchestrator already running")
            return
        }

        isRunning = true
        Log.i(TAG, "Starting MessageOrchestrator with ${transports.size} transports")

        cleanupJob = orchestratorScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupExpiredMessages()
            }
        }

        transports.forEach { transport ->
            orchestratorScope.launch {
                try {
                    transport.start()
                    transport.observeIncoming().collect { packet ->
                        processIncomingPacket(packet)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in transport ${transport.channel}: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        cleanupJob?.cancel()

        orchestratorScope.launch {
            transports.forEach { transport ->
                try {
                    transport.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping transport ${transport.channel}: ${e.message}")
                }
            }
        }

        Log.i(TAG, "MessageOrchestrator stopped")
    }

    fun registerTransport(transport: MessageTransport) {
        transports.add(transport)
        Log.i(TAG, "Registered transport: ${transport.channel}")
    }

    suspend fun processIncomingPacket(packet: IncomingPacket): MessageProcessResult {
        val receiveTime = System.currentTimeMillis()

        val messageId = extractMessageId(packet.data)
        if (messageId == null) {
            Log.w(TAG, "Could not extract message_id from packet via ${packet.channel}")
            return MessageProcessResult.Invalid(null, "Missing or invalid message_id")
        }

        val packetHash = calculatePacketHash(packet.data)

        val existingMessage = processedMessages[messageId]

        if (existingMessage != null) {

            val stats = channelStats[packet.channel]!!
            stats.duplicateCount++

            Log.d(TAG, "DUPLICATE: message_id=$messageId first via ${existingMessage.channel}, duplicate via ${packet.channel}")

            _analyticsFlow.emit(AnalyticsEvent.DuplicateReceived(
                messageId = messageId,
                originalChannel = existingMessage.channel,
                duplicateChannel = packet.channel,
                delayMs = receiveTime - existingMessage.processedAtMs
            ))

            return MessageProcessResult.Duplicate(
                messageId = messageId,
                originalChannel = existingMessage.channel,
                duplicateChannel = packet.channel
            )
        }

        val processedMessage = ProcessedMessage(
            messageId = messageId,
            channel = packet.channel,
            processedAtMs = receiveTime,
            packetHash = packetHash
        )

        processedMessages[messageId] = processedMessage

        val latency = receiveTime - packet.receivedAtMs
        val stats = channelStats[packet.channel]!!
        stats.firstArrivalCount++
        stats.totalLatencyMs += latency
        stats.minLatencyMs = minOf(stats.minLatencyMs, latency)
        stats.maxLatencyMs = maxOf(stats.maxLatencyMs, latency)

        Log.i(TAG, "PROCESSED: message_id=$messageId via ${packet.channel} (latency=${latency}ms)")

        if (processedMessages.size > MAX_CACHE_SIZE) {
            cleanupExpiredMessages()
        }

        val result = MessageProcessResult.Processed(
            messageId = messageId,
            channel = packet.channel,
            latencyMs = latency
        )

        _processedMessagesFlow.emit(Pair(packet, result))

        _analyticsFlow.emit(AnalyticsEvent.MessageProcessed(
            messageId = messageId,
            channel = packet.channel,
            latencyMs = latency
        ))

        return result
    }

    suspend fun broadcastMessage(packet: ByteArray, targetGeohash: String? = null) {
        val availableTransports = transports.filter { it.isAvailable }

        if (availableTransports.isEmpty()) {
            Log.w(TAG, "No transports available for broadcast")
            return
        }

        Log.i(TAG, "Broadcasting message via ${availableTransports.size} transports: ${availableTransports.map { it.channel }}")

        coroutineScope {
            availableTransports.forEach { transport ->
                launch {
                    try {
                        transport.send(packet, targetGeohash)
                        Log.d(TAG, "Sent via ${transport.channel}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send via ${transport.channel}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun extractMessageId(data: ByteArray): String? {
        return try {

            val jsonString = String(data, Charsets.UTF_8)
            if (jsonString.startsWith("{")) {
                val messageIdRegex = """"message_id"\s*:\s*"([^"]+)"""".toRegex()
                val match = messageIdRegex.find(jsonString)
                if (match != null) {
                    return match.groupValues[1]
                }
            }

            extractMessageIdFromBinaryProtocol(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting message_id: ${e.message}")
            null
        }
    }

    private fun extractMessageIdFromBinaryProtocol(data: ByteArray): String? {
        if (data.size < 13) return null

        val dataString = data.map { String.format("%02x", it) }.joinToString("")
        val uuidRegex = """([0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12})""".toRegex(RegexOption.IGNORE_CASE)
        val match = uuidRegex.find(dataString)

        return match?.value?.let { rawUuid ->

            if (rawUuid.contains("-")) rawUuid
            else "${rawUuid.substring(0,8)}-${rawUuid.substring(8,12)}-${rawUuid.substring(12,16)}-${rawUuid.substring(16,20)}-${rawUuid.substring(20,32)}"
        }
    }

    private fun calculatePacketHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { String.format("%02x", it) }
    }

    private fun cleanupExpiredMessages() {
        val now = System.currentTimeMillis()
        val expiredKeys = processedMessages.entries
            .filter { now - it.value.processedAtMs > CACHE_TTL_MS }
            .map { it.key }

        expiredKeys.forEach { processedMessages.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expiredKeys.size} expired messages from cache")
        }
    }

    fun getChannelStatistics(): Map<TransportChannel, ChannelStats> {
        return channelStats.toMap()
    }

    fun resetStatistics() {
        TransportChannel.values().forEach { channel ->
            channelStats[channel] = ChannelStats()
        }
    }

    fun getCacheStatus(): String {
        return "Cache size: ${processedMessages.size}, " +
               "Stats: ${channelStats.entries.joinToString { "${it.key}=${it.value.firstArrivalCount}/${it.value.duplicateCount}" }}"
    }

    fun isMessageProcessed(messageId: String): Boolean {
        return processedMessages.containsKey(messageId)
    }

    fun markAsProcessed(messageId: String, channel: TransportChannel) {
        if (!processedMessages.containsKey(messageId)) {
            processedMessages[messageId] = ProcessedMessage(
                messageId = messageId,
                channel = channel,
                processedAtMs = System.currentTimeMillis(),
                packetHash = ""
            )
        }
    }

    fun cleanup() {
        stop()
        orchestratorScope.cancel()
        processedMessages.clear()
        INSTANCE = null
    }
}

sealed class AnalyticsEvent {
    data class MessageProcessed(
        val messageId: String,
        val channel: TransportChannel,
        val latencyMs: Long
    ) : AnalyticsEvent()

    data class DuplicateReceived(
        val messageId: String,
        val originalChannel: TransportChannel,
        val duplicateChannel: TransportChannel,
        val delayMs: Long
    ) : AnalyticsEvent()

    data class TransportStatusChanged(
        val channel: TransportChannel,
        val isAvailable: Boolean
    ) : AnalyticsEvent()
}
