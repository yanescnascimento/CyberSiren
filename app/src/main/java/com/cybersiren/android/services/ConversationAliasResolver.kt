package com.cybersiren.android.services

import com.cybersiren.android.ui.ChatState

object ConversationAliasResolver {

    fun resolveCanonicalPeerID(
        selectedPeerID: String,
        connectedPeers: List<String>,
        meshNoiseKeyForPeer: (String) -> ByteArray?,
        meshHasPeer: (String) -> Boolean,
        nostrPubHexForAlias: (String) -> String?,
        findNoiseKeyForNostr: (String) -> ByteArray?
    ): String {
        var peer = selectedPeerID
        try {
            if (peer.startsWith("nostr_")) {
                val pubHex = nostrPubHexForAlias(peer)
                if (pubHex != null) {
                    val noiseKey = findNoiseKeyForNostr(pubHex)
                    if (noiseKey != null) {
                        val noiseHex = noiseKey.joinToString("") { b -> "%02x".format(b) }

                        val meshPeer = connectedPeers.firstOrNull { pid ->
                            meshNoiseKeyForPeer(pid)?.contentEquals(noiseKey) == true
                        }
                        peer = meshPeer ?: noiseHex
                    }
                }
            } else if (peer.length == 64 && peer.matches(Regex("^[0-9a-fA-F]+$"))) {

                val noiseKey = peer.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val meshPeer = connectedPeers.firstOrNull { pid ->
                    meshNoiseKeyForPeer(pid)?.contentEquals(noiseKey) == true
                }
                if (meshPeer != null) {
                    peer = meshPeer
                }
            }
        } catch (_: Exception) {  }
        return peer
    }

    fun unifyChatsIntoPeer(
        state: ChatState,
        targetPeerID: String,
        keysToMerge: List<String>
    ) {
        if (keysToMerge.isEmpty()) return
        val currentChats = state.getPrivateChatsValue().toMutableMap()
        val targetList = currentChats[targetPeerID]?.toMutableList() ?: mutableListOf()
        var didMerge = false
        keysToMerge.distinct().forEach { key ->
            if (key == targetPeerID) return@forEach
            val list = currentChats[key]
            if (!list.isNullOrEmpty()) {
                targetList.addAll(list)
                currentChats.remove(key)
                didMerge = true
            }
        }
        if (didMerge) {

            currentChats[targetPeerID] = targetList
            state.setPrivateChats(currentChats)

            val unread = state.getUnreadPrivateMessagesValue().toMutableSet()
            var hadUnread = false
            keysToMerge.forEach { key -> if (unread.remove(key)) hadUnread = true }
            if (hadUnread) unread.add(targetPeerID)
            state.setUnreadPrivateMessages(unread)

            val selected = state.getSelectedPrivateChatPeerValue()
            if (selected != null && keysToMerge.contains(selected)) {
                state.setSelectedPrivateChatPeer(targetPeerID)
            }

            val sheetPeer = state.getPrivateChatSheetPeerValue()
            if (sheetPeer != null && keysToMerge.contains(sheetPeer)) {
                state.setPrivateChatSheetPeer(targetPeerID)
            }
        }
    }
}
