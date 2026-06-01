package com.cybersiren.android.mesh

import android.util.Log
import com.cybersiren.android.crypto.EncryptionService
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.protocol.MessageType
import com.cybersiren.android.model.RoutedPacket
import com.cybersiren.android.util.toHexString
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.mutableSetOf

class SecurityManager(private val encryptionService: EncryptionService, private val myPeerID: String) {

    companion object {
        private const val TAG = "SecurityManager"
        private const val MESSAGE_TIMEOUT = com.cybersiren.android.util.AppConstants.Security.MESSAGE_TIMEOUT_MS
        private const val CLEANUP_INTERVAL = com.cybersiren.android.util.AppConstants.Security.CLEANUP_INTERVAL_MS
        private const val MAX_PROCESSED_MESSAGES = com.cybersiren.android.util.AppConstants.Security.MAX_PROCESSED_MESSAGES
        private const val MAX_PROCESSED_KEY_EXCHANGES = com.cybersiren.android.util.AppConstants.Security.MAX_PROCESSED_KEY_EXCHANGES
    }

    private val processedMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedKeyExchanges = Collections.synchronizedSet(mutableSetOf<String>())
    private val messageTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())

    var delegate: SecurityManagerDelegate? = null

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startPeriodicCleanup()
    }

    fun validatePacket(packet: BitchatPacket, peerID: String): Boolean {

        if (peerID == myPeerID) {
            Log.d(TAG, "Skipping validation for our own packet")
            return false
        }

        val currentTime = System.currentTimeMillis()
        val messageType = MessageType.fromValue(packet.type)

        val messageID = generateMessageID(packet, peerID)

        if (processedMessages.contains(messageID)) {

            val isFreshAnnounce = messageType == MessageType.ANNOUNCE &&
                    packet.ttl >= com.cybersiren.android.util.AppConstants.MESSAGE_TTL_HOPS

            if (!isFreshAnnounce) {
                Log.d(TAG, "Dropping duplicate packet: $messageID")
                return false
            }
            Log.d(TAG, "Allowing duplicate ANNOUNCE from direct neighbor: $messageID")
        }

        processedMessages.add(messageID)
        messageTimestamps[messageID] = currentTime

        if (!verifyPacketSignature(packet, peerID)) {
            Log.w(TAG, "Dropping packet from $peerID due to signature verification failure")
            return false
        }

        Log.d(TAG, "Packet validation passed for $peerID, messageID: $messageID")
        return true
    }

    suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (packet.recipientID?.toHexString() != myPeerID) {
            Log.d(TAG, "Skipping handshake not addressed to us: $peerID")
            return false
        }

        if (peerID == myPeerID) return false

        var forcedRehandshake = false
        if (encryptionService.hasEstablishedSession(peerID)) {
            Log.d(TAG, "Received new Noise handshake from $peerID with an existing session. Dropping old session to re-handshake.")
            try {
                encryptionService.removePeer(peerID)
                forcedRehandshake = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove existing Noise session for $peerID: ${e.message}")
            }
        }

        if (packet.payload.isEmpty()) {
            Log.w(TAG, "Noise handshake packet has empty payload")
            return false
        }

        val exchangeKey = "$peerID-${packet.payload.sliceArray(0 until minOf(16, packet.payload.size)).contentHashCode()}"

        if (!forcedRehandshake && processedKeyExchanges.contains(exchangeKey)) {
            Log.d(TAG, "Already processed handshake: $exchangeKey")
            return false
        }
        Log.d(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")
        processedKeyExchanges.add(exchangeKey)

        try {

            val response = encryptionService.processHandshakeMessage(packet.payload, peerID)

            if (response != null) {
                Log.d(TAG, "Successfully processed Noise handshake from $peerID, sending response")

                delegate?.sendHandshakeResponse(peerID, response)
            }

            if (encryptionService.hasEstablishedSession(peerID)) {
                Log.d(TAG, "Noise handshake completed with $peerID")
                delegate?.onKeyExchangeCompleted(peerID, packet.payload)
            }
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
            return false
        }
    }

    fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
        return packet.signature?.let { signature ->
            try {
                val isValid = encryptionService.verify(signature, packet.payload, peerID)
                if (!isValid) {
                    Log.w(TAG, "Invalid signature for packet from $peerID")
                }
                isValid
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify signature from $peerID: ${e.message}")
                false
            }
        } ?: true
    }

    fun signPacket(payload: ByteArray): ByteArray? {
        return try {
            encryptionService.sign(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign packet: ${e.message}")
            null
        }
    }

    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
        return try {
            encryptionService.encrypt(data, recipientPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $recipientPeerID: ${e.message}")
            null
        }
    }

    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
        return try {
            encryptionService.decrypt(encryptedData, senderPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $senderPeerID: ${e.message}")
            null
        }
    }

    fun getCombinedPublicKeyData(): ByteArray {
        return encryptionService.getCombinedPublicKeyData()
    }

    private fun generateMessageID(packet: BitchatPacket, peerID: String): String {
        return when (MessageType.fromValue(packet.type)) {
            MessageType.FRAGMENT -> {

                "${packet.timestamp}-$peerID-${packet.type}-${packet.payload.contentHashCode()}"
            }
            else -> {

                val payloadHash = packet.payload.sliceArray(0 until minOf(64, packet.payload.size)).contentHashCode()
                "${packet.timestamp}-$peerID-$payloadHash"
            }
        }
    }

    private fun verifyPacketSignature(packet: BitchatPacket, peerID: String): Boolean {
        try {

            if (MessageType.fromValue(packet.type) !in setOf(
                    MessageType.ANNOUNCE,
                    MessageType.MESSAGE,
                    MessageType.FILE_TRANSFER
                )) {
                return true
            }

            if (packet.signature == null) {
                Log.w(TAG, "Signature check for $peerID: NO_SIGNATURE (packet type ${packet.type})")
                return false
            }

            var signingPublicKey: ByteArray? = null

            if (MessageType.fromValue(packet.type) == MessageType.ANNOUNCE) {

                try {
                    val announcement = com.cybersiren.android.model.IdentityAnnouncement.decode(packet.payload)
                    signingPublicKey = announcement?.signingPublicKey
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode announcement for key extraction: ${e.message}")
                }
            } else {

                val peerInfo = delegate?.getPeerInfo(peerID)
                signingPublicKey = peerInfo?.signingPublicKey
            }

            if (signingPublicKey == null) {

                Log.w(TAG, "Signature check for $peerID: NO_SIGNING_KEY_AVAILABLE (packet type ${packet.type})")
                return false
            }

            val packetDataForSigning = packet.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "Signature check for $peerID: ENCODING_ERROR (packet type ${packet.type})")
                return false
            }

            val signature = packet.signature!!
            val isSignatureValid = encryptionService.verifyEd25519Signature(
                signature,
                packetDataForSigning,
                signingPublicKey
            )

            if (isSignatureValid) {

                return true
            } else {
                Log.w(TAG, "Signature INVALID for $peerID (type ${packet.type})")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error for $peerID: ${e.message}")
            return false
        }
    }

    fun hasKeysForPeer(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Security Manager Debug Info ===")
            appendLine("Processed Messages: ${processedMessages.size}")
            appendLine("Processed Key Exchanges: ${processedKeyExchanges.size}")
            appendLine("Message Timestamps: ${messageTimestamps.size}")

            if (processedKeyExchanges.isNotEmpty()) {
                appendLine("Key Exchange History:")
                processedKeyExchanges.take(10).forEach { exchange ->
                    appendLine("  - $exchange")
                }
                if (processedKeyExchanges.size > 10) {
                    appendLine("  ... and ${processedKeyExchanges.size - 10} more")
                }
            }
        }
    }

    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldData()
            }
        }
    }

    private fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - MESSAGE_TIMEOUT
        var removedCount = 0

        val messagesToRemove = messageTimestamps.entries.filter { (_, timestamp) ->
            timestamp < cutoffTime
        }.map { it.key }

        messagesToRemove.forEach { messageId ->
            messageTimestamps.remove(messageId)
            if (processedMessages.remove(messageId)) {
                removedCount++
            }
        }

        if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
            val excess = processedMessages.size - MAX_PROCESSED_MESSAGES
            val toRemove = processedMessages.take(excess)
            processedMessages.removeAll(toRemove.toSet())
            removeFromMessageTimestamps(toRemove)
            removedCount += excess
        }

        if (processedKeyExchanges.size > MAX_PROCESSED_KEY_EXCHANGES) {
            val excess = processedKeyExchanges.size - MAX_PROCESSED_KEY_EXCHANGES
            val toRemove = processedKeyExchanges.take(excess)
            processedKeyExchanges.removeAll(toRemove.toSet())
        }

        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount old processed messages")
        }
    }

    private fun removeFromMessageTimestamps(messageIds: List<String>) {
        messageIds.forEach { messageId ->
            messageTimestamps.remove(messageId)
        }
    }

    fun clearAllData() {
        processedMessages.clear()
        processedKeyExchanges.clear()
        messageTimestamps.clear()
    }

    fun shutdown() {
        managerScope.cancel()
        clearAllData()
    }
}

interface SecurityManagerDelegate {
    fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray)
    fun sendHandshakeResponse(peerID: String, response: ByteArray)
    fun getPeerInfo(peerID: String): PeerInfo?
}
