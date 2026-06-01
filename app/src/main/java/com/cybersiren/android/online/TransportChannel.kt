package com.cybersiren.android.online

enum class TransportChannel {

    BLE_MESH,

    FIREBASE_CLOUD,

    NOSTR_RELAY
}

sealed class MessageProcessResult {

    data class Processed(
        val messageId: String,
        val channel: TransportChannel,
        val latencyMs: Long
    ) : MessageProcessResult()

    data class Duplicate(
        val messageId: String,
        val originalChannel: TransportChannel,
        val duplicateChannel: TransportChannel
    ) : MessageProcessResult()

    data class Invalid(
        val messageId: String?,
        val reason: String
    ) : MessageProcessResult()
}

interface MessageTransport {

    val channel: TransportChannel

    val isAvailable: Boolean

    suspend fun send(packet: ByteArray, targetGeohash: String? = null)

    fun observeIncoming(): kotlinx.coroutines.flow.Flow<IncomingPacket>

    suspend fun start()

    suspend fun stop()
}

data class IncomingPacket(
    val data: ByteArray,
    val channel: TransportChannel,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IncomingPacket
        return data.contentEquals(other.data) && channel == other.channel
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + channel.hashCode()
        return result
    }
}
