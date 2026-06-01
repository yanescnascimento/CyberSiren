package com.cybersiren.android.noise

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class NoiseChannelEncryption {

    companion object {
        private const val TAG = "NoiseChannelEncryption"

        private const val PBKDF2_ITERATIONS = 100000
        private const val KEY_LENGTH = 256
    }

    private val channelKeys = ConcurrentHashMap<String, SecretKeySpec>()

    private val channelPasswords = ConcurrentHashMap<String, String>()

    fun setChannelPassword(password: String, channel: String) {
        try {
            if (password.isEmpty()) {
                Log.w(TAG, "Empty password provided for channel $channel")
                return
            }

            val key = deriveChannelKey(password, channel)

            channelKeys[channel] = key
            channelPasswords[channel] = password

            Log.d(TAG, "Set password for channel $channel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set password for channel $channel: ${e.message}")
        }
    }

    fun removeChannelPassword(channel: String) {
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        Log.d(TAG, "Removed password for channel $channel")
    }

    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }

    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }

    fun encryptChannelMessage(message: String, channel: String): ByteArray {
        val key = channelKeys[channel]
            ?: throw IllegalStateException("No key available for channel $channel")

        val messageBytes = message.toByteArray(Charsets.UTF_8)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            val encryptedData = cipher.doFinal(messageBytes)

            val result = ByteArray(iv.size + encryptedData.size)
            System.arraycopy(iv, 0, result, 0, iv.size)
            System.arraycopy(encryptedData, 0, result, iv.size, encryptedData.size)

            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt channel message: ${e.message}")
            throw e
        }
    }

    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String {
        val key = channelKeys[channel]
            ?: throw IllegalStateException("No key available for channel $channel")

        if (encryptedData.size < 16) {
            throw IllegalArgumentException("Encrypted data too short")
        }

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            val iv = encryptedData.sliceArray(0..11)
            val ciphertext = encryptedData.sliceArray(12 until encryptedData.size)

            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt channel message: ${e.message}")
            throw e
        }
    }

    private fun deriveChannelKey(password: String, channel: String): SecretKeySpec {
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

            val salt = channel.toByteArray(Charsets.UTF_8)

            val spec = PBEKeySpec(
                password.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                KEY_LENGTH
            )

            val secretKey = factory.generateSecret(spec)
            return SecretKeySpec(secretKey.encoded, "AES")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive channel key: ${e.message}")
            throw e
        }
    }

    fun calculateKeyCommitment(channel: String): String? {
        val key = channelKeys[channel] ?: return null

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key.encoded)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate key commitment: ${e.message}")
            null
        }
    }

    fun verifyKeyCommitment(channel: String, commitment: String): Boolean {
        val ourCommitment = calculateKeyCommitment(channel)
        return ourCommitment?.lowercase() == commitment.lowercase()
    }

    fun createChannelKeyPacket(password: String, channel: String): ByteArray? {
        return try {

            val packet = mapOf(
                "channel" to channel,
                "password" to password,
                "timestamp" to System.currentTimeMillis()
            )

            val json = com.google.gson.Gson().toJson(packet)
            json.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create channel key packet: ${e.message}")
            null
        }
    }

    fun processChannelKeyPacket(data: ByteArray): Pair<String, String>? {
        return try {
            val json = String(data, Charsets.UTF_8)
            val packet = com.google.gson.Gson().fromJson(json, Map::class.java) as Map<String, Any>

            val channel = packet["channel"] as? String
            val password = packet["password"] as? String

            if (channel != null && password != null) {
                Pair(channel, password)
            } else {
                Log.w(TAG, "Invalid channel key packet format")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process channel key packet: ${e.message}")
            null
        }
    }

    fun getDebugInfo(): String = buildString {
        appendLine("=== Channel Encryption Debug ===")
        appendLine("Active channels: ${channelKeys.size}")

        channelKeys.keys.forEach { channel ->
            val hasPassword = channelPasswords.containsKey(channel)
            val commitment = calculateKeyCommitment(channel)?.take(16)
            appendLine("  $channel: hasPassword=$hasPassword, commitment=${commitment}...")
        }
    }

    fun getActiveChannels(): Set<String> {
        return channelKeys.keys.toSet()
    }

    fun clear() {
        channelKeys.clear()
        channelPasswords.clear()
        Log.d(TAG, "Cleared all channel encryption data")
    }
}
