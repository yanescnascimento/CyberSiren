package com.cybersiren.android.mesh

import android.util.Log
import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.model.BitchatMessageType
import com.cybersiren.android.model.IdentityAnnouncement
import com.cybersiren.android.model.RoutedPacket
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.protocol.MessageType
import com.cybersiren.android.util.toHexString
import kotlinx.coroutines.*
import java.util.*
import kotlin.random.Random

class MessageHandler(private val myPeerID: String, private val appContext: android.content.Context) {

    companion object {
        private const val TAG = "MessageHandler"
    }

    var delegate: MessageHandlerDelegate? = null

    var packetProcessor: PacketProcessor? = null

    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun handleNoiseEncrypted(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        Log.d(TAG, "Processing Noise encrypted message from $peerID (${packet.payload.size} bytes)")

        if (peerID == myPeerID) return

        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            Log.d(TAG, "Encrypted message not for me (for $recipientID, I am $myPeerID)")
            return
        }

        try {

            val decryptedData = delegate?.decryptFromPeer(packet.payload, peerID)
            if (decryptedData == null) {
                Log.w(TAG, "Failed to decrypt Noise message from $peerID - may need handshake")
                return
            }

            if (decryptedData.isEmpty()) {
                Log.w(TAG, "Decrypted data is empty from $peerID")
                return
            }

            val noisePayload = com.cybersiren.android.model.NoisePayload.decode(decryptedData)
            if (noisePayload == null) {
                Log.w(TAG, "Failed to parse NoisePayload from $peerID")
                return
            }

            Log.d(TAG, "Decrypted NoisePayload type ${noisePayload.type} from $peerID")

            when (noisePayload.type) {
                com.cybersiren.android.model.NoisePayloadType.PRIVATE_MESSAGE -> {

                    val privateMessage = com.cybersiren.android.model.PrivateMessagePacket.decode(noisePayload.data)
                    if (privateMessage != null) {
                        Log.d(TAG, "Decrypted TLV PM from $peerID: ${privateMessage.content.take(30)}...")

                        val pmContent = privateMessage.content
                        if (pmContent.startsWith("[FAVORITED]") || pmContent.startsWith("[UNFAVORITED]")) {
                            handleFavoriteNotificationFromMesh(pmContent, peerID)

                            sendDeliveryAck(privateMessage.messageID, peerID)
                            return
                        }

                        val message = BitchatMessage(
                            id = privateMessage.messageID,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = privateMessage.content,
                            timestamp = java.util.Date(packet.timestamp.toLong()),
                            isRelay = false,
                            originalSender = null,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID,
                            mentions = null
                        )

                        delegate?.onMessageReceived(message)

                        sendDeliveryAck(privateMessage.messageID, peerID)
                    }
                }

                com.cybersiren.android.model.NoisePayloadType.FILE_TRANSFER -> {

                    val file = com.cybersiren.android.model.BitchatFilePacket.decode(noisePayload.data)
                    if (file != null) {
                        Log.d(TAG, "Decrypted encrypted file from $peerID: name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}'")
                        val uniqueMsgId = java.util.UUID.randomUUID().toString().uppercase()
                        val savedPath = com.cybersiren.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                        val message = BitchatMessage(
                            id = uniqueMsgId,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = savedPath,
                            type = com.cybersiren.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                            timestamp = java.util.Date(packet.timestamp.toLong()),
                            isRelay = false,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID
                        )

                        Log.d(TAG, "Saved encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                        delegate?.onMessageReceived(message)

                        sendDeliveryAck(uniqueMsgId, peerID)
                    } else {
                        Log.w(TAG, "Failed to decode encrypted file transfer from $peerID")
                    }
                }

                com.cybersiren.android.model.NoisePayloadType.DELIVERED -> {

                    val messageID = String(noisePayload.data, Charsets.UTF_8)
                    Log.d(TAG, "Delivery ACK received from $peerID for message $messageID")

                    delegate?.onDeliveryAckReceived(messageID, peerID)
                }

                com.cybersiren.android.model.NoisePayloadType.READ_RECEIPT -> {

                    val messageID = String(noisePayload.data, Charsets.UTF_8)
                    Log.d(TAG, "Read receipt received from $peerID for message $messageID")

                    delegate?.onReadReceiptReceived(messageID, peerID)
                }
                com.cybersiren.android.model.NoisePayloadType.VERIFY_CHALLENGE -> {
                    Log.d(TAG, "Verify challenge received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVerifyChallengeReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
                com.cybersiren.android.model.NoisePayloadType.VERIFY_RESPONSE -> {
                    Log.d(TAG, "Verify response received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVerifyResponseReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing Noise encrypted message from $peerID: ${e.message}")
        }
    }

    private suspend fun sendDeliveryAck(messageID: String, senderPeerID: String) {
        try {

            val ackPayload = com.cybersiren.android.model.NoisePayload(
                type = com.cybersiren.android.model.NoisePayloadType.DELIVERED,
                data = messageID.toByteArray(Charsets.UTF_8)
            )

            val encryptedPayload = delegate?.encryptForPeer(ackPayload.encode(), senderPeerID)
            if (encryptedPayload == null) {
                Log.w(TAG, "Failed to encrypt delivery ACK for $senderPeerID")
                return
            }

                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(senderPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encryptedPayload,
                    signature = null,
                    ttl = com.cybersiren.android.util.AppConstants.MESSAGE_TTL_HOPS
                )

            delegate?.sendPacket(packet)
            Log.d(TAG, "Sent delivery ACK to $senderPeerID for message $messageID")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery ACK to $senderPeerID: ${e.message}")
        }
    }

    suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (peerID == myPeerID) return false

        val now = System.currentTimeMillis()
        val age = now - packet.timestamp.toLong()
        if (age > com.cybersiren.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
            Log.w(TAG, "Ignoring stale ANNOUNCE from ${peerID.take(8)} (age=${age}ms > ${com.cybersiren.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
            return false
        }

        val announcement = IdentityAnnouncement.decode(packet.payload)
        if (announcement == null) {
            Log.w(TAG, "Failed to decode announce from $peerID as iOS-compatible TLV format")
            return false
        }

        var verified = false
        if (packet.signature != null) {

            verified = delegate?.verifyEd25519Signature(packet.signature!!, packet.toBinaryDataForSigning()!!, announcement.signingPublicKey) ?: false
            if (!verified) {
                Log.w(TAG, "Signature verification for announce failed ${peerID.take(8)}")
            }
        }

        val existingPeer = delegate?.getPeerInfo(peerID)

        if (existingPeer != null && existingPeer.noisePublicKey != null && !existingPeer.noisePublicKey!!.contentEquals(announcement.noisePublicKey)) {
            Log.w(TAG, "Announce key mismatch for ${peerID.take(8)}... — keeping unverified")
            verified = false
        }

        if (!verified) {
            Log.w(TAG, "Ignoring unverified announce from ${peerID.take(8)}...")
            return false
        }

        Log.d(TAG, "Verified announce from $peerID: nickname=${announcement.nickname}, " +
                "noisePublicKey=${announcement.noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., " +
                "signingPublicKey=${announcement.signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}...")

        val nickname = announcement.nickname
        val noisePublicKey = announcement.noisePublicKey
        val signingPublicKey = announcement.signingPublicKey

        val isFirstAnnounce = delegate?.updatePeerInfo(
            peerID = peerID,
            nickname = nickname,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            isVerified = true
        ) ?: false

        delegate?.updatePeerIDBinding(
            newPeerID = peerID,
            nickname = nickname,
            publicKey = noisePublicKey,
            previousPeerID = null
        )

        try {
            val neighborsOrNull = com.cybersiren.android.services.meshgraph.GossipTLV.decodeNeighborsFromAnnouncementPayload(packet.payload)
            com.cybersiren.android.services.meshgraph.MeshGraphService.getInstance()
                .updateFromAnnouncement(peerID, nickname, neighborsOrNull, packet.timestamp)
        } catch (_: Exception) { }

        Log.d(TAG, "Processed verified TLV announce: stored identity for $peerID")
        return isFirstAnnounce
    }

    suspend fun handleNoiseHandshake(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        Log.d(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")

        if (peerID == myPeerID) return

        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            Log.d(TAG, "Handshake not for me (for $recipientID, I am $myPeerID)")
            return
        }

        try {

            val response = delegate?.processNoiseHandshakeMessage(packet.payload, peerID)

            if (response != null) {
                Log.d(TAG, "Generated handshake response for $peerID (${response.size} bytes)")

                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    signature = null,
                    ttl = com.cybersiren.android.util.AppConstants.MESSAGE_TTL_HOPS
                )

                delegate?.sendPacket(responsePacket)
                Log.d(TAG, "Sent handshake response to $peerID")
            }

            val hasSession = delegate?.hasNoiseSession(peerID) ?: false
            if (hasSession) {
                Log.d(TAG, "Noise session established with $peerID")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
        }
    }

    suspend fun handleMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (peerID == myPeerID) return
        val senderNickname = delegate?.getPeerNickname(peerID)
        if (senderNickname != null) {
            Log.d(TAG, "Received message from $senderNickname")
            delegate?.updatePeerNickname(peerID, senderNickname)
        }

        val recipientID = packet.recipientID?.takeIf { !it.contentEquals(delegate?.getBroadcastRecipient()) }

        if (recipientID == null) {

            handleBroadcastMessage(routed)
        } else if (recipientID.toHexString() == myPeerID) {

            handlePrivateMessage(packet, peerID)
        }

    }

    private suspend fun handleBroadcastMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        val peerInfo = delegate?.getPeerInfo(peerID)
        if (peerInfo == null || !peerInfo.isVerifiedNickname) {
            Log.w(TAG, "Dropping public message from unverified or unknown peer ${peerID.take(8)}...")
            return
        }

        try {

            val isFileTransfer = com.cybersiren.android.protocol.MessageType.fromValue(packet.type) == com.cybersiren.android.protocol.MessageType.FILE_TRANSFER
            val file = com.cybersiren.android.model.BitchatFilePacket.decode(packet.payload)
            if (file != null) {
                if (isFileTransfer) {
                    Log.d(TAG, "FILE_TRANSFER decode success (broadcast): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = com.cybersiren.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                val message = BitchatMessage(
                    id = java.util.UUID.randomUUID().toString().uppercase(),
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = com.cybersiren.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Date(packet.timestamp.toLong())
                )
                Log.d(TAG, "Saved incoming file to $savedPath")
                delegate?.onMessageReceived(message)
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "FILE_TRANSFER decode failed (broadcast) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            val message = BitchatMessage(
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = String(packet.payload, Charsets.UTF_8),
                senderPeerID = peerID,
                timestamp = Date(packet.timestamp.toLong())
            )
            delegate?.onMessageReceived(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process broadcast message: ${e.message}")
        }
    }

    private suspend fun handlePrivateMessage(packet: BitchatPacket, peerID: String) {
        try {

            if (packet.signature != null && !delegate?.verifySignature(packet, peerID)!!) {
                Log.w(TAG, "Invalid signature for private message from $peerID")
                return
            }

            val isFileTransfer = com.cybersiren.android.protocol.MessageType.fromValue(packet.type) == com.cybersiren.android.protocol.MessageType.FILE_TRANSFER
            val file = com.cybersiren.android.model.BitchatFilePacket.decode(packet.payload)
            if (file != null) {
                if (isFileTransfer) {
                    Log.d(TAG, "FILE_TRANSFER decode success (private): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = com.cybersiren.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                val message = BitchatMessage(
                    id = java.util.UUID.randomUUID().toString().uppercase(),
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = com.cybersiren.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Date(packet.timestamp.toLong()),
                    isPrivate = true,
                    recipientNickname = delegate?.getMyNickname()
                )
                Log.d(TAG, "Saved incoming file to $savedPath")
                delegate?.onMessageReceived(message)
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "FILE_TRANSFER decode failed (private) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            val message = BitchatMessage(
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = String(packet.payload, Charsets.UTF_8),
                senderPeerID = peerID,
                timestamp = Date(packet.timestamp.toLong())
            )
            delegate?.onMessageReceived(message)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process private message from $peerID: ${e.message}")
        }
    }

    suspend fun handleLeave(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val content = String(packet.payload, Charsets.UTF_8)

        if (content.startsWith("#")) {

            delegate?.onChannelLeave(content, peerID)
        } else {

            delegate?.removePeer(peerID)
        }

    }

    suspend fun handleEmergencyAlert(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (peerID == myPeerID) return

        Log.d(TAG, "Processing EMERGENCY_ALERT from $peerID (${packet.payload.size} bytes)")

        delegate?.onEmergencyAlertReceived(packet, peerID)
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Message Handler Debug Info ===")
            appendLine("Handler Scope Active: ${handlerScope.isActive}")
            appendLine("My Peer ID: $myPeerID")
        }
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0

        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }

        return result
    }

    fun shutdown() {
        handlerScope.cancel()
    }

    private fun handleFavoriteNotificationFromMesh(content: String, fromPeerID: String) {
        try {
            val isFavorite = content.startsWith("[FAVORITED]")
            val npub = content.substringAfter(":", "").trim().takeIf { it.startsWith("npub1") }

            val peerInfo = delegate?.getPeerInfo(fromPeerID)
            val noiseKey = peerInfo?.noisePublicKey
            if (noiseKey != null) {
                com.cybersiren.android.favorites.FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, isFavorite)
                if (npub != null) {

                    com.cybersiren.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, npub)
                    com.cybersiren.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(fromPeerID, npub)
                }

                val rel = com.cybersiren.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                val guidance = if (isFavorite) {
                    if (rel?.isFavorite == true) {
                        " — mutual! You can continue DMs via Nostr when out of mesh."
                    } else {
                        " — favorite back to continue DMs later."
                    }
                } else {
                    ". DMs over Nostr will pause unless you both favorite again."
                }

                val action = if (isFavorite) "favorited" else "unfavorited"
                val sys = com.cybersiren.android.model.BitchatMessage(
                    sender = "system",
                    content = "${peerInfo.nickname} $action you$guidance",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                delegate?.onMessageReceived(sys)
            }
        } catch (_: Exception) {

        }
    }
}

interface MessageHandlerDelegate {

    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean
    fun removePeer(peerID: String)
    fun updatePeerNickname(peerID: String, nickname: String)
    fun getPeerNickname(peerID: String): String?
    fun getNetworkSize(): Int
    fun getMyNickname(): String?
    fun getPeerInfo(peerID: String): PeerInfo?
    fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean): Boolean

    fun sendPacket(packet: BitchatPacket)
    fun relayPacket(routed: RoutedPacket)
    fun getBroadcastRecipient(): ByteArray

    fun verifySignature(packet: BitchatPacket, peerID: String): Boolean
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray?
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray?
    fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean

    fun hasNoiseSession(peerID: String): Boolean
    fun initiateNoiseHandshake(peerID: String)
    fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray?
    fun updatePeerIDBinding(newPeerID: String, nickname: String,
                           publicKey: ByteArray, previousPeerID: String?)

    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?

    fun onMessageReceived(message: BitchatMessage)
    fun onChannelLeave(channel: String, fromPeer: String)
    fun onDeliveryAckReceived(messageID: String, peerID: String)
    fun onReadReceiptReceived(messageID: String, peerID: String)
    fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long)
    fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long)

    fun onEmergencyAlertReceived(packet: BitchatPacket, fromPeerID: String)
}
