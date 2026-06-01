package com.cybersiren.android.mesh

import android.util.Log
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.protocol.MessageType
import com.cybersiren.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class StoreForwardManager {

    companion object {
        private const val TAG = "StoreForwardManager"
        private const val MESSAGE_CACHE_TIMEOUT = com.cybersiren.android.util.AppConstants.StoreForward.MESSAGE_CACHE_TIMEOUT_MS
        private const val MAX_CACHED_MESSAGES = com.cybersiren.android.util.AppConstants.StoreForward.MAX_CACHED_MESSAGES
        private const val MAX_CACHED_MESSAGES_FAVORITES = com.cybersiren.android.util.AppConstants.StoreForward.MAX_CACHED_MESSAGES_FAVORITES
        private const val CLEANUP_INTERVAL = com.cybersiren.android.util.AppConstants.StoreForward.CLEANUP_INTERVAL_MS
    }

    private data class StoredMessage(
        val packet: BitchatPacket,
        val timestamp: Long,
        val messageID: String,
        val isForFavorite: Boolean
    )

    private val messageCache = Collections.synchronizedList(mutableListOf<StoredMessage>())
    private val favoriteMessageQueue = ConcurrentHashMap<String, MutableList<StoredMessage>>()
    private val deliveredMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val cachedMessagesSentToPeer = Collections.synchronizedSet(mutableSetOf<String>())

    var delegate: StoreForwardManagerDelegate? = null

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startPeriodicCleanup()
    }

    fun cacheMessage(packet: BitchatPacket, messageID: String) {

        if (packet.type == MessageType.NOISE_HANDSHAKE.value ||
            packet.type == MessageType.NOISE_ENCRYPTED.value ||
            packet.type == MessageType.ANNOUNCE.value ||
            packet.type == MessageType.LEAVE.value) {
            Log.d(TAG, "Skipping cache for message type: ${packet.type}")
            return
        }

        if (packet.recipientID != null && packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            Log.d(TAG, "Skipping cache for broadcast message")
            return
        }

        val recipientPeerID = packet.recipientID?.let { recipientID ->
            String(recipientID).replace("\u0000", "")
        }

        if (recipientPeerID.isNullOrEmpty()) {
            Log.w(TAG, "Cannot cache message without valid recipient")
            return
        }

        val isForFavorite = delegate?.isFavorite(recipientPeerID) ?: false

        val storedMessage = StoredMessage(
            packet = packet,
            timestamp = System.currentTimeMillis(),
            messageID = messageID,
            isForFavorite = isForFavorite
        )

        if (isForFavorite) {

            if (!favoriteMessageQueue.containsKey(recipientPeerID)) {
                favoriteMessageQueue[recipientPeerID] = mutableListOf()
            }
            favoriteMessageQueue[recipientPeerID]?.add(storedMessage)

            if (favoriteMessageQueue[recipientPeerID]?.size ?: 0 > MAX_CACHED_MESSAGES_FAVORITES) {
                favoriteMessageQueue[recipientPeerID]?.removeAt(0)
            }

            Log.d(TAG, "Cached message for favorite peer $recipientPeerID (${favoriteMessageQueue[recipientPeerID]?.size} total)")

        } else {

            cleanupMessageCache()

            messageCache.add(storedMessage)

            if (messageCache.size > MAX_CACHED_MESSAGES) {
                messageCache.removeAt(0)
            }

            Log.d(TAG, "Cached message for peer $recipientPeerID (${messageCache.size} total in cache)")
        }
    }

    fun sendCachedMessages(peerID: String) {
        if (cachedMessagesSentToPeer.contains(peerID)) {
            Log.d(TAG, "Already sent cached messages to $peerID")
            return
        }

        cachedMessagesSentToPeer.add(peerID)

        managerScope.launch {
            cleanupMessageCache()

            val messagesToSend = mutableListOf<StoredMessage>()

            favoriteMessageQueue[peerID]?.let { favoriteMessages ->
                val undeliveredFavorites = favoriteMessages.filter { !deliveredMessages.contains(it.messageID) }
                messagesToSend.addAll(undeliveredFavorites)
                favoriteMessageQueue.remove(peerID)
                Log.d(TAG, "Found ${undeliveredFavorites.size} cached favorite messages for $peerID")
            }

            val recipientMessages = messageCache.filter { storedMessage ->
                !deliveredMessages.contains(storedMessage.messageID) &&
                storedMessage.packet.recipientID?.let { recipientID ->
                    String(recipientID).replace("\u0000", "") == peerID
                } == true
            }
            messagesToSend.addAll(recipientMessages)

            if (recipientMessages.isNotEmpty()) {
                Log.d(TAG, "Found ${recipientMessages.size} cached regular messages for $peerID")
            }

            messagesToSend.sortBy { it.timestamp }

            if (messagesToSend.isNotEmpty()) {
                Log.i(TAG, "Sending ${messagesToSend.size} cached messages to $peerID")
            }

            val messageIDsToRemove = messagesToSend.map { it.messageID }
            deliveredMessages.addAll(messageIDsToRemove)

            messagesToSend.forEachIndexed { index, storedMessage ->
                delay(index * 10L)
                delegate?.sendPacket(storedMessage.packet)
            }

            messageCache.removeAll { messageIDsToRemove.contains(it.messageID) }

            if (messagesToSend.isNotEmpty()) {
                Log.d(TAG, "Finished sending ${messagesToSend.size} cached messages to $peerID")
            }
        }
    }

    fun shouldCacheForPeer(recipientPeerID: String): Boolean {

        val isOffline = !(delegate?.isPeerOnline(recipientPeerID) ?: false)
        val isRecipientFavorite = delegate?.isFavorite(recipientPeerID) ?: false

        return isOffline && isRecipientFavorite
    }

    fun markMessageAsDelivered(messageID: String) {
        deliveredMessages.add(messageID)
    }

    fun getCachedMessageCount(peerID: String): Int {
        val favoriteCount = favoriteMessageQueue[peerID]?.size ?: 0
        val regularCount = messageCache.count { storedMessage ->
            storedMessage.packet.recipientID?.let { recipientID ->
                String(recipientID).replace("\u0000", "") == peerID
            } == true
        }
        return favoriteCount + regularCount
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Store-Forward Manager Debug Info ===")
            appendLine("Regular Cache: ${messageCache.size}/${MAX_CACHED_MESSAGES}")
            appendLine("Favorite Queues: ${favoriteMessageQueue.size}")

            favoriteMessageQueue.forEach { (peerID, messages) ->
                appendLine("  - $peerID: ${messages.size} messages")
            }

            appendLine("Delivered Messages: ${deliveredMessages.size}")
            appendLine("Peers Sent Cache: ${cachedMessagesSentToPeer.size}")

            val now = System.currentTimeMillis()
            val regularCacheAges = messageCache.map { (now - it.timestamp) / 1000 }
            if (regularCacheAges.isNotEmpty()) {
                val avgAge = regularCacheAges.average().toInt()
                val maxAge = regularCacheAges.maxOrNull() ?: 0
                appendLine("Regular Cache Age: avg ${avgAge}s, max ${maxAge}s")
            }
        }
    }

    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupMessageCache()
                cleanupDeliveredMessages()
            }
        }
    }

    private fun cleanupMessageCache() {
        val cutoffTime = System.currentTimeMillis() - MESSAGE_CACHE_TIMEOUT
        val sizeBefore = messageCache.size
        val removed = messageCache.removeAll { !it.isForFavorite && it.timestamp < cutoffTime }

        if (removed) {
            val removedCount = sizeBefore - messageCache.size
            Log.d(TAG, "Cleaned up $removedCount old cached messages")
        }
    }

    private fun cleanupDeliveredMessages() {
        if (deliveredMessages.size > 1000) {
            Log.d(TAG, "Clearing delivered messages set (${deliveredMessages.size} entries)")
            deliveredMessages.clear()
        }

        if (cachedMessagesSentToPeer.size > 200) {
            Log.d(TAG, "Clearing cached messages sent tracking (${cachedMessagesSentToPeer.size} entries)")
            cachedMessagesSentToPeer.clear()
        }
    }

    fun clearAllCache() {
        messageCache.clear()
        favoriteMessageQueue.clear()
        deliveredMessages.clear()
        cachedMessagesSentToPeer.clear()
        Log.d(TAG, "Cleared all cached message data")
    }

    fun forceCleanup() {
        cleanupMessageCache()
        cleanupDeliveredMessages()
    }

    fun shutdown() {
        managerScope.cancel()
        clearAllCache()
    }
}

interface StoreForwardManagerDelegate {
    fun isFavorite(peerID: String): Boolean
    fun isPeerOnline(peerID: String): Boolean
    fun sendPacket(packet: BitchatPacket)
}
