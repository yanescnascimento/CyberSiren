package com.cybersiren.android.nostr

import android.app.Application
import android.util.Log
import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.ui.ChatState
import com.cybersiren.android.ui.MessageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class GeohashMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val repo: GeohashRepository,
    private val scope: CoroutineScope,
    private val dataManager: com.cybersiren.android.ui.DataManager
) {
    companion object { private const val TAG = "GeohashMessageHandler" }

    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    private fun dedupe(id: String): Boolean {
        if (seen.contains(id)) return true
        seen.add(id)
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
        return false
    }

    fun onEvent(event: NostrEvent, subscribedGeohash: String) {
        scope.launch(Dispatchers.Default) {
            try {
                if (event.kind != NostrKind.EPHEMERAL_EVENT && event.kind != NostrKind.GEOHASH_PRESENCE) return@launch
                val tagGeo = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
                if (tagGeo == null || !tagGeo.equals(subscribedGeohash, true)) return@launch
                if (dedupe(event.id)) return@launch

                if (event.kind == NostrKind.EPHEMERAL_EVENT) {
                    val pow = PoWPreferenceManager.getCurrentSettings()
                    if (pow.enabled && pow.difficulty > 0) {
                        if (!NostrProofOfWork.validateDifficulty(event, pow.difficulty)) return@launch
                    }
                }

                if (dataManager.isGeohashUserBlocked(event.pubkey)) return@launch

                if (event.kind == NostrKind.GEOHASH_PRESENCE || event.kind == NostrKind.EPHEMERAL_EVENT) {
                    repo.updateParticipant(subscribedGeohash, event.pubkey, Date(event.createdAt * 1000L))
                }

                event.tags.find { it.size >= 2 && it[0] == "n" }?.let { repo.cacheNickname(event.pubkey, it[1]) }
                event.tags.find { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }?.let { repo.markTeleported(event.pubkey) }

                try {
                    com.cybersiren.android.nostr.GeohashAliasRegistry.put("nostr_${event.pubkey.take(16)}", event.pubkey)
                } catch (_: Exception) { }

                if (event.kind == NostrKind.GEOHASH_PRESENCE) return@launch

                val my = NostrIdentityBridge.deriveIdentity(subscribedGeohash, application)
                if (my.publicKeyHex.equals(event.pubkey, true)) return@launch

                val isTeleportPresence = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" } &&
                                         event.content.trim().isEmpty()
                if (isTeleportPresence) return@launch

                val senderName = repo.displayNameForNostrPubkeyUI(event.pubkey)
                val hasNonce = try { NostrProofOfWork.hasNonce(event) } catch (_: Exception) { false }
                val msg = BitchatMessage(
                    id = event.id,
                    sender = senderName,
                    content = event.content,
                    timestamp = Date(event.createdAt * 1000L),
                    isRelay = false,
                    originalSender = repo.displayNameForNostrPubkey(event.pubkey),
                    senderPeerID = "nostr:${event.pubkey.take(8)}",
                    mentions = null,
                    channel = "#$subscribedGeohash",
                    powDifficulty = try {
                        if (hasNonce) NostrProofOfWork.calculateDifficulty(event.id).takeIf { it > 0 } else null
                    } catch (_: Exception) { null }
                )
                withContext(Dispatchers.Main) { messageManager.addChannelMessage("geo:$subscribedGeohash", msg) }
            } catch (e: Exception) {
                Log.e(TAG, "onEvent error: ${e.message}")
            }
        }
    }
}
