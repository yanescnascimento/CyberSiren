package com.cybersiren.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.cybersiren.android.model.BitchatMessage
import java.util.*

class ChannelManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val coroutineScope: CoroutineScope
) {

    private val channelKeys = mutableMapOf<String, SecretKeySpec>()
    private val channelPasswords = mutableMapOf<String, String>()
    private val channelKeyCommitments = mutableMapOf<String, String>()
    private val retentionEnabledChannels = mutableSetOf<String>()

    fun joinChannel(channel: String, password: String? = null, myPeerID: String): Boolean {
        val channelTag = if (channel.startsWith("#")) channel else "#$channel"

        if (state.getJoinedChannelsValue().contains(channelTag)) {
            if (state.getPasswordProtectedChannelsValue().contains(channelTag) && !channelKeys.containsKey(channelTag)) {

                if (password != null) {
                    return verifyChannelPassword(channelTag, password)
                } else {
                    state.setPasswordPromptChannel(channelTag)
                    state.setShowPasswordPrompt(true)
                    return false
                }
            }
            switchToChannel(channelTag)
            return true
        }

        if (state.getPasswordProtectedChannelsValue().contains(channelTag) && !channelKeys.containsKey(channelTag)) {
            if (dataManager.isChannelCreator(channelTag, myPeerID)) {

            } else if (password != null) {
                if (!verifyChannelPassword(channelTag, password)) {
                    return false
                }
            } else {
                state.setPasswordPromptChannel(channelTag)
                state.setShowPasswordPrompt(true)
                return false
            }
        }

        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.add(channelTag)
        state.setJoinedChannels(updatedChannels)

        if (!dataManager.channelCreators.containsKey(channelTag) && !state.getPasswordProtectedChannelsValue().contains(channelTag)) {
            dataManager.addChannelCreator(channelTag, myPeerID)
        }

        dataManager.addChannelMember(channelTag, myPeerID)

        if (!state.getChannelMessagesValue().containsKey(channelTag)) {
            val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
            updatedChannelMessages[channelTag] = emptyList()
            state.setChannelMessages(updatedChannelMessages)
        }

        switchToChannel(channelTag)
        saveChannelData()
        return true
    }

    fun leaveChannel(channel: String) {
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.remove(channel)
        state.setJoinedChannels(updatedChannels)

        if (state.getCurrentChannelValue() == channel) {
            state.setCurrentChannel(null)
        }

        messageManager.removeChannelMessages(channel)
        dataManager.removeChannelMembers(channel)
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        dataManager.removeChannelCreator(channel)

        saveChannelData()
    }

    fun switchToChannel(channel: String?) {
        state.setCurrentChannel(channel)
        state.setSelectedPrivateChatPeer(null)

        channel?.let { ch ->
            messageManager.clearChannelUnreadCount(ch)
        }
    }

    private fun verifyChannelPassword(channel: String, password: String): Boolean {

        return true
    }

    private fun deriveChannelKey(password: String, channelName: String): SecretKeySpec {

        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            channelName.toByteArray(),
            100000,
            256
        )
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }

    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return decryptChannelMessage(encryptedContent, channel, null)
    }

    private fun decryptChannelMessage(encryptedContent: ByteArray, channel: String, testKey: SecretKeySpec?): String? {
        val key = testKey ?: channelKeys[channel] ?: return null

        try {
            if (encryptedContent.size < 16) return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = encryptedContent.sliceArray(0..11)
            val ciphertext = encryptedContent.sliceArray(12 until encryptedContent.size)

            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            val decryptedData = cipher.doFinal(ciphertext)
            return String(decryptedData, Charsets.UTF_8)

        } catch (e: Exception) {
            return null
        }
    }

    fun sendEncryptedChannelMessage(
        content: String,
        mentions: List<String>,
        channel: String,
        senderNickname: String?,
        myPeerID: String,
        onEncryptedPayload: (ByteArray) -> Unit,
        onFallback: () -> Unit
    ) {

        return
    }

    fun addChannelMessage(channel: String, message: BitchatMessage, senderPeerID: String?) {
        messageManager.addChannelMessage(channel, message)

        senderPeerID?.let { peerID ->
            dataManager.addChannelMember(channel, peerID)
        }
    }

    fun removeChannelMember(channel: String, peerID: String) {
        dataManager.removeChannelMember(channel, peerID)
    }

    fun cleanupDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        dataManager.cleanupAllDisconnectedMembers(connectedPeers, myPeerID)
    }

    fun isChannelPasswordProtected(channel: String): Boolean {
        return state.getPasswordProtectedChannelsValue().contains(channel)
    }

    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }

    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }

    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return dataManager.isChannelCreator(channel, peerID)
    }

    fun getJoinedChannelsList(): List<String> {
        return state.getJoinedChannelsValue().toList().sorted()
    }

    private fun saveChannelData() {
        dataManager.saveChannelData(state.getJoinedChannelsValue(), state.getPasswordProtectedChannelsValue())
    }

    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        return dataManager.loadChannelData()
    }

    fun hidePasswordPrompt() {
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)
    }

    fun setChannelPassword(channel: String, password: String) {

        channelPasswords[channel] = password

        channelKeys[channel] = deriveChannelKey(password, channel)

        state.setPasswordProtectedChannels(
            state.getPasswordProtectedChannelsValue().toMutableSet().apply { add(channel) }
        )

        dataManager.saveChannelData(
            state.getJoinedChannelsValue(),
            state.getPasswordProtectedChannelsValue()
        )
    }

    fun clearAllChannels() {
        state.setJoinedChannels(emptySet())
        state.setCurrentChannel(null)
        state.setPasswordProtectedChannels(emptySet())
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)

        channelKeys.clear()
        channelPasswords.clear()
        channelKeyCommitments.clear()
        retentionEnabledChannels.clear()
    }
}
