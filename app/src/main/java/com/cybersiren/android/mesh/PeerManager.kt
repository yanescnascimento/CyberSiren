package com.cybersiren.android.mesh

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class PeerInfo(
    val id: String,
    var nickname: String,
    var isConnected: Boolean,
    var isDirectConnection: Boolean,
    var noisePublicKey: ByteArray?,
    var signingPublicKey: ByteArray?,
    var isVerifiedNickname: Boolean,
    var lastSeen: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerInfo

        if (id != other.id) return false
        if (nickname != other.nickname) return false
        if (isConnected != other.isConnected) return false
        if (isDirectConnection != other.isDirectConnection) return false
        if (noisePublicKey != null) {
            if (other.noisePublicKey == null) return false
            if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        } else if (other.noisePublicKey != null) return false
        if (signingPublicKey != null) {
            if (other.signingPublicKey == null) return false
            if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        } else if (other.signingPublicKey != null) return false
        if (isVerifiedNickname != other.isVerifiedNickname) return false
        if (lastSeen != other.lastSeen) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + isDirectConnection.hashCode()
        result = 31 * result + (noisePublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (signingPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + isVerifiedNickname.hashCode()
        result = 31 * result + lastSeen.hashCode()
        return result
    }
}

class PeerManager {

    companion object {
        private const val TAG = "PeerManager"
    }

    private val stalePeerTimeoutMs: Long = com.cybersiren.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS

    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val peerRSSI = ConcurrentHashMap<String, Int>()
    private val announcedPeers = CopyOnWriteArrayList<String>()
    private val announcedToPeers = CopyOnWriteArrayList<String>()

    private val fingerprintManager = PeerFingerprintManager.getInstance()

    var delegate: PeerManagerDelegate? = null

    var isPeerDirectlyConnected: ((String) -> Boolean)? = null

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startPeriodicCleanup()
    }

    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        if (peerID == "unknown") return false

        val now = System.currentTimeMillis()
        val existingPeer = peers[peerID]
        val isNewPeer = existingPeer == null

        val peerInfo = PeerInfo(
            id = peerID,
            nickname = nickname,
            isConnected = true,
            isDirectConnection = existingPeer?.isDirectConnection ?: false,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            isVerifiedNickname = isVerified,
            lastSeen = now
        )

        peers[peerID] = peerInfo

        if (isNewPeer && isVerified) {
            announcedPeers.add(peerID)
            notifyPeerListUpdate()
            Log.d(TAG, "New verified peer: $nickname ($peerID)")
            return true
        } else if (isVerified) {
            Log.d(TAG, "Updated verified peer: $nickname ($peerID)")
        } else {
            Log.d(TAG, "Unverified peer announcement from: $nickname ($peerID)")
        }

        return false
    }

    fun getPeerInfo(peerID: String): PeerInfo? {
        return peers[peerID]?.let { info ->

            val isDirect = isPeerDirectlyConnected?.invoke(peerID) ?: false
            if (info.isDirectConnection != isDirect) {
                info.copy(isDirectConnection = isDirect)
            } else {
                info
            }
        }
    }

    fun isPeerVerified(peerID: String): Boolean {
        return peers[peerID]?.isVerifiedNickname == true
    }

    fun getVerifiedPeers(): Map<String, PeerInfo> {
        return peers.filterValues { it.isVerifiedNickname }.mapValues { (_, info) ->
            val isDirect = isPeerDirectlyConnected?.invoke(info.id) ?: false
            if (info.isDirectConnection != isDirect) info.copy(isDirectConnection = isDirect) else info
        }
    }

    fun refreshPeerList() {
        notifyPeerListUpdate()
    }

    fun updatePeerLastSeen(peerID: String) {
        if (peerID != "unknown") {
            peers[peerID]?.let { info ->
                peers[peerID] = info.copy(lastSeen = System.currentTimeMillis())
            }
        }
    }

    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
        if (peerID == "unknown") return false

        val now = System.currentTimeMillis()
        val stalePeerIDs = mutableListOf<String>()
        peers.forEach { (existingPeerID, info) ->
            if (info.nickname == nickname && existingPeerID != peerID) {
                val wasRecentlySeen = (now - info.lastSeen) < 10000
                if (!wasRecentlySeen) {
                    stalePeerIDs.add(existingPeerID)
                }
            }
        }

        stalePeerIDs.forEach { stalePeerID ->
            removePeer(stalePeerID, notifyDelegate = false)
        }

        val isFirstAnnounce = !announcedPeers.contains(peerID)

        val existing = peers[peerID]
        if (existing != null) {
            peers[peerID] = existing.copy(nickname = nickname, lastSeen = now, isConnected = true)
        } else {
            peers[peerID] = PeerInfo(
                id = peerID,
                nickname = nickname,
                isConnected = true,
                isDirectConnection = false,
                noisePublicKey = null,
                signingPublicKey = null,
                isVerifiedNickname = false,
                lastSeen = now
            )
        }

        if (isFirstAnnounce) {
            announcedPeers.add(peerID)
            notifyPeerListUpdate()
            return true
        }
        Log.d(TAG, "Updated peer: $peerID ($nickname)")
        return false
    }

    fun removePeer(peerID: String, notifyDelegate: Boolean = true) {
        val removed = peers.remove(peerID)
        peerRSSI.remove(peerID)
        announcedPeers.remove(peerID)
        announcedToPeers.remove(peerID)

        fingerprintManager.removePeer(peerID)

        if (notifyDelegate && removed != null) {

            try { delegate?.onPeerRemoved(peerID) } catch (_: Exception) {}
            notifyPeerListUpdate()
        }
    }

    fun updatePeerRSSI(peerID: String, rssi: Int) {
        if (peerID != "unknown") {
            peerRSSI[peerID] = rssi
        }
    }

    fun hasAnnouncedToPeer(peerID: String): Boolean {
        return announcedToPeers.contains(peerID)
    }

    fun markPeerAsAnnouncedTo(peerID: String) {
        if (!announcedToPeers.contains(peerID)) {
            announcedToPeers.add(peerID)
        }
    }

    fun isPeerActive(peerID: String): Boolean {
        val info = peers[peerID] ?: return false
        return info.isConnected
    }

    fun getPeerNickname(peerID: String): String? {
        return peers[peerID]?.nickname
    }

    fun getAllPeerNicknames(): Map<String, String> {
        return peers.mapValues { it.value.nickname }
    }

    fun getAllPeerRSSI(): Map<String, Int> {
        return peerRSSI.toMap()
    }

    fun getActivePeerIDs(): List<String> {
        return peers.filterValues { it.isConnected }
            .keys
            .toList()
            .sorted()
    }

    fun getActivePeerCount(): Int {
        return getActivePeerIDs().size
    }

    fun clearAllPeers() {
        peers.clear()
        peerRSSI.clear()
        announcedPeers.clear()
        announcedToPeers.clear()

        fingerprintManager.clearAllFingerprints()

        notifyPeerListUpdate()
    }

    fun getDebugInfo(addressPeerMap: Map<String, String>? = null): String {
        val now = System.currentTimeMillis()
        val activeIds = getActivePeerIDs().toSet()
        return buildString {
            appendLine("=== Peer Manager Debug Info ===")
            appendLine("Active Peers: ${activeIds.size}")
            peers.forEach { (peerID, storedInfo) ->

                val isDirect = isPeerDirectlyConnected?.invoke(peerID) ?: false
                val info = if (storedInfo.isDirectConnection != isDirect) storedInfo.copy(isDirectConnection = isDirect) else storedInfo

                val timeSince = (now - info.lastSeen) / 1000
                val rssi = peerRSSI[peerID]?.let { "${it} dBm" } ?: "No RSSI"
                val deviceAddress = addressPeerMap?.entries?.find { it.value == peerID }?.key
                val addressInfo = deviceAddress?.let { " [Device: $it]" } ?: " [Device: Unknown]"
                val status = if (activeIds.contains(peerID)) "ACTIVE" else "INACTIVE"
                val direct = if (info.isDirectConnection) "DIRECT" else "ROUTED"
                appendLine("  - $peerID (${info.nickname})$addressInfo - $status/$direct, last seen ${timeSince}s ago, RSSI: $rssi")
            }
            appendLine("Announced Peers: ${announcedPeers.size}")
            appendLine("Announced To Peers: ${announcedToPeers.size}")
        }
    }

    fun getDebugInfoWithDeviceAddresses(addressPeerMap: Map<String, String>): String {
        return buildString {
            appendLine("=== Device Address to Peer Mapping ===")
            if (addressPeerMap.isEmpty()) {
                appendLine("No device address mappings available")
            } else {
                addressPeerMap.forEach { (deviceAddress, peerID) ->
                    val nickname = peers[peerID]?.nickname ?: "Unknown"
                    val isActive = isPeerActive(peerID)
                    val status = if (isActive) "ACTIVE" else "INACTIVE"
                    appendLine("  Device: $deviceAddress -> Peer: $peerID ($nickname) [$status]")
                }
            }
            appendLine()
            appendLine(getDebugInfo(addressPeerMap))
        }
    }

    private fun notifyPeerListUpdate() {
        val peerList = getActivePeerIDs()
        delegate?.onPeerListUpdated(peerList)
    }

    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(com.cybersiren.android.util.AppConstants.Mesh.PEER_CLEANUP_INTERVAL_MS)
                cleanupStalePeers()
            }
        }
    }

    private fun cleanupStalePeers() {
        val now = System.currentTimeMillis()

        val peersToRemove = peers.filterValues { (now - it.lastSeen) > stalePeerTimeoutMs }
            .keys
            .toList()

        peersToRemove.forEach { peerID ->
            Log.d(TAG, "Removing stale peer: $peerID")
            removePeer(peerID)
        }

        if (peersToRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${peersToRemove.size} stale peers")
        }
    }

    fun storeFingerprintForPeer(peerID: String, publicKey: ByteArray): String {
        return fingerprintManager.storeFingerprintForPeer(peerID, publicKey)
    }

    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        fingerprintManager.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }

    fun getFingerprintForPeer(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }

    fun getPeerIDForFingerprint(fingerprint: String): String? {
        return fingerprintManager.getPeerIDForFingerprint(fingerprint)
    }

    fun hasFingerprintForPeer(peerID: String): Boolean {
        return fingerprintManager.hasFingerprintForPeer(peerID)
    }

    fun getAllPeerFingerprints(): Map<String, String> {
        return fingerprintManager.getAllPeerFingerprints()
    }

    fun clearAllFingerprints() {
        fingerprintManager.clearAllFingerprints()
    }

    fun getFingerprintDebugInfo(): String {
        return fingerprintManager.getDebugInfo()
    }

    fun shutdown() {
        managerScope.cancel()
        clearAllPeers()
    }
}

interface PeerManagerDelegate {
    fun onPeerListUpdated(peerIDs: List<String>)
    fun onPeerRemoved(peerID: String)
}
