package com.cybersiren.android.nostr

import android.content.Context
import android.util.Log
import com.cybersiren.android.identity.SecureIdentityStateManager
import java.security.MessageDigest
import java.security.SecureRandom

data class NostrIdentity(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val npub: String,
    val createdAt: Long
) {

    companion object {
        private const val TAG = "NostrIdentity"

        fun generate(): NostrIdentity {
            val (privateKeyHex, publicKeyHex) = NostrCrypto.generateKeyPair()
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArrayLocal())

            Log.d(TAG, "Generated new Nostr identity: npub=$npub")

            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = System.currentTimeMillis()
            )
        }

        fun fromPrivateKey(privateKeyHex: String): NostrIdentity {
            require(NostrCrypto.isValidPrivateKey(privateKeyHex)) {
                "Invalid private key"
            }

            val publicKeyHex = NostrCrypto.derivePublicKey(privateKeyHex)
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArrayLocal())

            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = System.currentTimeMillis()
            )
        }

        fun fromSeed(seed: String): NostrIdentity {

            val digest = MessageDigest.getInstance("SHA-256")
            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            val privateKeyBytes = digest.digest(seedBytes)
            val privateKeyHex = privateKeyBytes.joinToString("") { "%02x".format(it) }

            return fromPrivateKey(privateKeyHex)
        }
    }

    fun signEvent(event: NostrEvent): NostrEvent {
        return event.sign(privateKeyHex)
    }

    fun getShortNpub(): String {
        return if (npub.length > 16) {
            "${npub.take(8)}...${npub.takeLast(8)}"
        } else {
            npub
        }
    }
}

object NostrIdentityBridge {
    private const val TAG = "NostrIdentityBridge"
    private const val NOSTR_PRIVATE_KEY = "nostr_private_key"
    private const val DEVICE_SEED_KEY = "nostr_device_seed"

    private val geohashIdentityCache = mutableMapOf<String, NostrIdentity>()

    fun getCurrentNostrIdentity(context: Context): NostrIdentity? {
        val stateManager = SecureIdentityStateManager(context)

        val existingKey = loadNostrPrivateKey(stateManager)
        if (existingKey != null) {
            return try {
                NostrIdentity.fromPrivateKey(existingKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create identity from stored key: ${e.message}")
                null
            }
        }

        val newIdentity = NostrIdentity.generate()
        saveNostrPrivateKey(stateManager, newIdentity.privateKeyHex)

        Log.i(TAG, "Created new Nostr identity: ${newIdentity.getShortNpub()}")
        return newIdentity
    }

    fun deriveIdentity(forGeohash: String, context: Context): NostrIdentity {

        geohashIdentityCache[forGeohash]?.let { cachedIdentity ->

            return cachedIdentity
        }

        val stateManager = SecureIdentityStateManager(context)
        val seed = getOrCreateDeviceSeed(stateManager)

        val geohashBytes = forGeohash.toByteArray(Charsets.UTF_8)

        for (i in 0 until 10) {
            val candidateKey = candidateKey(seed, geohashBytes, i.toUInt())
            val candidateKeyHex = candidateKey.toHexStringLocal()

            if (NostrCrypto.isValidPrivateKey(candidateKeyHex)) {
                val identity = NostrIdentity.fromPrivateKey(candidateKeyHex)

                geohashIdentityCache[forGeohash] = identity

                Log.d(TAG, "Derived geohash identity for $forGeohash (iteration $i)")
                return identity
            }
        }

        val combined = seed + geohashBytes
        val digest = MessageDigest.getInstance("SHA-256")
        val fallbackKey = digest.digest(combined)

        val fallbackIdentity = NostrIdentity.fromPrivateKey(fallbackKey.toHexStringLocal())

        geohashIdentityCache[forGeohash] = fallbackIdentity

        Log.d(TAG, "Used fallback identity derivation for $forGeohash")
        return fallbackIdentity
    }

    private fun candidateKey(seed: ByteArray, message: ByteArray, iteration: UInt): ByteArray {
        val input = message + iteration.toLittleEndianBytes()
        return hmacSha256(seed, input)
    }

    fun associateNostrIdentity(nostrPubkey: String, noisePublicKey: ByteArray, context: Context) {
        val stateManager = SecureIdentityStateManager(context)

        Log.d(TAG, "Associated Nostr pubkey ${nostrPubkey.take(16)}... with Noise key")
    }

    fun getNostrPublicKey(noisePublicKey: ByteArray, context: Context): String? {

        return null
    }

    fun clearAllAssociations(context: Context) {
        val stateManager = SecureIdentityStateManager(context)

        geohashIdentityCache.clear()

        try {
            stateManager.clearSecureValues(NOSTR_PRIVATE_KEY, DEVICE_SEED_KEY)

            Log.i(TAG, "Cleared all Nostr identity data and cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Nostr data: ${e.message}")
        }
    }

    private fun loadNostrPrivateKey(stateManager: SecureIdentityStateManager): String? {
        return try {

            stateManager.getSecureValue(NOSTR_PRIVATE_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Nostr private key: ${e.message}")
            null
        }
    }

    private fun saveNostrPrivateKey(stateManager: SecureIdentityStateManager, privateKeyHex: String) {
        try {

            stateManager.storeSecureValue(NOSTR_PRIVATE_KEY, privateKeyHex)

            Log.d(TAG, "Saved Nostr private key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Nostr private key: ${e.message}")
            throw e
        }
    }

    private fun getOrCreateDeviceSeed(stateManager: SecureIdentityStateManager): ByteArray {
        try {

            val existingSeed = stateManager.getSecureValue(DEVICE_SEED_KEY)
            if (existingSeed != null) {
                return android.util.Base64.decode(existingSeed, android.util.Base64.DEFAULT)
            }

            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)

            val seedBase64 = android.util.Base64.encodeToString(seed, android.util.Base64.DEFAULT)
            stateManager.storeSecureValue(DEVICE_SEED_KEY, seedBase64)

            Log.d(TAG, "Generated new device seed for geohash identity derivation")
            return seed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get/create device seed: ${e.message}")
            throw e
        }
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKeySpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKeySpec)
        return mac.doFinal(message)
    }
}

private fun String.hexToByteArrayLocal(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun ByteArray.toHexStringLocal(): String {
    return joinToString("") { "%02x".format(it) }
}

private fun UInt.toLittleEndianBytes(): ByteArray {
    val bytes = ByteArray(4)
    bytes[0] = (this and 0xFFu).toByte()
    bytes[1] = ((this shr 8) and 0xFFu).toByte()
    bytes[2] = ((this shr 16) and 0xFFu).toByte()
    bytes[3] = ((this shr 24) and 0xFFu).toByte()
    return bytes
}
