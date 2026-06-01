package com.cybersiren.android.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cybersiren.android.noise.NoiseEncryptionService
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.edit

open class EncryptionService(private val context: Context) {

    companion object {
        private const val TAG = "EncryptionService"
        private const val ED25519_PRIVATE_KEY_PREF = "ed25519_signing_private_key"
        private const val OLD_PREFS_NAME = "bitchat_crypto"
        private const val SECURE_PREFS_NAME = "bitchat_crypto_secure"
    }

    private val noiseService: NoiseEncryptionService by lazy { NoiseEncryptionService(context) }

    private val establishedSessions = ConcurrentHashMap<String, String>()

    private lateinit var ed25519PrivateKey: Ed25519PrivateKeyParameters
    private lateinit var ed25519PublicKey: Ed25519PublicKeyParameters

    var onSessionEstablished: ((String) -> Unit)? = null
    var onSessionLost: ((String) -> Unit)? = null
    var onHandshakeRequired: ((String) -> Unit)? = null
    private lateinit var prefs: SharedPreferences

    init {
        initialize()
    }

    private fun setUpEncryptedPrefs() {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    protected open fun initialize() {
        setUpEncryptedPrefs()

        val keyPair = loadOrCreateEd25519KeyPair()
        ed25519PrivateKey = keyPair.private as Ed25519PrivateKeyParameters
        ed25519PublicKey = keyPair.public as Ed25519PublicKeyParameters

        Log.d(TAG, "Ed25519 signing keys initialized")

        noiseService.onPeerAuthenticated = { peerID, fingerprint ->
            Log.d(TAG, "Noise session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
            establishedSessions[peerID] = fingerprint
            onSessionEstablished?.invoke(peerID)
        }

        noiseService.onHandshakeRequired = { peerID ->
            Log.d(TAG, "Handshake required for $peerID")
            onHandshakeRequired?.invoke(peerID)
        }
    }

    fun getCombinedPublicKeyData(): ByteArray {
        return noiseService.getStaticPublicKeyData()
    }

    fun getStaticPublicKey(): ByteArray? {
        return noiseService.getStaticPublicKeyData()
    }

    fun getSigningPublicKey(): ByteArray? {
        return ed25519PublicKey.encoded
    }

    fun signData(data: ByteArray): ByteArray? {
        return try {
            val signer = Ed25519Signer()
            signer.init(true, ed25519PrivateKey)
            signer.update(data, 0, data.size)
            val signature = signer.generateSignature()
            Log.d(TAG, "Generated Ed25519 signature (${signature.size} bytes)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign data with Ed25519: ${e.message}")
            null
        }
    }

    @Throws(Exception::class)
    fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
        Log.d(TAG, "Legacy addPeerPublicKey called for $peerID with ${publicKeyData.size} bytes")

        if (!hasEstablishedSession(peerID)) {
            Log.d(TAG, "No Noise session with $peerID, initiating handshake")
            initiateHandshake(peerID)
        }
    }

    fun getPeerIdentityKey(peerID: String): ByteArray? {
        val fingerprint = getPeerFingerprint(peerID) ?: return null
        return fingerprint.toByteArray()
    }

    fun clearPersistentIdentity() {
        noiseService.clearPersistentIdentity()
        establishedSessions.clear()

        try {
            prefs.edit { remove(ED25519_PRIVATE_KEY_PREF) }
            Log.d(TAG, "Cleared Ed25519 signing keys from preferences")

            val keyPair = loadOrCreateEd25519KeyPair()
            ed25519PrivateKey = keyPair.private as Ed25519PrivateKeyParameters
            ed25519PublicKey = keyPair.public as Ed25519PublicKeyParameters
            Log.d(TAG, "Rotated Ed25519 signing keys in memory")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Ed25519 keys: ${e.message}")
        }
    }

    @Throws(Exception::class)
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val encrypted = noiseService.encrypt(data, peerID)
        if (encrypted == null) {
            throw Exception("Failed to encrypt for $peerID")
        }
        return encrypted
    }

    @Throws(Exception::class)
    fun decrypt(data: ByteArray, peerID: String): ByteArray {
        val decrypted = noiseService.decrypt(data, peerID)
        if (decrypted == null) {
            throw Exception("Failed to decrypt from $peerID")
        }
        return decrypted
    }

    @Throws(Exception::class)
    fun sign(data: ByteArray): ByteArray {

        return ByteArray(0)
    }

    @Throws(Exception::class)
    fun verify(signature: ByteArray, data: ByteArray, peerID: String): Boolean {

        return hasEstablishedSession(peerID)
    }

    fun hasEstablishedSession(peerID: String): Boolean {
        return noiseService.hasEstablishedSession(peerID)
    }

    fun getSessionState(peerID: String): com.cybersiren.android.noise.NoiseSession.NoiseSessionState {
        return noiseService.getSessionState(peerID)
    }

    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }

    fun getPeerFingerprint(peerID: String): String? {
        return noiseService.getPeerFingerprint(peerID)
    }

    fun getCurrentPeerID(fingerprint: String): String? {
        return noiseService.getPeerID(fingerprint)
    }

    fun initiateHandshake(peerID: String): ByteArray? {
        Log.d(TAG, "Initiating Noise handshake with $peerID")
        return noiseService.initiateHandshake(peerID)
    }

    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        Log.d(TAG, "Processing handshake message from $peerID")
        return noiseService.processHandshakeMessage(data, peerID)
    }

    fun removePeer(peerID: String) {
        establishedSessions.remove(peerID)
        noiseService.removePeer(peerID)
        onSessionLost?.invoke(peerID)
        Log.d(TAG, "Removed session for $peerID")
    }

    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        oldPeerID?.let { establishedSessions.remove(it) }
        establishedSessions[newPeerID] = fingerprint
        noiseService.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }

    fun setChannelPassword(password: String, channel: String) {
        noiseService.setChannelPassword(password, channel)
    }

    fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return noiseService.encryptChannelMessage(message, channel)
    }

    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return noiseService.decryptChannelMessage(encryptedData, channel)
    }

    fun removeChannelPassword(channel: String) {
        noiseService.removeChannelPassword(channel)
    }

    fun getEstablishedPeers(): List<String> {
        return establishedSessions.keys.toList()
    }

    fun getSessionsNeedingRekey(): List<String> {
        return noiseService.getSessionsNeedingRekey()
    }

    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "Initiating rekey for $peerID")
        establishedSessions.remove(peerID)
        return noiseService.initiateRekey(peerID)
    }

    fun getIdentityFingerprint(): String {
        return noiseService.getIdentityFingerprint()
    }

    fun getDebugInfo(): String = buildString {
        appendLine("=== EncryptionService Debug ===")
        appendLine("Established Sessions: ${establishedSessions.size}")
        appendLine("Our Fingerprint: ${getIdentityFingerprint().take(16)}...")

        if (establishedSessions.isNotEmpty()) {
            appendLine("Active Encrypted Sessions:")
            establishedSessions.forEach { (peerID, fingerprint) ->
                appendLine("  $peerID -> ${fingerprint.take(16)}...")
            }
        }

        appendLine("")
        appendLine(noiseService.toString())
    }

    fun shutdown() {
        establishedSessions.clear()
        noiseService.shutdown()
        Log.d(TAG, "EncryptionService shut down")
    }

    open fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(data, 0, data.size)
            val isValid = verifier.verifySignature(signature)
            Log.d(TAG, "Ed25519 signature verification: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify Ed25519 signature: ${e.message}")
            false
        }
    }

    private fun loadOrCreateEd25519KeyPair(): AsymmetricCipherKeyPair {

        migrateOldEd25519KeyIfNeeded()
        try {
            val storedKey = prefs.getString(ED25519_PRIVATE_KEY_PREF, null)

            if (storedKey != null) {

                val privateKeyBytes = Base64.decode(storedKey, Base64.DEFAULT)
                val privateKey = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
                val publicKey = privateKey.generatePublicKey()
                Log.d(TAG, "Loaded existing Ed25519 signing key pair")
                return AsymmetricCipherKeyPair(publicKey, privateKey)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load existing Ed25519 key, creating new one: ${e.message}")
        }

        return generateAndSaveEd25519KeyPair()
    }

    fun generateAndSaveEd25519KeyPair(): AsymmetricCipherKeyPair {
        val keyGen = Ed25519KeyPairGenerator()
        keyGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyGen.generateKeyPair()

        try {
            val privateKey = keyPair.private as Ed25519PrivateKeyParameters
            val privateKeyBytes = privateKey.encoded
            val encodedKey = Base64.encodeToString(privateKeyBytes, Base64.DEFAULT)

            prefs.edit { putString(ED25519_PRIVATE_KEY_PREF, encodedKey) }
            Log.d(TAG, "Created and stored new Ed25519 signing key pair")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store Ed25519 private key: ${e.message}")
        }

        return keyPair
    }

    private fun migrateOldEd25519KeyIfNeeded() {
        try {

            val oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)

            val oldKey = oldPrefs.getString(ED25519_PRIVATE_KEY_PREF, null)

            if (oldKey != null && !prefs.contains(ED25519_PRIVATE_KEY_PREF)) {
                prefs.edit {
                    putString(ED25519_PRIVATE_KEY_PREF, oldKey)
                }
                oldPrefs.edit {
                    remove(ED25519_PRIVATE_KEY_PREF)
                }
                Log.d(TAG, "Migrated Ed25519 key to EncryptedSharedPreferences")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate Ed25519 key; generating new identity: ${e.message}")
        }
    }
}
