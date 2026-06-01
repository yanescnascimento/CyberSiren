package com.cybersiren.android.ui

import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.model.DeliveryStatus
import java.util.*
import java.util.Collections

class MessageManager(private val state: ChatState) {

    private val processedUIMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val recentSystemEvents = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val MESSAGE_DEDUP_TIMEOUT = com.cybersiren.android.util.AppConstants.UI.MESSAGE_DEDUP_TIMEOUT_MS
    private val SYSTEM_EVENT_DEDUP_TIMEOUT = com.cybersiren.android.util.AppConstants.UI.SYSTEM_EVENT_DEDUP_TIMEOUT_MS

    fun addMessage(message: BitchatMessage) {
        val currentMessages = state.getMessagesValue().toMutableList()
        currentMessages.add(message)
        state.setMessages(currentMessages)

        try { com.cybersiren.android.services.AppStateStore.addPublicMessage(message) } catch (_: Exception) { }
    }

    fun addSystemMessage(text: String) {
        val sys = BitchatMessage(
            sender = "system",
            content = text,
            timestamp = Date(),
            isRelay = false
        )
        addMessage(sys)
    }

    fun clearMessages() {
        state.setMessages(emptyList())
        state.setChannelMessages(emptyMap())
    }

    fun addChannelMessage(channel: String, message: BitchatMessage) {
        val currentChannelMessages = state.getChannelMessagesValue().toMutableMap()
        if (!currentChannelMessages.containsKey(channel)) {
            currentChannelMessages[channel] = mutableListOf()
        }

        val channelMessageList = currentChannelMessages[channel]?.toMutableList() ?: mutableListOf()
        channelMessageList.add(message)
        currentChannelMessages[channel] = channelMessageList
        state.setChannelMessages(currentChannelMessages)

        try { com.cybersiren.android.services.AppStateStore.addChannelMessage(channel, message) } catch (_: Exception) { }

        val viewingClassicChannel = state.getCurrentChannelValue() == channel
        val viewingGeohashChannel = try {
            if (channel.startsWith("geo:")) {
                val geo = channel.removePrefix("geo:")
                val selected = state.selectedLocationChannel.value
                selected is com.cybersiren.android.geohash.ChannelID.Location && selected.channel.geohash.equals(geo, ignoreCase = true)
            } else false
        } catch (_: Exception) { false }

        if (!viewingClassicChannel && !viewingGeohashChannel) {
            val currentUnread = state.getUnreadChannelMessagesValue().toMutableMap()
            currentUnread[channel] = (currentUnread[channel] ?: 0) + 1
            state.setUnreadChannelMessages(currentUnread)
        }
    }

    fun clearChannelMessages(channel: String) {
        val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
        updatedChannelMessages[channel] = emptyList()
        state.setChannelMessages(updatedChannelMessages)
    }

    fun removeChannelMessages(channel: String) {
        val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
        updatedChannelMessages.remove(channel)
        state.setChannelMessages(updatedChannelMessages)

        val updatedUnread = state.getUnreadChannelMessagesValue().toMutableMap()
        updatedUnread.remove(channel)
        state.setUnreadChannelMessages(updatedUnread)
    }

    fun clearChannelUnreadCount(channel: String) {
        val currentUnread = state.getUnreadChannelMessagesValue().toMutableMap()
        currentUnread.remove(channel)
        state.setUnreadChannelMessages(currentUnread)
    }

    fun addPrivateMessage(peerID: String, message: BitchatMessage) {
        val currentPrivateChats = state.getPrivateChatsValue().toMutableMap()
        if (!currentPrivateChats.containsKey(peerID)) {
            currentPrivateChats[peerID] = mutableListOf()
        }

        val chatMessages = currentPrivateChats[peerID]?.toMutableList() ?: mutableListOf()
        chatMessages.add(message)
        currentPrivateChats[peerID] = chatMessages
        state.setPrivateChats(currentPrivateChats)

        try { com.cybersiren.android.services.AppStateStore.addPrivateMessage(peerID, message) } catch (_: Exception) { }

        if (state.getSelectedPrivateChatPeerValue() != peerID && message.sender != state.getNicknameValue()) {
            val currentUnread = state.getUnreadPrivateMessagesValue().toMutableSet()
            currentUnread.add(peerID)
            state.setUnreadPrivateMessages(currentUnread)
        }
    }

    fun addPrivateMessageNoUnread(peerID: String, message: BitchatMessage) {
        val currentPrivateChats = state.getPrivateChatsValue().toMutableMap()
        if (!currentPrivateChats.containsKey(peerID)) {
            currentPrivateChats[peerID] = mutableListOf()
        }
        val chatMessages = currentPrivateChats[peerID]?.toMutableList() ?: mutableListOf()
        chatMessages.add(message)
        currentPrivateChats[peerID] = chatMessages
        state.setPrivateChats(currentPrivateChats)

        try { com.cybersiren.android.services.AppStateStore.addPrivateMessage(peerID, message) } catch (_: Exception) { }
    }

    fun clearPrivateMessages(peerID: String) {
        val updatedChats = state.getPrivateChatsValue().toMutableMap()
        updatedChats[peerID] = emptyList()
        state.setPrivateChats(updatedChats)
    }

    fun initializePrivateChat(peerID: String) {
        if (state.getPrivateChatsValue().containsKey(peerID)) return

        val updatedChats = state.getPrivateChatsValue().toMutableMap()
        updatedChats[peerID] = emptyList()
        state.setPrivateChats(updatedChats)
    }

    fun clearPrivateUnreadMessages(peerID: String) {
        val updatedUnread = state.getUnreadPrivateMessagesValue().toMutableSet()
        updatedUnread.remove(peerID)
        state.setUnreadPrivateMessages(updatedUnread)
    }

    fun generateMessageKey(message: BitchatMessage): String {
        val senderKey = message.senderPeerID ?: message.sender
        val contentHash = message.content.hashCode()
        return "$senderKey-${message.timestamp.time}-$contentHash"
    }

    fun isMessageProcessed(messageKey: String): Boolean {
        return processedUIMessages.contains(messageKey)
    }

    fun markMessageProcessed(messageKey: String) {
        processedUIMessages.add(messageKey)
    }

    fun isDuplicateSystemEvent(eventType: String, peerID: String): Boolean {
        val now = System.currentTimeMillis()
        val eventKey = "$eventType-$peerID"
        val lastEvent = recentSystemEvents[eventKey]

        if (lastEvent != null && (now - lastEvent) < SYSTEM_EVENT_DEDUP_TIMEOUT) {
            return true
        }

        recentSystemEvents[eventKey] = now
        return false
    }

    fun cleanupDeduplicationCaches() {
        val now = System.currentTimeMillis()

        if (processedUIMessages.size > 1000) {
            processedUIMessages.clear()
        }

        recentSystemEvents.entries.removeAll { (_, timestamp) ->
            (now - timestamp) > SYSTEM_EVENT_DEDUP_TIMEOUT * 2
        }
    }

    private fun statusPriority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Sending -> 1
        is DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
        is DeliveryStatus.Failed -> 0
    }

    private fun chooseStatus(old: DeliveryStatus?, new: DeliveryStatus): DeliveryStatus? {

        return if (statusPriority(new) >= statusPriority(old)) new else old
    }

    fun updateMessageDeliveryStatus(messageID: String, status: DeliveryStatus) {

        val updatedPrivateChats = state.getPrivateChatsValue().toMutableMap()
        var updated = false

        updatedPrivateChats.forEach { (peerID, messages) ->
            val updatedMessages = messages.toMutableList()
            val messageIndex = updatedMessages.indexOfFirst { it.id == messageID }
            if (messageIndex >= 0) {
                val current = updatedMessages[messageIndex].deliveryStatus
                val finalStatus = chooseStatus(current, status)
                if (finalStatus !== current) {
                    updatedMessages[messageIndex] = updatedMessages[messageIndex].copy(deliveryStatus = finalStatus)
                    updatedPrivateChats[peerID] = updatedMessages
                    updated = true
                }
            }
        }

        if (updated) {
            state.setPrivateChats(updatedPrivateChats)

            try { com.cybersiren.android.services.AppStateStore.updatePrivateMessageStatus(messageID, status) } catch (_: Exception) { }
        }

        val updatedMessages = state.getMessagesValue().toMutableList()
        val messageIndex = updatedMessages.indexOfFirst { it.id == messageID }
        if (messageIndex >= 0) {
            val current = updatedMessages[messageIndex].deliveryStatus
            val finalStatus = chooseStatus(current, status)
            if (finalStatus !== current) {
                updatedMessages[messageIndex] = updatedMessages[messageIndex].copy(deliveryStatus = finalStatus)
                state.setMessages(updatedMessages)
            }
        }

        val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
        updatedChannelMessages.forEach { (channel, messages) ->
            val channelMessagesList = messages.toMutableList()
            val channelMessageIndex = channelMessagesList.indexOfFirst { it.id == messageID }
            if (channelMessageIndex >= 0) {
                val current = channelMessagesList[channelMessageIndex].deliveryStatus
                val finalStatus = chooseStatus(current, status)
                if (finalStatus !== current) {
                    channelMessagesList[channelMessageIndex] = channelMessagesList[channelMessageIndex].copy(deliveryStatus = finalStatus)
                    updatedChannelMessages[channel] = channelMessagesList
                }
            }
        }
        state.setChannelMessages(updatedChannelMessages)
    }

    fun removeMessageById(messageID: String) {

        run {
            val list = state.getMessagesValue().toMutableList()
            val idx = list.indexOfFirst { it.id == messageID }
            if (idx >= 0) {
                list.removeAt(idx)
                state.setMessages(list)
            }
        }

        run {
            val chats = state.getPrivateChatsValue().toMutableMap()
            var changed = false
            chats.keys.toList().forEach { key ->
                val msgs = chats[key]?.toMutableList() ?: mutableListOf()
                val idx = msgs.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    msgs.removeAt(idx)
                    chats[key] = msgs
                    changed = true
                }
            }
            if (changed) state.setPrivateChats(chats)
        }

        run {
            val chans = state.getChannelMessagesValue().toMutableMap()
            var changed = false
            chans.keys.toList().forEach { ch ->
                val msgs = chans[ch]?.toMutableList() ?: mutableListOf()
                val idx = msgs.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    msgs.removeAt(idx)
                    chans[ch] = msgs
                    changed = true
                }
            }
            if (changed) state.setChannelMessages(chans)
        }
    }

    fun parseMentions(content: String, peerNicknames: Set<String>, currentNickname: String?): List<String> {
        val mentionRegex = "@([a-zA-Z0-9_]+)".toRegex()
        val allNicknames = peerNicknames + (currentNickname ?: "")

        return mentionRegex.findAll(content)
            .map { it.groupValues[1] }
            .filter { allNicknames.contains(it) }
            .distinct()
            .toList()
    }

    fun parseChannels(content: String): List<String> {
        val channelRegex = "#([a-zA-Z0-9_]+)".toRegex()
        return channelRegex.findAll(content)
            .map { it.groupValues[0] }
            .distinct()
            .toList()
    }

    fun clearAllMessages() {
        state.setMessages(emptyList())
        state.setPrivateChats(emptyMap())
        state.setChannelMessages(emptyMap())
        state.setUnreadPrivateMessages(emptySet())
        state.setUnreadChannelMessages(emptyMap())
        processedUIMessages.clear()
        recentSystemEvents.clear()
    }
}
