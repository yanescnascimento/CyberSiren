package com.cybersiren.android.noise

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class NoiseSessionManager(
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {

    companion object {
        private const val TAG = "NoiseSessionManager"
    }

    private val sessions = ConcurrentHashMap<String, NoiseSession>()

    var onSessionEstablished: ((String, ByteArray) -> Unit)? = null
    var onSessionFailed: ((String, Throwable) -> Unit)? = null

    fun addSession(peerID: String, session: NoiseSession) {
        sessions[peerID] = session
        Log.d(TAG, "Added new session for $peerID")
    }

    fun getSession(peerID: String): NoiseSession? {
        val session = sessions[peerID]
        return session
    }

    fun removeSession(peerID: String) {
        sessions[peerID]?.destroy()
        sessions.remove(peerID)
        Log.d(TAG, "Removed session for $peerID")
    }

    fun initiateHandshake(peerID: String): ByteArray {
        Log.d(TAG, "initiateHandshake($peerID)")

        removeSession(peerID)

        val session = NoiseSession(
            peerID = peerID,
            isInitiator = true,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        )
        Log.d(TAG, "Storing new INITIATOR session for $peerID")
        addSession(peerID, session)

        try {
            val handshakeData = session.startHandshake()
            Log.d(TAG, "Started handshake with $peerID as INITIATOR")
            return handshakeData
        } catch (e: Exception) {
            sessions.remove(peerID)
            throw e
        }
    }

    fun processHandshakeMessage(peerID: String, message: ByteArray): ByteArray? {
        Log.d(TAG, "processHandshakeMessage($peerID, ${message.size} bytes)")

        try {
            var session = getSession(peerID)

            if (session == null) {
                Log.d(TAG, "Creating new RESPONDER session for $peerID")
                session = NoiseSession(
                    peerID = peerID,
                    isInitiator = false,
                    localStaticPrivateKey = localStaticPrivateKey,
                    localStaticPublicKey = localStaticPublicKey
                )
                addSession(peerID, session)
            }

            val response = session.processHandshakeMessage(message)

            if (session.isEstablished()) {
                Log.d(TAG, "Session ESTABLISHED with $peerID")
                val remoteStaticKey = session.getRemoteStaticPublicKey()
                if (remoteStaticKey != null) {
                    onSessionEstablished?.invoke(peerID, remoteStaticKey)
                }
            }

            return response

        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed with $peerID: ${e.message}")
            sessions.remove(peerID)
            onSessionFailed?.invoke(peerID, e)
            throw e
        }
    }

    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID) ?: throw IllegalStateException("No session found for $peerID")
        if (!session.isEstablished()) {
            throw IllegalStateException("Session not established with $peerID")
        }
        return session.encrypt(data)
    }

    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray {
        val session = getSession(peerID)
        if (session == null) {
            Log.e(TAG, "No session found for $peerID when trying to decrypt")
            throw IllegalStateException("No session found for $peerID")
        }
        if (!session.isEstablished()) {
            Log.e(TAG, "Session not established with $peerID when trying to decrypt")
            throw IllegalStateException("Session not established with $peerID")
        }
        return session.decrypt(encryptedData)
    }

    fun hasEstablishedSession(peerID: String): Boolean {
        val hasSession = getSession(peerID)?.isEstablished() ?: false
        Log.d(TAG, "hasEstablishedSession($peerID): $hasSession")
        return hasSession
    }

    fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        return getSession(peerID)?.getState() ?: NoiseSession.NoiseSessionState.Uninitialized
    }

    fun getRemoteStaticKey(peerID: String): ByteArray? {
        return getSession(peerID)?.getRemoteStaticPublicKey()
    }

    fun getHandshakeHash(peerID: String): ByteArray? {
        return getSession(peerID)?.getHandshakeHash()
    }

    fun getSessionsNeedingRekey(): List<String> {
        return sessions.entries
            .filter { (_, session) ->
                session.isEstablished() && session.needsRekey()
            }
            .map { it.key }
    }

    fun getDebugInfo(): String = buildString {
        appendLine("=== Noise Session Manager Debug ===")
        appendLine("Active sessions: ${sessions.size}")
        appendLine("")

        if (sessions.isNotEmpty()) {
            appendLine("Sessions:")
            sessions.forEach { (peerID, session) ->
                appendLine("  $peerID: ${session.getState()}")
            }
        }
    }

    fun shutdown() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        Log.d(TAG, "Noise session manager shut down")
    }
}

sealed class NoiseSessionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object SessionNotFound : NoiseSessionError("Session not found")
    object SessionNotEstablished : NoiseSessionError("Session not established")
    object InvalidState : NoiseSessionError("Session in invalid state")
    object HandshakeFailed : NoiseSessionError("Handshake failed")
    object AlreadyEstablished : NoiseSessionError("Session already established")
}
