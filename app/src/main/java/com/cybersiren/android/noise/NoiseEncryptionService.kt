package com.cybersiren.android.noise

import android.content.Context
import android.util.Log
import com.cybersiren.android.identity.SecureIdentityStateManager
import com.cybersiren.android.mesh.PeerFingerprintManager
import com.cybersiren.android.noise.southernstorm.protocol.Noise
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class NoiseEncryptionService(private val context: Context) {

    companion object {
        private const val TAG = "NoiseEncryptionService"

        private const val REKEY_TIME_LIMIT = com.cybersiren.android.util.AppConstants.Noise.REKEY_TIME_LIMIT_MS
        private const val REKEY_MESSAGE_LIMIT = com.cybersiren.android.util.AppConstants.Noise.REKEY_MESSAGE_LIMIT_ENCRYPTION
    }

    private var staticIdentityPrivateKey: ByteArray
    private var staticIdentityPublicKey: ByteArray

    private var signingPrivateKey: ByteArray
    private var signingPublicKey: ByteArray

    private lateinit var sessionManager: NoiseSessionManager

    private val channelEncryption = NoiseChannelEncryption()

    private val identityStateManager: SecureIdentityStateManager

    private val fingerprintManager = PeerFingerprintManager.getInstance()

    var onPeerAuthenticated: ((String, String) -> Unit)? = null
    var onHandshakeRequired: ((String) -> Unit)? = null

    init {

        identityStateManager = SecureIdentityStateManager(context)

        staticIdentityPrivateKey = ByteArray(32)
        staticIdentityPublicKey = ByteArray(32)
        signingPrivateKey = ByteArray(32)
        signingPublicKey = ByteArray(32)

        loadOrGenerateKeys()

        initializeSessionManager()
    }

    private fun initializeSessionManager() {

        sessionManager = NoiseSessionManager(staticIdentityPrivateKey, staticIdentityPublicKey)

        sessionManager.onSessionEstablished = { peerID, remoteStaticKey ->
            handleSessionEstablished(peerID, remoteStaticKey)
        }

    }

    private fun loadOrGenerateKeys() {

        val loadedKeyPair = identityStateManager.loadStaticKey()
        if (loadedKeyPair != null) {
            staticIdentityPrivateKey = loadedKeyPair.first
            staticIdentityPublicKey = loadedKeyPair.second
            Log.d(TAG, "Loaded existing static identity key: ${calculateFingerprint(staticIdentityPublicKey)}")
        } else {

            val keyPair = generateKeyPair()
            staticIdentityPrivateKey = keyPair.first
            staticIdentityPublicKey = keyPair.second

            identityStateManager.saveStaticKey(staticIdentityPrivateKey, staticIdentityPublicKey)
            Log.d(TAG, "Generated and saved new static identity key")
        }

        val loadedSigningKeyPair = identityStateManager.loadSigningKey()
        if (loadedSigningKeyPair != null) {
            signingPrivateKey = loadedSigningKeyPair.first
            signingPublicKey = loadedSigningKeyPair.second
            Log.d(TAG, "Loaded existing Ed25519 signing key")
        } else {

            val signingKeyPair = generateEd25519KeyPair()
            signingPrivateKey = signingKeyPair.first
            signingPublicKey = signingKeyPair.second

            identityStateManager.saveSigningKey(signingPrivateKey, signingPublicKey)
            Log.d(TAG, "Generated and saved new Ed25519 signing key")
        }
    }

    fun getStaticPublicKeyData(): ByteArray {
        return staticIdentityPublicKey.clone()
    }

    fun getSigningPublicKeyData(): ByteArray {
        return signingPublicKey.clone()
    }

    fun getIdentityFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(staticIdentityPublicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getPeerPublicKeyData(peerID: String): ByteArray? {
        return sessionManager.getRemoteStaticKey(peerID)
    }

    fun clearPersistentIdentity() {
        Log.w(TAG, "Panic Mode: Clearing persistent identity and rotating in-memory keys")

        identityStateManager.clearIdentityData()

        if (::sessionManager.isInitialized) {
            sessionManager.shutdown()
        }

        loadOrGenerateKeys()

        initializeSessionManager()

        Log.d(TAG, "Identity cleared and keys rotated")
    }

    fun initiateHandshake(peerID: String): ByteArray? {
        return try {
            sessionManager.initiateHandshake(peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate handshake with $peerID: ${e.message}")
            null
        }
    }

    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        return try {
            sessionManager.processHandshakeMessage(peerID, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process handshake from $peerID: ${e.message}")
            null
        }
    }

    fun hasEstablishedSession(peerID: String): Boolean {
        return sessionManager.hasEstablishedSession(peerID)
    }

    fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        return sessionManager.getSessionState(peerID)
    }

    fun encrypt(data: ByteArray, peerID: String): ByteArray? {
        if (!hasEstablishedSession(peerID)) {
            Log.w(TAG, "No established session with $peerID, handshake required. TODO: IMPLEMENT HANDSHAKE INIT")
            onHandshakeRequired?.invoke(peerID)
            return null
        }

        return try {
            sessionManager.encrypt(data, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $peerID: ${e.message}")
            null
        }
    }

    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray? {
        if (!hasEstablishedSession(peerID)) {
            Log.w(TAG, "No established session with $peerID")
            return null
        }

        return try {
            sessionManager.decrypt(encryptedData, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $peerID: ${e.message}")
            null
        }
    }

    fun getPeerFingerprint(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }

    fun getPeerID(fingerprint: String): String? {
        return fingerprintManager.getPeerIDForFingerprint(fingerprint)
    }

    fun removePeer(peerID: String) {
        sessionManager.removeSession(peerID)

        fingerprintManager.removePeer(peerID)
    }

    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {

        fingerprintManager.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }

    fun setChannelPassword(password: String, channel: String) {
        channelEncryption.setChannelPassword(password, channel)
    }

    fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return try {
            channelEncryption.encryptChannelMessage(message, channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt channel message for $channel: ${e.message}")
            null
        }
    }

    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return try {
            channelEncryption.decryptChannelMessage(encryptedData, channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt channel message for $channel: ${e.message}")
            null
        }
    }

    fun removeChannelPassword(channel: String) {
        channelEncryption.removeChannelPassword(channel)
    }

    fun getSessionsNeedingRekey(): List<String> {
        return sessionManager.getSessionsNeedingRekey()
    }

    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "Initiating rekey for session with $peerID")

        sessionManager.removeSession(peerID)

        return initiateHandshake(peerID)
    }

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        try {
            val dhState = com.cybersiren.android.noise.southernstorm.protocol.Noise.createDH("25519")
            dhState.generateKeyPair()

            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)

            dhState.getPrivateKey(privateKey, 0)
            dhState.getPublicKey(publicKey, 0)

            dhState.destroy()

            return Pair(privateKey, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair: ${e.message}")
            throw e
        }
    }

    private fun handleSessionEstablished(peerID: String, remoteStaticKey: ByteArray) {

        fingerprintManager.storeFingerprintForPeer(peerID, remoteStaticKey)

        val fingerprint = calculateFingerprint(remoteStaticKey)

        Log.d(TAG, "Session established with $peerID, fingerprint: ${fingerprint.take(16)}...")

        onPeerAuthenticated?.invoke(peerID, fingerprint)
    }

    private fun calculateFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun signPacket(packet: com.cybersiren.android.protocol.BitchatPacket): com.cybersiren.android.protocol.BitchatPacket? {

        val packetData = packet.toBinaryDataForSigning() ?: return null

        val signature = signData(packetData) ?: return null

        return packet.copy(signature = signature)
    }

    fun verifyPacketSignature(packet: com.cybersiren.android.protocol.BitchatPacket, publicKey: ByteArray): Boolean {
        val signature = packet.signature ?: return false

        val packetData = packet.toBinaryDataForSigning() ?: return false

        return verifySignature(signature, packetData, publicKey)
    }

    fun signData(data: ByteArray): ByteArray? {
        return try {

            signWithEd25519(data, signingPrivateKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign data: ${e.message}")
            null
        }
    }

    fun verifySignature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            verifyWithEd25519(signature, data, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify signature: ${e.message}")
            false
        }
    }

    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        try {

            val keyGen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
            keyGen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = keyGen.generateKeyPair()

            val privateKey = (keyPair.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters).encoded
            val publicKey = (keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded

            return Pair(privateKey, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate Ed25519 key pair: ${e.message}")
            throw e
        }
    }

    private fun signWithEd25519(data: ByteArray, privateKey: ByteArray): ByteArray {
        try {
            val privateKeyParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(privateKey, 0)
            val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
            signer.init(true, privateKeyParams)
            signer.update(data, 0, data.size)
            return signer.generateSignature()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign data with Ed25519: ${e.message}")
            throw e
        }
    }

    private fun verifyWithEd25519(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        try {
            val publicKeyParams = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKey, 0)
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(data, 0, data.size)
            return verifier.verifySignature(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify Ed25519 signature: ${e.message}")
            return false
        }
    }

    fun shutdown() {
        if (::sessionManager.isInitialized) {
            sessionManager.shutdown()
        }
        channelEncryption.clear()

    }
}

sealed class NoiseEncryptionError(message: String) : Exception(message) {
    object HandshakeRequired : NoiseEncryptionError("Handshake required before encryption")
    object SessionNotEstablished : NoiseEncryptionError("No established Noise session")
    object InvalidMessage : NoiseEncryptionError("Invalid message format")
    class HandshakeFailed(cause: Throwable) : NoiseEncryptionError("Handshake failed: ${cause.message}")
}
