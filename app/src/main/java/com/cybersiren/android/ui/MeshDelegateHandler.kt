package com.cybersiren.android.ui

import com.cybersiren.android.mesh.BluetoothMeshDelegate
import com.cybersiren.android.ui.NotificationTextUtils
import com.cybersiren.android.mesh.BluetoothMeshService
import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date

class MeshDelegateHandler(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val coroutineScope: CoroutineScope,
    private val onHapticFeedback: () -> Unit,
    private val getMyPeerID: () -> String,
    private val getMeshService: () -> BluetoothMeshService
) : BluetoothMeshDelegate {

    override fun didReceiveMessage(message: BitchatMessage) {
        coroutineScope.launch {

            val messageKey = messageManager.generateMessageKey(message)
            if (messageManager.isMessageProcessed(messageKey)) {
                return@launch
            }
            messageManager.markMessageProcessed(messageKey)

            message.senderPeerID?.let { senderPeerID ->
                if (privateChatManager.isPeerBlocked(senderPeerID)) {
                    return@launch
                }
            }

            onHapticFeedback()

            if (message.isPrivate) {

                privateChatManager.handleIncomingPrivateMessage(message)

                message.senderPeerID?.let { senderPeerID ->
                    sendReadReceiptIfFocused(message)
                }

                message.senderPeerID?.let { senderPeerID ->

                    val senderNickname = message.sender.takeIf { it != senderPeerID } ?: senderPeerID
                    val preview = NotificationTextUtils.buildPrivateMessagePreview(message)
                    notificationManager.showPrivateMessageNotification(
                        senderPeerID = senderPeerID,
                        senderNickname = senderNickname,
                        messageContent = preview
                    )
                }
            } else if (message.channel != null) {

                if (state.getJoinedChannelsValue().contains(message.channel)) {
                    val channel = message.channel
                    val viewingClassic = state.getCurrentChannelValue() == channel
                    val viewingGeohash = try {
                        if (channel.startsWith("geo:")) {
                            val geo = channel.removePrefix("geo:")
                            val selected = state.selectedLocationChannel.value
                            selected is com.cybersiren.android.geohash.ChannelID.Location && selected.channel.geohash.equals(geo, ignoreCase = true)
                        } else false
                    } catch (_: Exception) { false }
                    if (!viewingClassic && !viewingGeohash) {
                        val currentUnread = state.getUnreadChannelMessagesValue().toMutableMap()
                        currentUnread[channel] = (currentUnread[channel] ?: 0) + 1
                        state.setUnreadChannelMessages(currentUnread)
                    }
                }
            } else {

                checkAndTriggerMeshMentionNotification(message)
            }

            if (messageManager.isMessageProcessed("cleanup_check_${System.currentTimeMillis()/30000}")) {
                messageManager.cleanupDeduplicationCaches()
            }
        }
    }

    override fun didUpdatePeerList(peers: List<String>) {
        coroutineScope.launch {
            state.setConnectedPeers(peers)
            state.setIsConnected(peers.isNotEmpty())
            notificationManager.showActiveUserNotification(peers)

            runCatching { com.cybersiren.android.services.MessageRouter.tryGetInstance()?.onPeersUpdated(peers) }

            channelManager.cleanupDisconnectedMembers(peers, getMyPeerID())

            state.getSelectedPrivateChatPeerValue()?.let { currentPeer ->
                val isNostrAlias = currentPeer.startsWith("nostr_")
                val isNoiseHex = currentPeer.length == 64 && currentPeer.matches(Regex("^[0-9a-fA-F]+$"))
                val isMeshEphemeral = currentPeer.length == 16 && currentPeer.matches(Regex("^[0-9a-fA-F]+$"))

                if (isNostrAlias || isNoiseHex) {

                    val canonical = com.cybersiren.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                        selectedPeerID = currentPeer,
                        connectedPeers = peers,
                        meshNoiseKeyForPeer = { pid -> getPeerInfo(pid)?.noisePublicKey },
                        meshHasPeer = { pid -> peers.contains(pid) },
                        nostrPubHexForAlias = { alias ->

                            if (com.cybersiren.android.nostr.GeohashAliasRegistry.contains(alias)) {
                                com.cybersiren.android.nostr.GeohashAliasRegistry.get(alias)
                            } else {

                                val prefix = alias.removePrefix("nostr_")
                                val favs = try { com.cybersiren.android.favorites.FavoritesPersistenceService.shared.getOurFavorites() } catch (_: Exception) { emptyList() }
                                favs.firstNotNullOfOrNull { rel ->
                                    rel.peerNostrPublicKey?.let { s ->
                                        runCatching { com.cybersiren.android.nostr.Bech32.decode(s) }.getOrNull()?.let { dec ->
                                            if (dec.first == "npub") dec.second.joinToString("") { b -> "%02x".format(b) } else null
                                        }
                                    }
                                }?.takeIf { it.startsWith(prefix, ignoreCase = true) }
                            }
                        },
                        findNoiseKeyForNostr = { key -> com.cybersiren.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                    )
                    if (canonical != currentPeer) {

                        com.cybersiren.android.services.ConversationAliasResolver.unifyChatsIntoPeer(state, canonical, listOf(currentPeer))
                        state.setSelectedPrivateChatPeer(canonical)
                    }
                } else if (isMeshEphemeral && !peers.contains(currentPeer)) {

                    val favoriteRel = try {
                        val info = getPeerInfo(currentPeer)
                        val noiseKey = info?.noisePublicKey
                        if (noiseKey != null) {
                            com.cybersiren.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                        } else null
                    } catch (_: Exception) { null }

                    if (favoriteRel?.isMutual == true) {
                        val noiseHex = favoriteRel.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
                        if (noiseHex != currentPeer) {
                            com.cybersiren.android.services.ConversationAliasResolver.unifyChatsIntoPeer(
                                state = state,
                                targetPeerID = noiseHex,
                                keysToMerge = listOf(currentPeer)
                            )
                            state.setSelectedPrivateChatPeer(noiseHex)
                        }
                    } else {
                        privateChatManager.cleanupDisconnectedPeer(currentPeer)
                    }
                }
            }

            peers.forEach { pid ->
                try {
                    val info = getPeerInfo(pid)
                    val noiseKey = info?.noisePublicKey ?: return@forEach
                    val noiseHex = noiseKey.joinToString("") { b -> "%02x".format(b) }

                    val npub = com.cybersiren.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(noiseKey)
                    val tempNostrKey: String? = try {
                        if (npub != null) {
                            val (hrp, data) = com.cybersiren.android.nostr.Bech32.decode(npub)
                            if (hrp == "npub") "nostr_${data.joinToString("") { b -> "%02x".format(b) }.take(16)}" else null
                        } else null
                    } catch (_: Exception) { null }

                    unifyChatsIntoPeer(pid, listOfNotNull(noiseHex, tempNostrKey))
                } catch (_: Exception) { }
            }
        }
    }

    private fun unifyChatsIntoPeer(targetPeerID: String, keysToMerge: List<String>) {
        com.cybersiren.android.services.ConversationAliasResolver.unifyChatsIntoPeer(state, targetPeerID, keysToMerge)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        coroutineScope.launch {
            channelManager.removeChannelMember(channel, fromPeer)
        }
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Delivered(recipientPeerID, Date()))
        }
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Read(recipientPeerID, Date()))
        }
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {

    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {

    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return channelManager.decryptChannelMessage(encryptedContent, channel)
    }

    override fun getNickname(): String? = state.getNicknameValue()

    override fun isFavorite(peerID: String): Boolean {
        return privateChatManager.isFavorite(peerID)
    }

    private fun checkAndTriggerMeshMentionNotification(message: BitchatMessage) {
        try {

            val currentNickname = state.getNicknameValue()
            if (currentNickname.isNullOrEmpty()) {
                return
            }

            val isMention = checkForMeshMention(message.content, currentNickname)

            if (isMention) {
                android.util.Log.d("MeshDelegateHandler", "Triggering mesh mention notification from ${message.sender}")

                notificationManager.showMeshMentionNotification(
                    senderNickname = message.sender,
                    messageContent = message.content,
                    senderPeerID = message.senderPeerID
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshDelegateHandler", "Error checking mesh mentions: ${e.message}")
        }
    }

    private fun checkForMeshMention(content: String, currentNickname: String): Boolean {

        val mentionPattern = "@([\\p{L}0-9_]+)".toRegex()

        return mentionPattern.findAll(content).any { match ->
            val mentionedUsername = match.groupValues[1]

            mentionedUsername.equals(currentNickname, ignoreCase = true)
        }
    }

    private fun sendReadReceiptIfFocused(message: BitchatMessage) {

        val isAppInBackground = notificationManager.getAppBackgroundState()
        val currentPrivateChatPeer = notificationManager.getCurrentPrivateChatPeer()

        val senderPeerID = message.senderPeerID
        val shouldSendReadReceipt = !isAppInBackground && senderPeerID != null && currentPrivateChatPeer == senderPeerID

            if (shouldSendReadReceipt) {
                android.util.Log.d("MeshDelegateHandler", "Sending reactive read receipt for focused chat with $senderPeerID (message=${message.id})")
                val nickname = state.getNicknameValue() ?: "unknown"

                getMeshService().sendReadReceipt(message.id, senderPeerID!!, nickname)

                try {
                    val current = state.getUnreadPrivateMessagesValue().toMutableSet()
                    if (current.remove(senderPeerID)) {
                        state.setUnreadPrivateMessages(current)
                    }
                } catch (_: Exception) { }
            } else {
                android.util.Log.d("MeshDelegateHandler", "Skipping read receipt - chat not focused (background: $isAppInBackground, current peer: $currentPrivateChatPeer, sender: $senderPeerID)")
            }
        }

    fun getPeerInfo(peerID: String): com.cybersiren.android.mesh.PeerInfo? {
        return getMeshService().getPeerInfo(peerID)
    }

}
