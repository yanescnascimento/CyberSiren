package com.cybersiren.android.mesh

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class PeerFingerprintManager private constructor() {

    companion object {
        private const val TAG = "PeerFingerprintManager"

        @Volatile
        private var INSTANCE: PeerFingerprintManager? = null

        fun getInstance(): PeerFingerprintManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PeerFingerprintManager().also { INSTANCE = it }
            }
        }
    }

    private val peerIDToFingerprint = ConcurrentHashMap<String, String>()
    private val fingerprintToPeerID = ConcurrentHashMap<String, String>()

    fun storeFingerprintForPeer(peerID: String, publicKey: ByteArray): String {

        val existingFingerprint = getFingerprintForPeer(peerID)
        val fingerprint = calculateFingerprint(publicKey)

        if (existingFingerprint != null && existingFingerprint != fingerprint) {
            Log.w(TAG, "Fingerprint mismatch for peer $peerID: $existingFingerprint != $fingerprint")
            throw IllegalStateException("Fingerprint mismatch for peer $peerID: $existingFingerprint != $fingerprint")
        }

        peerIDToFingerprint[peerID] = fingerprint
        fingerprintToPeerID[fingerprint] = peerID

        Log.d(TAG, "Stored fingerprint for peer $peerID: ${fingerprint.take(16)}...")
        return fingerprint
    }

    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        if (newPeerID.isBlank()) {
            Log.w(TAG, "Attempted to update mapping with blank newPeerID")
            return
        }

        if (fingerprint.isBlank()) {
            Log.w(TAG, "Attempted to update mapping with blank fingerprint")
            return
        }

        oldPeerID?.takeIf { it.isNotBlank() }?.let { oldID ->
            val removedFingerprint = peerIDToFingerprint.remove(oldID)
            if (removedFingerprint != null && removedFingerprint == fingerprint) {
                Log.d(TAG, "Removed old mapping: $oldID -> ${removedFingerprint.take(16)}...")
            }
        }

        peerIDToFingerprint[newPeerID] = fingerprint
        fingerprintToPeerID[fingerprint] = newPeerID

        Log.d(TAG, "Updated peer ID mapping: $newPeerID (was: $oldPeerID), fingerprint: ${fingerprint.take(16)}...")
    }

    fun getFingerprintForPeer(peerID: String): String? {
        if (peerID.isBlank()) return null
        return peerIDToFingerprint[peerID]
    }

    fun getPeerIDForFingerprint(fingerprint: String): String? {
        if (fingerprint.isBlank()) return null
        return fingerprintToPeerID[fingerprint]
    }

    fun hasFingerprintForPeer(peerID: String): Boolean {
        return getFingerprintForPeer(peerID) != null
    }

    fun getAllPeerFingerprints(): Map<String, String> {
        return peerIDToFingerprint.toMap()
    }

    fun getAllFingerprintMappings(): Map<String, String> {
        return fingerprintToPeerID.toMap()
    }

    fun removePeer(peerID: String) {
        if (peerID.isBlank()) return

        val fingerprint = peerIDToFingerprint.remove(peerID)
        if (fingerprint != null) {
            fingerprintToPeerID.remove(fingerprint)
            Log.d(TAG, "Removed peer mappings for $peerID: ${fingerprint.take(16)}...")
        }
    }

    fun removeFingerprint(fingerprint: String) {
        if (fingerprint.isBlank()) return

        val peerID = fingerprintToPeerID.remove(fingerprint)
        if (peerID != null) {
            peerIDToFingerprint.remove(peerID)
            Log.d(TAG, "Removed fingerprint mappings for ${fingerprint.take(16)}...: $peerID")
        }
    }

    fun clearAllFingerprints() {
        val count = peerIDToFingerprint.size
        peerIDToFingerprint.clear()
        fingerprintToPeerID.clear()
    }

    private fun calculateFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== PeerFingerprintManager Debug Info ===")
            appendLine("Total mappings: ${peerIDToFingerprint.size}")

            if (peerIDToFingerprint.isNotEmpty()) {
                appendLine("Peer ID -> Fingerprint mappings:")
                peerIDToFingerprint.forEach { (peerID, fingerprint) ->
                    appendLine("  $peerID -> ${fingerprint.take(16)}...")
                }
            } else {
                appendLine("No fingerprint mappings stored")
            }

            val inconsistentMappings = mutableListOf<String>()
            peerIDToFingerprint.forEach { (peerID, fingerprint) ->
                val reversePeerID = fingerprintToPeerID[fingerprint]
                if (reversePeerID != peerID) {
                    inconsistentMappings.add("$peerID -> $fingerprint -> $reversePeerID")
                }
            }

            if (inconsistentMappings.isNotEmpty()) {
                appendLine("INCONSISTENT MAPPINGS DETECTED:")
                inconsistentMappings.forEach { appendLine("  $it") }
            }
        }
    }
}
