package com.cybersiren.android.nostr

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

data class NostrEvent(
    var id: String = "",
    val pubkey: String,
    @SerializedName("created_at") val createdAt: Int,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    var sig: String? = null
) {

    companion object {

        fun fromJson(json: Map<String, Any>): NostrEvent? {
            return try {
                NostrEvent(
                    id = json["id"] as? String ?: "",
                    pubkey = json["pubkey"] as? String ?: return null,
                    createdAt = (json["created_at"] as? Number)?.toInt() ?: return null,
                    kind = (json["kind"] as? Number)?.toInt() ?: return null,
                    tags = (json["tags"] as? List<List<String>>) ?: return null,
                    content = json["content"] as? String ?: return null,
                    sig = json["sig"] as? String?
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromJsonString(jsonString: String): NostrEvent? {
            return try {
                val gson = Gson()
                gson.fromJson(jsonString, NostrEvent::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun createTextNote(
            content: String,
            publicKeyHex: String,
            privateKeyHex: String,
            tags: List<List<String>> = emptyList(),
            createdAt: Int = (System.currentTimeMillis() / 1000).toInt()
        ): NostrEvent {
            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.TEXT_NOTE,
                tags = tags,
                content = content
            )
            return event.sign(privateKeyHex)
        }

        fun createMetadata(
            metadata: String,
            publicKeyHex: String,
            privateKeyHex: String,
            createdAt: Int = (System.currentTimeMillis() / 1000).toInt()
        ): NostrEvent {
            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.METADATA,
                tags = emptyList(),
                content = metadata
            )
            return event.sign(privateKeyHex)
        }
    }

    fun sign(privateKeyHex: String): NostrEvent {
        val (eventId, eventIdHash) = calculateEventId()

        val signature = signHash(eventIdHash, privateKeyHex)

        return this.copy(
            id = eventId,
            sig = signature
        )
    }

    fun computeEventIdHex(): String {
        val (eventId, _) = calculateEventId()
        return eventId
    }

    private fun calculateEventId(): Pair<String, ByteArray> {

        val serialized = listOf(
            0,
            pubkey,
            createdAt,
            kind,
            tags,
            content
        )

        val gson = GsonBuilder().disableHtmlEscaping().create()
        val jsonString = gson.toJson(serialized)

        val digest = MessageDigest.getInstance("SHA-256")
        val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
        val hash = digest.digest(jsonBytes)

        val hexId = hash.joinToString("") { "%02x".format(it) }

        return Pair(hexId, hash)
    }

    private fun signHash(hash: ByteArray, privateKeyHex: String): String {
        return try {

            NostrCrypto.schnorrSign(hash, privateKeyHex)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign event: ${e.message}", e)
        }
    }

    fun toJsonString(): String {
        val gson = Gson()
        return gson.toJson(this)
    }

    fun isValidSignature(): Boolean {
        return try {
            val signatureHex = sig ?: return false
            if (id.isEmpty() || pubkey.isEmpty()) return false

            val (calculatedId, messageHash) = calculateEventId()

            if (calculatedId != id) return false

            NostrCrypto.schnorrVerify(messageHash, signatureHex, pubkey)
        } catch (e: Exception) {
            false
        }
    }

    fun isValid(): Boolean {
        return try {

            if (pubkey.isEmpty() || content.isEmpty()) return false
            if (createdAt <= 0 || kind < 0) return false
            if (!NostrCrypto.isValidPublicKey(pubkey)) return false

            isValidSignature()
        } catch (e: Exception) {
            false
        }
    }
}

object NostrKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val DIRECT_MESSAGE = 14
    const val FILE_MESSAGE = 15
    const val SEAL = 13
    const val GIFT_WRAP = 1059
    const val EPHEMERAL_EVENT = 20000
    const val GEOHASH_PRESENCE = 20001
}

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
