package com.cybersiren.android.nostr

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NostrProtocol {

    private const val TAG = "NostrProtocol"
    private val gson = Gson()

    fun createPrivateMessage(
        content: String,
        recipientPubkey: String,
        senderIdentity: NostrIdentity
    ): List<NostrEvent> {
        Log.d(TAG, "Creating private message for recipient: ${recipientPubkey.take(16)}...")

        val rumorBase = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", recipientPubkey)),
            content = content
        )
        val rumorId = rumorBase.computeEventIdHex()
        val rumor = rumorBase.copy(id = rumorId)

        val sealedEvent = createSeal(
            rumor = rumor,
            recipientPubkey = recipientPubkey,
            senderPrivateKey = senderIdentity.privateKeyHex,
            senderPublicKey = senderIdentity.publicKeyHex
        )

        val giftWrapToRecipient = createGiftWrap(
            seal = sealedEvent,
            recipientPubkey = recipientPubkey
        )
        Log.d(TAG, "Created gift wrap: toRecipient=${giftWrapToRecipient.id.take(16)}...")
        return listOf(giftWrapToRecipient)
    }

    fun decryptPrivateMessage(
        giftWrap: NostrEvent,
        recipientIdentity: NostrIdentity
    ): Triple<String, String, Int>? {
        Log.v(TAG, "Starting decryption of gift wrap: ${giftWrap.id.take(16)}...")

        return try {

            val seal = unwrapGiftWrap(giftWrap, recipientIdentity.privateKeyHex)
                ?: run {
                    Log.w(TAG, "Failed to unwrap gift wrap")
                    return null
                }

            Log.v(TAG, "Successfully unwrapped gift wrap from: ${seal.pubkey.take(16)}...")

            val rumor = openSeal(seal, recipientIdentity.privateKeyHex)
                ?: run {
                    Log.w(TAG, "Failed to open seal")
                    return null
                }

            Log.v(TAG, "Successfully opened seal")

            Triple(rumor.content, rumor.pubkey, rumor.createdAt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt private message: ${e.message}")
            null
        }
    }

    suspend fun createGeohashTextNote(
        content: String,
        geohash: String,
        senderIdentity: NostrIdentity,
        nickname: String? = null
    ): NostrEvent = withContext(Dispatchers.Default) {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("g", geohash))

        if (!nickname.isNullOrEmpty()) {
            tags.add(listOf("n", nickname))
        }

        val event = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.TEXT_NOTE,
            tags = tags,
            content = content
        )

        return@withContext senderIdentity.signEvent(event)
    }

    suspend fun createGeohashPresenceEvent(
        geohash: String,
        senderIdentity: NostrIdentity
    ): NostrEvent = withContext(Dispatchers.Default) {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("g", geohash))

        val event = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.GEOHASH_PRESENCE,
            tags = tags,
            content = ""
        )

        return@withContext senderIdentity.signEvent(event)
    }

    suspend fun createEphemeralGeohashEvent(
        content: String,
        geohash: String,
        senderIdentity: NostrIdentity,
        nickname: String? = null,
        teleported: Boolean = false
    ): NostrEvent = withContext(Dispatchers.Default) {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("g", geohash))

        if (!nickname.isNullOrEmpty()) {
            tags.add(listOf("n", nickname))
        }

        if (teleported) {

            tags.add(listOf("t", "teleport"))
        }

        var event = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.EPHEMERAL_EVENT,
            tags = tags,
            content = content
        )

        val powSettings = PoWPreferenceManager.getCurrentSettings()
        if (powSettings.enabled && powSettings.difficulty > 0) {
            Log.d(TAG, "PoW enabled for geohash event: difficulty=${powSettings.difficulty}")

            try {

                PoWPreferenceManager.startMining()

                val minedEvent = NostrProofOfWork.mineEvent(
                    event = event,
                    targetDifficulty = powSettings.difficulty,
                    maxIterations = 2_000_000
                )

                if (minedEvent != null) {
                    event = minedEvent
                    val actualDifficulty = NostrProofOfWork.calculateDifficulty(event.id)
                    Log.d(TAG, "PoW mining successful: target=${powSettings.difficulty}, actual=$actualDifficulty, nonce=${NostrProofOfWork.getNonce(event)}")
                } else {
                    Log.w(TAG, "PoW mining failed, proceeding without PoW")
                }
            } finally {

                PoWPreferenceManager.stopMining()
            }
        }

        return@withContext senderIdentity.signEvent(event)
    }

    private fun createSeal(
        rumor: NostrEvent,
        recipientPubkey: String,
        senderPrivateKey: String,
        senderPublicKey: String
    ): NostrEvent {
        val rumorJSON = gson.toJson(rumor)

        val encrypted = NostrCrypto.encryptNIP44(
            plaintext = rumorJSON,
            recipientPublicKeyHex = recipientPubkey,
            senderPrivateKeyHex = senderPrivateKey
        )

        val seal = NostrEvent(
            pubkey = senderPublicKey,
            createdAt = NostrCrypto.randomizeTimestampUpToPast(),
            kind = NostrKind.SEAL,
            tags = emptyList(),
            content = encrypted
        )

        return seal.sign(senderPrivateKey)
    }

    private fun createGiftWrap(
        seal: NostrEvent,
        recipientPubkey: String
    ): NostrEvent {
        val sealJSON = gson.toJson(seal)

        val (wrapPrivateKey, wrapPublicKey) = NostrCrypto.generateKeyPair()
        Log.v(TAG, "Creating gift wrap with ephemeral key")

        val encrypted = NostrCrypto.encryptNIP44(
            plaintext = sealJSON,
            recipientPublicKeyHex = recipientPubkey,
            senderPrivateKeyHex = wrapPrivateKey
        )

        val giftWrap = NostrEvent(
            pubkey = wrapPublicKey,
            createdAt = NostrCrypto.randomizeTimestampUpToPast(),
            kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", recipientPubkey)),
            content = encrypted
        )

        return giftWrap.sign(wrapPrivateKey)
    }

    private fun unwrapGiftWrap(
        giftWrap: NostrEvent,
        recipientPrivateKey: String
    ): NostrEvent? {
        Log.d(TAG, "Unwrapping gift wrap; content prefix='${giftWrap.content.take(3)}' length=${giftWrap.content.length}")

        return try {
            val decrypted = NostrCrypto.decryptNIP44(
                ciphertext = giftWrap.content,
                senderPublicKeyHex = giftWrap.pubkey,
                recipientPrivateKeyHex = recipientPrivateKey
            )

            val jsonElement = JsonParser.parseString(decrypted)
            if (!jsonElement.isJsonObject) {
                Log.w(TAG, "Decrypted gift wrap is not a JSON object")
                return null
            }

            val jsonObject = jsonElement.asJsonObject
            val seal = NostrEvent(
                id = jsonObject.get("id")?.asString ?: "",
                pubkey = jsonObject.get("pubkey")?.asString ?: "",
                createdAt = jsonObject.get("created_at")?.asInt ?: 0,
                kind = jsonObject.get("kind")?.asInt ?: 0,
                tags = parseTagsFromJson(jsonObject.get("tags")?.asJsonArray) ?: emptyList(),
                content = jsonObject.get("content")?.asString ?: "",
                sig = jsonObject.get("sig")?.asString
            )

            Log.v(TAG, "Unwrapped seal with kind: ${seal.kind}")
            seal
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unwrap gift wrap: ${e.message}")
            null
        }
    }

    private fun openSeal(
        seal: NostrEvent,
        recipientPrivateKey: String
    ): NostrEvent? {
        return try {
            val decrypted = NostrCrypto.decryptNIP44(
                ciphertext = seal.content,
                senderPublicKeyHex = seal.pubkey,
                recipientPrivateKeyHex = recipientPrivateKey
            )

            val jsonElement = JsonParser.parseString(decrypted)
            if (!jsonElement.isJsonObject) {
                Log.w(TAG, "Decrypted seal is not a JSON object")
                return null
            }

            val jsonObject = jsonElement.asJsonObject
            NostrEvent(
                id = jsonObject.get("id")?.asString ?: "",
                pubkey = jsonObject.get("pubkey")?.asString ?: "",
                createdAt = jsonObject.get("created_at")?.asInt ?: 0,
                kind = jsonObject.get("kind")?.asInt ?: 0,
                tags = parseTagsFromJson(jsonObject.get("tags")?.asJsonArray) ?: emptyList(),
                content = jsonObject.get("content")?.asString ?: "",
                sig = jsonObject.get("sig")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open seal: ${e.message}")
            null
        }
    }

    private fun parseTagsFromJson(tagsArray: com.google.gson.JsonArray?): List<List<String>>? {
        if (tagsArray == null) return emptyList()

        return try {
            tagsArray.map { tagElement ->
                if (tagElement.isJsonArray) {
                    val tagArray = tagElement.asJsonArray
                    tagArray.map { it.asString }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tags: ${e.message}")
            null
        }
    }
}
