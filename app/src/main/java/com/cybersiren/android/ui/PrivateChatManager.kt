package com.cybersiren.android.ui

import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.model.DeliveryStatus
import com.cybersiren.android.mesh.PeerFingerprintManager
import java.security.MessageDigest

import com.cybersiren.android.mesh.BluetoothMeshService
import java.util.*
import android.util.Log

interface NoiseSessionDelegate {
    fun hasEstablishedSession(peerID: String): Boolean
    fun initiateHandshake(peerID: String)
    fun getMyPeerID(): String
}

class PrivateChatManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val noiseSessionDelegate: NoiseSessionDelegate
) {

    companion object {
        private const val TAG = "PrivateChatManager"
    }

    private val fingerprintManager = PeerFingerprintManager.getInstance()

    private val unreadReceivedMessages = mutableMapOf<String, MutableList<BitchatMessage>>()

    fun startPrivateChat(peerID: String, meshService: BluetoothMeshService): Boolean {
        if (isPeerBlocked(peerID)) {
            val peerNickname = getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot start chat with $peerNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }

        establishNoiseSessionIfNeeded(peerID, meshService)

        try {
            consolidateNostrTempConversationIfNeeded(peerID)
        } catch (_: Exception) { }

        state.setSelectedPrivateChatPeer(peerID)

        messageManager.clearPrivateUnreadMessages(peerID)

        messageManager.initializePrivateChat(peerID)

        sendReadReceiptsForPeer(peerID, meshService)

        return true
    }

    fun endPrivateChat() {
        state.setSelectedPrivateChatPeer(null)
    }

    fun sendPrivateMessage(
        content: String,
        peerID: String,
        recipientNickname: String?,
        senderNickname: String?,
        myPeerID: String,
        onSendMessage: (String, String, String, String) -> Unit
    ): Boolean {
        if (isPeerBlocked(peerID)) {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot send message to $recipientNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }

        val message = BitchatMessage(
            sender = senderNickname ?: myPeerID,
            content = content,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = myPeerID,
            deliveryStatus = DeliveryStatus.Sending
        )

        messageManager.addPrivateMessage(peerID, message)
        onSendMessage(content, peerID, recipientNickname ?: "", message.id)

        return true
    }

    fun isPeerBlocked(peerID: String): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
        return fingerprint != null && dataManager.isUserBlocked(fingerprint)
    }

    fun toggleFavorite(peerID: String) {
        var fingerprint = fingerprintManager.getFingerprintForPeer(peerID)

        if (fingerprint == null && peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
            try {
                val pubBytes = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val fpBytes = digest.digest(pubBytes)
                fingerprint = fpBytes.joinToString("") { "%02x".format(it) }
                Log.d(TAG, "Computed fingerprint from noise key hex for offline toggle: $fingerprint")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute fingerprint from noise key hex: ${e.message}")
            }
        }

        if (fingerprint == null) {
            Log.w(TAG, "toggleFavorite: no fingerprint for peerID=$peerID; ignoring toggle")
            return
        }

        Log.d(TAG, "toggleFavorite called for peerID: $peerID, fingerprint: $fingerprint")

        val wasFavorite = dataManager.isFavorite(fingerprint!!)
        Log.d(TAG, "Current favorite status: $wasFavorite")

        val currentFavorites = state.getFavoritePeersValue()
        Log.d(TAG, "Current UI state favorites: $currentFavorites")

        if (wasFavorite) {
            dataManager.removeFavorite(fingerprint!!)
            Log.d(TAG, "Removed from favorites: $fingerprint")
        } else {
            dataManager.addFavorite(fingerprint!!)
            Log.d(TAG, "Added to favorites: $fingerprint")
        }

        val newFavorites = dataManager.favoritePeers.toSet()
        state.setFavoritePeers(newFavorites)

        Log.d(TAG, "Force updated favorite peers state. New favorites: $newFavorites")
        Log.d(TAG, "All peer fingerprints: ${fingerprintManager.getAllPeerFingerprints()}")
    }

    fun isFavorite(peerID: String): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID) ?: return false
        val isFav = dataManager.isFavorite(fingerprint)
        Log.d(TAG, "isFavorite check: peerID=$peerID, fingerprint=$fingerprint, result=$isFav")
        return isFav
    }

    fun getPeerFingerprint(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }

    fun getPeerFingerprints(): Map<String, String> {
        return fingerprintManager.getAllPeerFingerprints()
    }

    fun blockPeer(peerID: String, meshService: BluetoothMeshService): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
        if (fingerprint != null) {
            dataManager.addBlockedUser(fingerprint)

            val peerNickname = getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "blocked user $peerNickname",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)

            if (state.getSelectedPrivateChatPeerValue() == peerID) {
                endPrivateChat()
            }

            return true
        }
        return false
    }

    fun unblockPeer(peerID: String, meshService: BluetoothMeshService): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
        if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
            dataManager.removeBlockedUser(fingerprint)

            val peerNickname = getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "unblocked user $peerNickname",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return true
        }
        return false
    }

    fun blockPeerByNickname(targetName: String, meshService: BluetoothMeshService): Boolean {
        val peerID = getPeerIDForNickname(targetName, meshService)

        if (peerID != null) {
            return blockPeer(peerID, meshService)
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "user '$targetName' not found",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
    }

    fun unblockPeerByNickname(targetName: String, meshService: BluetoothMeshService): Boolean {
        val peerID = getPeerIDForNickname(targetName, meshService)

        if (peerID != null) {
            val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
            if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
                return unblockPeer(peerID, meshService)
            } else {
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = "user '$targetName' is not blocked",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
                return false
            }
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "user '$targetName' not found",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
    }

    fun listBlockedUsers(): String {
        val blockedCount = dataManager.blockedUsers.size
        return if (blockedCount == 0) {
            "no blocked users"
        } else {
            "blocked users: $blockedCount fingerprints"
        }
    }

    fun handleIncomingPrivateMessage(message: BitchatMessage) {
        handleIncomingPrivateMessage(message, suppressUnread = false)
    }

    fun handleIncomingPrivateMessage(message: BitchatMessage, suppressUnread: Boolean) {
        val senderPeerID = message.senderPeerID
        if (senderPeerID != null) {

            if (!isPeerBlocked(senderPeerID)) {

                messageManager.initializePrivateChat(senderPeerID)

                if (senderPeerID.startsWith("nostr_")) {
                    if (suppressUnread) {
                        messageManager.addPrivateMessageNoUnread(senderPeerID, message)
                    } else {
                        messageManager.addPrivateMessage(senderPeerID, message)
                    }
                }

                if (!suppressUnread && state.getSelectedPrivateChatPeerValue() != senderPeerID) {
                    val unreadList = unreadReceivedMessages.getOrPut(senderPeerID) { mutableListOf() }
                    unreadList.add(message)
                    Log.d(TAG, "Queued unread from $senderPeerID (count=${unreadList.size})")
                    val currentUnread = state.getUnreadPrivateMessagesValue().toMutableSet()
                    currentUnread.add(senderPeerID)
                    state.setUnreadPrivateMessages(currentUnread)
                }
            }
            return
        }

        val inferredPeer = state.getSelectedPrivateChatPeerValue() ?: return
        if (suppressUnread) {
            messageManager.addPrivateMessageNoUnread(inferredPeer, message)
        } else {
            messageManager.addPrivateMessage(inferredPeer, message)
        }
    }

    fun sendReadReceiptsForPeer(peerID: String, meshService: BluetoothMeshService) {

        val chats = try { state.getPrivateChatsValue() } catch (_: Exception) { emptyMap<String, List<BitchatMessage>>() }
        val messages = chats[peerID].orEmpty()

        if (messages.isEmpty()) {
            Log.d(TAG, "No messages found for peer $peerID to send read receipts")
        }

        val myNickname = state.getNicknameValue() ?: "unknown"
        var sentCount = 0
        messages.forEach { msg ->

            if (msg.senderPeerID == peerID) {
                try {
                    meshService.sendReadReceipt(msg.id, peerID, myNickname)
                    sentCount += 1
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send read receipt for message ${msg.id}: ${e.message}")
                }
            }
        }

        unreadReceivedMessages.remove(peerID)

        try { messageManager.clearPrivateUnreadMessages(peerID) } catch (_: Exception) { }
        Log.d(TAG, "Sent $sentCount read receipts for peer $peerID (from conversation messages)")
    }

    fun cleanupDisconnectedPeer(peerID: String) {

        if (state.getSelectedPrivateChatPeerValue() == peerID) {
            endPrivateChat()
        }

        unreadReceivedMessages.remove(peerID)
        Log.d(TAG, "Cleaned up unread messages for disconnected peer $peerID")
    }

    private fun establishNoiseSessionIfNeeded(peerID: String, meshService: BluetoothMeshService) {
        if (noiseSessionDelegate.hasEstablishedSession(peerID)) {
            Log.d(TAG, "Noise session already established with $peerID")
            return
        }

        Log.d(TAG, "No Noise session with $peerID, determining who should initiate handshake")

        val myPeerID = noiseSessionDelegate.getMyPeerID()

        if (myPeerID < peerID) {

            Log.d(
                TAG,
                "Our peer ID lexicographically < target peer ID, initiating Noise handshake with $peerID"
            )
            noiseSessionDelegate.initiateHandshake(peerID)
        } else {

            Log.d(
                TAG,
                "Our peer ID lexicographically >= target peer ID, sending identity announcement to prompt handshake from $peerID"
            )
            meshService.sendAnnouncementToPeer(peerID)
            Log.d(TAG, "Sent identity announcement to $peerID – starting handshake now from our side")
            noiseSessionDelegate.initiateHandshake(peerID)
        }

    }

    private fun getPeerIDForNickname(nickname: String, meshService: BluetoothMeshService): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }

    private fun getPeerNickname(peerID: String, meshService: BluetoothMeshService): String {
        return meshService.getPeerNicknames()[peerID] ?: peerID
    }

    private fun consolidateNostrTempConversationIfNeeded(targetPeerID: String) {

        if (targetPeerID.startsWith("nostr_")) return

        val tryMergeKeys = mutableListOf<String>()

        try {
            val noiseKeyBytes = targetPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val npub = com.cybersiren.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(noiseKeyBytes)
            if (npub != null) {

                val (hrp, data) = com.cybersiren.android.nostr.Bech32.decode(npub)
                if (hrp == "npub") {
                    val pubHex = data.joinToString("") { "%02x".format(it) }
                    tryMergeKeys.add("nostr_${pubHex.take(16)}")
                }
            }
        } catch (_: Exception) { }

        state.getPrivateChatsValue().keys.filter { it.startsWith("nostr_") }.forEach { tempKey ->
            if (!tryMergeKeys.contains(tempKey)) tryMergeKeys.add(tempKey)
        }

        if (tryMergeKeys.isEmpty()) return

        val currentChats = state.getPrivateChatsValue().toMutableMap()
        val targetList = currentChats[targetPeerID]?.toMutableList() ?: mutableListOf()

        var didMerge = false
        tryMergeKeys.forEach { tempKey ->
            val tempList = currentChats[tempKey]
            if (!tempList.isNullOrEmpty()) {
                targetList.addAll(tempList)
                currentChats.remove(tempKey)
                didMerge = true
            }
        }

        if (didMerge) {
            currentChats[targetPeerID] = targetList
            state.setPrivateChats(currentChats)

            val unread = state.getUnreadPrivateMessagesValue().toMutableSet()
            val hadUnread = tryMergeKeys.any { unread.remove(it) }
            if (hadUnread) {
                unread.add(targetPeerID)
                state.setUnreadPrivateMessages(unread)
            }

            val sheetPeer = state.getPrivateChatSheetPeerValue()
            if (sheetPeer != null && tryMergeKeys.contains(sheetPeer)) {
                state.setPrivateChatSheetPeer(targetPeerID)
            }
        }
    }

    fun clearAllPrivateChats() {
        state.setSelectedPrivateChatPeer(null)
        state.setUnreadPrivateMessages(emptySet())

        unreadReceivedMessages.clear()

    }

    fun getAllPeerFingerprints(): Map<String, String> {
        return fingerprintManager.getAllPeerFingerprints()
    }
}
