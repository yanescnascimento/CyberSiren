package com.cybersiren.android.noise

import android.util.Log
import com.cybersiren.android.noise.southernstorm.protocol.*
import com.cybersiren.android.util.toHexString
import java.security.SecureRandom

class NoiseSession(
    private val peerID: String,
    private val isInitiator: Boolean,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {

    companion object {
        private const val TAG = "NoiseSession"
        private const val NOISE_XX_PATTERN_LENGTH = 3

        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"

        private const val REKEY_TIME_LIMIT = com.cybersiren.android.util.AppConstants.Noise.REKEY_TIME_LIMIT_MS
        private const val REKEY_MESSAGE_LIMIT = com.cybersiren.android.util.AppConstants.Noise.REKEY_MESSAGE_LIMIT_SESSION

        private const val XX_MESSAGE_1_SIZE = 32
        private const val XX_MESSAGE_2_SIZE = 96
        private const val XX_MESSAGE_3_SIZE = 48

        private const val MAX_PAYLOAD_SIZE = com.cybersiren.android.util.AppConstants.Noise.MAX_PAYLOAD_SIZE_BYTES

        private const val NONCE_SIZE_BYTES = 4
        private const val REPLAY_WINDOW_SIZE = 1024
        private const val REPLAY_WINDOW_BYTES = REPLAY_WINDOW_SIZE / 8
        private const val HIGH_NONCE_WARNING_THRESHOLD = com.cybersiren.android.util.AppConstants.Noise.HIGH_NONCE_WARNING_THRESHOLD

        private fun isValidNonce(receivedNonce: Long, highestReceivedNonce: Long, replayWindow: ByteArray): Boolean {
            if (receivedNonce + REPLAY_WINDOW_SIZE <= highestReceivedNonce) {
                return false
            }

            if (receivedNonce > highestReceivedNonce) {
                return true
            }

            val offset = (highestReceivedNonce - receivedNonce).toInt()
            val byteIndex = offset / 8
            val bitIndex = offset % 8

            return (replayWindow[byteIndex].toInt() and (1 shl bitIndex)) == 0
        }

        private fun markNonceAsSeen(receivedNonce: Long, highestReceivedNonce: Long, replayWindow: ByteArray): Pair<Long, ByteArray> {
            var newHighestReceivedNonce = highestReceivedNonce
            val newReplayWindow = replayWindow.copyOf()

            if (receivedNonce > highestReceivedNonce) {
                val shift = (receivedNonce - highestReceivedNonce).toInt()

                if (shift >= REPLAY_WINDOW_SIZE) {

                    newReplayWindow.fill(0)
                } else {

                    for (i in (REPLAY_WINDOW_BYTES - 1) downTo 0) {
                        val sourceByteIndex = i - shift / 8
                        var newByte = 0

                        if (sourceByteIndex >= 0) {
                            newByte = (newReplayWindow[sourceByteIndex].toInt() and 0xFF) ushr (shift % 8)
                            if (sourceByteIndex > 0 && shift % 8 != 0) {
                                newByte = newByte or ((newReplayWindow[sourceByteIndex - 1].toInt() and 0xFF) shl (8 - shift % 8))
                            }
                        }

                        newReplayWindow[i] = (newByte and 0xFF).toByte()
                    }
                }

                newHighestReceivedNonce = receivedNonce
                newReplayWindow[0] = (newReplayWindow[0].toInt() or 1).toByte()
            } else {
                val offset = (highestReceivedNonce - receivedNonce).toInt()
                val byteIndex = offset / 8
                val bitIndex = offset % 8
                newReplayWindow[byteIndex] = (newReplayWindow[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }

            return Pair(newHighestReceivedNonce, newReplayWindow)
        }

        private fun extractNonceFromCiphertextPayload(combinedPayload: ByteArray): Pair<Long, ByteArray>? {
            if (combinedPayload.size < NONCE_SIZE_BYTES) {
                Log.w(TAG, "Combined payload too small: ${combinedPayload.size} < $NONCE_SIZE_BYTES")
                throw Exception("Combined payload too small: ${combinedPayload.size} < $NONCE_SIZE_BYTES")
            }

            try {

                var extractedNonce = 0L
                for (i in 0 until NONCE_SIZE_BYTES) {
                    extractedNonce = (extractedNonce shl 8) or (combinedPayload[i].toLong() and 0xFF)
                }

                val ciphertext = combinedPayload.copyOfRange(NONCE_SIZE_BYTES, combinedPayload.size)
                Log.d(TAG, "Extracted nonce: $extractedNonce, ciphertext size: ${ciphertext.size}")
                return Pair(extractedNonce, ciphertext)

            } catch (e: Exception) {
                throw Exception("Failed to extract nonce from payload: ${e.message}")
            }
        }

        private fun nonceToBytes(nonce: Long): ByteArray {
            val bytes = ByteArray(NONCE_SIZE_BYTES)
            var value = nonce
            for (i in (NONCE_SIZE_BYTES - 1) downTo 0) {
                bytes[i] = (value and 0xFF).toByte()
                value = value ushr 8
            }
            return bytes
        }
    }

    private var handshakeState: HandshakeState? = null
    private var sendCipher: CipherState? = null
    private var receiveCipher: CipherState? = null

    private var state: NoiseSessionState = NoiseSessionState.Uninitialized
    private val creationTime = System.currentTimeMillis()

    private var currentPattern = 0;
    private var messagesSent = 0L
    private var messagesReceived = 0L

    private var highestReceivedNonce = 0L
    private var replayWindow = ByteArray(REPLAY_WINDOW_BYTES)

    private val cipherLock = Any()

    private var remoteStaticPublicKey: ByteArray? = null
    private var handshakeHash: ByteArray? = null

    sealed class NoiseSessionState {
        object Uninitialized : NoiseSessionState()
        object Handshaking : NoiseSessionState()
        object Established : NoiseSessionState()
        data class Failed(val error: Throwable) : NoiseSessionState()

        override fun toString(): String = when (this) {
            is Uninitialized -> "uninitialized"
            is Handshaking -> "handshaking"
            is Established -> "established"
            is Failed -> "failed: ${error.message}"
        }
    }

    fun getState(): NoiseSessionState = state
    fun isEstablished(): Boolean = state is NoiseSessionState.Established
    fun isHandshaking(): Boolean = state is NoiseSessionState.Handshaking
    fun getCreationTime(): Long = creationTime

    init {
        try {

            validateStaticKeys()
            Log.d(TAG, "Created ${if (isInitiator) "initiator" else "responder"} session for $peerID")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to initialize Noise session: ${e.message}")
        }
    }

    private fun validateStaticKeys() {
        if (localStaticPrivateKey.size != 32) {
            throw IllegalArgumentException("Local static private key must be 32 bytes, got ${localStaticPrivateKey.size}")
        }
        if (localStaticPublicKey.size != 32) {
            throw IllegalArgumentException("Local static public key must be 32 bytes, got ${localStaticPublicKey.size}")
        }

        if (localStaticPrivateKey.all { it == 0.toByte() }) {
            throw IllegalArgumentException("Local static private key cannot be all zeros")
        }
        if (localStaticPublicKey.all { it == 0.toByte() }) {
            throw IllegalArgumentException("Local static public key cannot be all zeros")
        }

        Log.d(TAG, "Static keys validated successfully - private: ${localStaticPrivateKey.size} bytes, public: ${localStaticPublicKey.size} bytes")
    }

    private fun initializeNoiseHandshake(role: Int) {
        try {
            Log.d(TAG, "Creating HandshakeState with role: ${if (role == HandshakeState.INITIATOR) "INITIATOR" else "RESPONDER"}")

            Log.d(TAG, "=== ANDROID NOISE SESSION - BEFORE HANDSHAKE INIT ===")
            Log.d(TAG, "Creating NoiseHandshakeState for peer: $peerID")
            Log.d(TAG, "Role: ${if (role == HandshakeState.INITIATOR) "INITIATOR" else "RESPONDER"}")

            handshakeState = HandshakeState(PROTOCOL_NAME, role)
            Log.d(TAG, "HandshakeState created successfully")

            Log.d(TAG, "=== ANDROID NOISE SESSION - AFTER HANDSHAKE INIT ===")
            Log.d(TAG, "NoiseHandshakeState created and mixPreMessageKeys() completed")

            if (handshakeState?.needsLocalKeyPair() == true) {
                Log.d(TAG, "Local static key pair is required for XX pattern")

                val localKeyPair = handshakeState?.getLocalKeyPair()
                if (localKeyPair != null) {

                    Log.d(TAG, "Setting persistent static identity keys...")

                    localKeyPair.setPrivateKey(localStaticPrivateKey, 0)

                    if (!localKeyPair.hasPrivateKey() || !localKeyPair.hasPublicKey()) {
                        throw IllegalStateException("Failed to set static identity keys - local fork issue")
                    }

                    Log.d(TAG, "Successfully set persistent static identity keys")
                    Log.d(TAG, "Algorithm: ${localKeyPair.dhName}")
                    Log.d(TAG, "Private key length: ${localKeyPair.privateKeyLength}")
                    Log.d(TAG, "Public key length: ${localKeyPair.publicKeyLength}")

                    val verifyPrivate = ByteArray(32)
                    val verifyPublic = ByteArray(32)
                    localKeyPair.getPrivateKey(verifyPrivate, 0)
                    localKeyPair.getPublicKey(verifyPublic, 0)

                    Log.d(TAG, "Persistent identity public key: ${localStaticPublicKey.joinToString("") { "%02x".format(it) }}")
                    Log.d(TAG, "Set public key:               ${verifyPublic.joinToString("") { "%02x".format(it) }}")

                } else {
                    throw IllegalStateException("HandshakeState returned null for local key pair")
                }

            } else {
                Log.d(TAG, "Local static key pair not needed for this handshake pattern/role")
            }
            handshakeState?.start()
            Log.d(TAG, "Handshake state started successfully with persistent identity keys")

        } catch (e: Exception) {
            Log.e(TAG, "Exception during handshake initialization: ${e.message}", e)
            throw e
        }
    }

    @Synchronized
    fun startHandshake(): ByteArray {
        Log.d(TAG, "Starting noise XX handshake with $peerID as INITIATOR")

        if (!isInitiator) {
            throw IllegalStateException("Only initiator can start handshake")
        }

        if (state != NoiseSessionState.Uninitialized) {
            throw IllegalStateException("Handshake already started")
        }

        try {

            initializeNoiseHandshake(HandshakeState.INITIATOR)
            state = NoiseSessionState.Handshaking

            val messageBuffer = ByteArray(XX_MESSAGE_1_SIZE)
            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")
            val messageLength = handshakeStateLocal.writeMessage(messageBuffer, 0, null, 0, 0)
            currentPattern++
            val firstMessage = messageBuffer.copyOf(messageLength)

            if (firstMessage.size != XX_MESSAGE_1_SIZE) {
                Log.w(TAG, "Warning: XX message 1 size ${firstMessage.size} != expected $XX_MESSAGE_1_SIZE")
            }

            Log.d(TAG, "Sending XX handshake message 1 to $peerID (${firstMessage.size} bytes) currentPattern: $currentPattern")
            return firstMessage
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to start handshake: ${e.message}")
            throw e
        }
    }

    @Synchronized
    fun processHandshakeMessage(message: ByteArray): ByteArray? {
        Log.d(TAG, "Processing handshake message from $peerID (${message.size} bytes)")

        try {

            if (state == NoiseSessionState.Uninitialized && !isInitiator) {
                initializeNoiseHandshake(HandshakeState.RESPONDER)
                state = NoiseSessionState.Handshaking
                Log.d(TAG, "Initialized as RESPONDER for XX handshake with $peerID")
            }

            if (state != NoiseSessionState.Handshaking) {
                throw IllegalStateException("Invalid state for handshake: $state")
            }

            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")

            val payloadBuffer = ByteArray(XX_MESSAGE_2_SIZE + MAX_PAYLOAD_SIZE)

            val payloadLength = handshakeStateLocal.readMessage(message, 0, message.size, payloadBuffer, 0)
            currentPattern++
            Log.d(TAG, "Read handshake message, payload length: $payloadLength currentPattern: $currentPattern")

            val action = handshakeStateLocal.getAction()
            Log.d(TAG, "Handshake action after processing message: $action")

            return when (action) {
                HandshakeState.WRITE_MESSAGE -> {

                    val responseBuffer = ByteArray(XX_MESSAGE_2_SIZE + MAX_PAYLOAD_SIZE)
                    val responseLength = handshakeStateLocal.writeMessage(responseBuffer, 0, null, 0, 0)
                    currentPattern++
                    val response = responseBuffer.copyOf(responseLength)

                    Log.d(TAG, "Generated handshake response: ${response.size} bytes, action still: ${handshakeStateLocal.getAction()} currentPattern: $currentPattern")
                    completeHandshake()
                    response
                }

                HandshakeState.SPLIT -> {

                    completeHandshake()
                    Log.d(TAG, "SPLIT XX handshake completed with $peerID")
                    null
                }

                HandshakeState.FAILED -> {
                    throw Exception("Handshake failed - Noise library reported FAILED state")
                }

                HandshakeState.READ_MESSAGE -> {

                    Log.d(TAG, "Handshake waiting for next message from $peerID")
                    null
                }

                else -> {
                    Log.d(TAG, "Handshake action: $action - no immediate action needed")
                    null
                }
            }

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Handshake failed with $peerID: ${e.message}", e)
            throw e
        }
    }

    @Synchronized
    private fun completeHandshake() {
        if (currentPattern < NOISE_XX_PATTERN_LENGTH) {
            return
        }

        Log.d(TAG, "Completing XX handshake with $peerID")

        try {

            val cipherPair = handshakeState?.split()

            sendCipher = cipherPair?.getSender()
            receiveCipher = cipherPair?.getReceiver()

            if (handshakeState?.hasRemotePublicKey() == true) {
                val remoteDH = handshakeState?.getRemotePublicKey()
                if (remoteDH != null) {
                    remoteStaticPublicKey = ByteArray(32)
                    remoteDH.getPublicKey(remoteStaticPublicKey!!, 0)
                    Log.d(TAG, "Remote static public key: ${remoteStaticPublicKey!!.joinToString("") { "%02x".format(it) }}")
                }
            }

            handshakeHash = handshakeState?.getHandshakeHash()

            handshakeState?.destroy()
            handshakeState = null

            messagesSent = 0
            messagesReceived = 0
            currentPattern = 0

            highestReceivedNonce = 0L
            replayWindow = ByteArray(REPLAY_WINDOW_BYTES)

            state = NoiseSessionState.Established
            Log.d(TAG, "Handshake completed with $peerID as isInitiator: $isInitiator - transport keys derived")
            Log.d(TAG, "XX handshake completed with $peerID")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to complete handshake: ${e.message}")
            throw e
        }
    }

    fun encrypt(data: ByteArray): ByteArray {

        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }

        synchronized(cipherLock) {

            if (!isEstablished()) {
                throw IllegalStateException("Session not established during cipher operation")
            }

            if (sendCipher == null) {
                throw IllegalStateException("Send cipher not available")
            }

            if (messagesSent > UInt.MAX_VALUE.toLong() - 1) {
                throw SessionError.NonceExceeded("Nonce value $messagesSent exceeds 4-byte limit")
            }

            try {

                if (sendCipher!!.macLength != 16) {
                    throw IllegalStateException("Send cipher MAC length is not 16")
                }

                val ciphertext = ByteArray(data.size + sendCipher!!.macLength)
                sendCipher!!.setNonce(messagesSent)
                val ciphertextLength = sendCipher!!.encryptWithAd(null, data, 0, ciphertext, 0, data.size)

                val currentNonce = messagesSent
                messagesSent++

                val nonceBytes = nonceToBytes(currentNonce)
                val combinedPayload = ByteArray(NONCE_SIZE_BYTES + ciphertextLength)

                System.arraycopy(nonceBytes, 0, combinedPayload, 0, NONCE_SIZE_BYTES)

                System.arraycopy(ciphertext, 0, combinedPayload, NONCE_SIZE_BYTES, ciphertextLength)

                if (currentNonce > HIGH_NONCE_WARNING_THRESHOLD) {
                    Log.w(TAG, "High nonce value detected: $currentNonce - consider rekeying")
                }

                Log.d(TAG, "ANDROID ENCRYPT: ${data.size} → ${combinedPayload.size} bytes (nonce: $currentNonce, ciphertextLength+TAG: ${ciphertextLength}) for $peerID (msg #$messagesSent, role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
                return combinedPayload

            } catch (e: Exception) {
                Log.e(TAG, "Real encryption failed - exception: ${e.message}")

                if (sendCipher != null) {
                    Log.e(TAG, "Send cipher state: ${sendCipher!!.javaClass.simpleName}")
                }

                throw SessionError.EncryptionFailed
            }
        }
    }

    fun decrypt(combinedPayload: ByteArray): ByteArray {

        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }

        synchronized(cipherLock) {

            if (!isEstablished()) {
                throw IllegalStateException("Session not established during cipher operation")
            }

            if (receiveCipher == null) {
                throw IllegalStateException("Receive cipher not available")
            }

            try {

                val nonceAndCiphertext = extractNonceFromCiphertextPayload(combinedPayload)
                if (nonceAndCiphertext == null) {
                    Log.e(TAG, "Failed to extract nonce from payload for $peerID")
                    throw SessionError.DecryptionFailed
                }

                val (extractedNonce, ciphertext) = nonceAndCiphertext

                if (!isValidNonce(extractedNonce, highestReceivedNonce, replayWindow)) {
                    Log.w(TAG, "Replay attack detected: nonce $extractedNonce rejected for $peerID")
                    throw SessionError.DecryptionFailed
                }

                val plaintext = ByteArray(ciphertext.size)

                 receiveCipher!!.setNonce(extractedNonce)
                val plaintextLength = receiveCipher!!.decryptWithAd(null, ciphertext, 0, plaintext, 0, ciphertext.size)

                val (newHighestReceivedNonce, newReplayWindow) = markNonceAsSeen(extractedNonce, highestReceivedNonce, replayWindow)
                highestReceivedNonce = newHighestReceivedNonce
                replayWindow = newReplayWindow

                if (extractedNonce > HIGH_NONCE_WARNING_THRESHOLD) {
                    Log.w(TAG, "High nonce value detected: $extractedNonce - consider rekeying")
                }

                val result = plaintext.copyOf(plaintextLength)
                Log.d(TAG, "ANDROID DECRYPT: ${combinedPayload.size} → ${result.size} bytes from $peerID (nonce: $extractedNonce, highest: $highestReceivedNonce, role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
                return result

            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed - exception: ${e.message}")

                if (receiveCipher != null) {
                    Log.e(TAG, "Receive cipher state: ${receiveCipher!!.javaClass.simpleName}")
                }
                Log.e(TAG, "Session state: $state, highest received nonce: $highestReceivedNonce")
                Log.e(TAG, "Input data size: ${combinedPayload.size} bytes")

                throw SessionError.DecryptionFailed
            }
        }
    }

    fun getRemoteStaticPublicKey(): ByteArray? {
        return remoteStaticPublicKey?.clone()
    }

    fun getHandshakeHash(): ByteArray? {
        return handshakeHash?.clone()
    }

    fun needsRekey(): Boolean {
        if (!isEstablished()) return false

        val timeLimit = System.currentTimeMillis() - creationTime > REKEY_TIME_LIMIT
        val messageLimit = (messagesSent + messagesReceived) > REKEY_MESSAGE_LIMIT

        return timeLimit || messageLimit
    }

    fun getSessionStats(): String = buildString {
        appendLine("NoiseSession with $peerID:")
        appendLine("  State: $state")
        appendLine("  Role: ${if (isInitiator) "initiator" else "responder"}")
        appendLine("  Messages sent: $messagesSent")
        appendLine("  Messages received: $messagesReceived")
        appendLine("  Session age: ${(System.currentTimeMillis() - creationTime) / 1000}s")
        appendLine("  Needs rekey: ${needsRekey()}")
        appendLine("  Has remote key: ${remoteStaticPublicKey != null}")
        appendLine("  Has send cipher: ${sendCipher != null}")
        appendLine("  Has receive cipher: ${receiveCipher != null}")
    }

    @Synchronized
    fun reset() {
        try {

            destroy()

            state = NoiseSessionState.Uninitialized
            messagesSent = 0
            messagesReceived = 0

            highestReceivedNonce = 0L
            replayWindow = ByteArray(REPLAY_WINDOW_BYTES)

            remoteStaticPublicKey = null
            handshakeHash = null
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to reset session: ${e.message}")
        }
    }

    @Synchronized
    fun destroy() {
        try {

            sendCipher?.destroy()
            receiveCipher?.destroy()
            handshakeState?.destroy()

            remoteStaticPublicKey?.fill(0)
            handshakeHash?.fill(0)

            sendCipher = null
            receiveCipher = null
            handshakeState = null
            remoteStaticPublicKey = null
            handshakeHash = null

            if (state !is NoiseSessionState.Failed) {
                state = NoiseSessionState.Failed(Exception("Session destroyed"))
            }

            Log.d(TAG, "Session destroyed for $peerID")

        } catch (e: Exception) {
            Log.w(TAG, "Error during session cleanup: ${e.message}")
        }
    }
}

sealed class SessionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object InvalidState : SessionError("Session in invalid state")
    object NotEstablished : SessionError("Session not established")
    object HandshakeFailed : SessionError("Handshake failed")
    object EncryptionFailed : SessionError("Encryption failed")
    object DecryptionFailed : SessionError("Decryption failed")
    class HandshakeInitializationFailed(message: String) : SessionError("Handshake initialization failed: $message")
    class NonceExceeded(message: String) : SessionError(message)
}
