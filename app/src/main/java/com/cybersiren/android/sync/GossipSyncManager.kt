package com.cybersiren.android.sync

import android.util.Log
import com.cybersiren.android.mesh.BluetoothPacketBroadcaster
import com.cybersiren.android.model.RequestSyncPacket
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.protocol.MessageType
import com.cybersiren.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class GossipSyncManager(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val configProvider: ConfigProvider
) {
    interface Delegate {
        fun sendPacket(packet: BitchatPacket)
        fun sendPacketToPeer(peerID: String, packet: BitchatPacket)
        fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket
    }

    interface ConfigProvider {
        fun seenCapacity(): Int
        fun gcsMaxBytes(): Int
        fun gcsTargetFpr(): Double
    }

    companion object {
        private const val TAG = "GossipSyncManager"
    }

    var delegate: Delegate? = null

    private val defaultMaxBytes = SyncDefaults.DEFAULT_FILTER_BYTES
    private val defaultFpr = SyncDefaults.DEFAULT_FPR_PERCENT

    private val messages = LinkedHashMap<String, BitchatPacket>()

    private val latestAnnouncementByPeer = ConcurrentHashMap<String, Pair<String, BitchatPacket>>()

    private var periodicJob: Job? = null
    private var cleanupJob: Job? = null
    fun start() {
        periodicJob?.cancel()
        periodicJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(30_000)
                    sendRequestSync()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Periodic sync error: ${e.message}") }
            }
        }

        cleanupJob?.cancel()
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(com.cybersiren.android.util.AppConstants.Sync.CLEANUP_INTERVAL_MS)
                    pruneStaleAnnouncements()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Periodic cleanup error: ${e.message}") }
            }
        }
    }

    fun stop() {
        periodicJob?.cancel(); periodicJob = null
        cleanupJob?.cancel(); cleanupJob = null
    }

    fun scheduleInitialSync(delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSync()
        }
    }

    fun scheduleInitialSyncToPeer(peerID: String, delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSyncToPeer(peerID)
        }
    }

    fun onPublicPacketSeen(packet: BitchatPacket) {

        val mt = MessageType.fromValue(packet.type)
        val isBroadcastMessage = (mt == MessageType.MESSAGE && (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)))
        val isAnnouncement = (mt == MessageType.ANNOUNCE)
        if (!isBroadcastMessage && !isAnnouncement) return

        val idBytes = PacketIdUtil.computeIdBytes(packet)
        val id = idBytes.joinToString("") { b -> "%02x".format(b) }

        if (isBroadcastMessage) {
            synchronized(messages) {
                messages[id] = packet

                val cap = configProvider.seenCapacity().coerceAtLeast(1)
                while (messages.size > cap) {
                    val it = messages.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
                }
            }
        } else if (isAnnouncement) {

            val now = System.currentTimeMillis()
            val age = now - packet.timestamp.toLong()
            if (age > com.cybersiren.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                Log.d(TAG, "Ignoring stale ANNOUNCE (age=${age}ms > ${com.cybersiren.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
                return
            }

            val sender = packet.senderID.joinToString("") { b -> "%02x".format(b) }
            latestAnnouncementByPeer[sender] = id to packet

            val cap = configProvider.seenCapacity().coerceAtLeast(1)
            while (latestAnnouncementByPeer.size > cap) {
                val it = latestAnnouncementByPeer.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() } else break
            }
        }
    }

    private fun sendRequestSync() {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = com.cybersiren.android.util.AppConstants.SYNC_TTL_HOPS
        )

        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacket(signed)
    }

    private fun sendRequestSyncToPeer(peerID: String) {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(peerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = com.cybersiren.android.util.AppConstants.SYNC_TTL_HOPS
        )
        Log.d(TAG, "Sending sync request to $peerID (${payload.size} bytes)")

        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacketToPeer(peerID, signed)
    }

    fun handleRequestSync(fromPeerID: String, request: RequestSyncPacket) {

        val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)
        fun mightContain(id: ByteArray): Boolean {
            val v = (GCSFilter.run {

                val md = java.security.MessageDigest.getInstance("SHA-256");
                md.update(id); val d = md.digest();
                var x = 0L; for (i in 0 until 8) { x = (x shl 8) or (d[i].toLong() and 0xFF) }
                (x and 0x7fff_ffff_ffff_ffffL) % request.m
            })
            return GCSFilter.contains(sorted, v)
        }

        for ((_, pair) in latestAnnouncementByPeer.entries) {
            val (id, pkt) = pair
            val idBytes = hexToBytes(id)
            if (!mightContain(idBytes)) {

                val toSend = pkt.copy(ttl = com.cybersiren.android.util.AppConstants.SYNC_TTL_HOPS)
                delegate?.sendPacketToPeer(fromPeerID, toSend)
                Log.d(TAG, "Sent sync announce: Type ${toSend.type} from ${toSend.senderID.toHexString()} to $fromPeerID packet id ${idBytes.toHexString()}")
            }
        }

        val toSendMsgs = synchronized(messages) { messages.values.toList() }
        for (pkt in toSendMsgs) {
            val idBytes = PacketIdUtil.computeIdBytes(pkt)
            if (!mightContain(idBytes)) {
                val toSend = pkt.copy(ttl = com.cybersiren.android.util.AppConstants.SYNC_TTL_HOPS)
                delegate?.sendPacketToPeer(fromPeerID, toSend)
                Log.d(TAG, "Sent sync message: Type ${toSend.type} to $fromPeerID packet id ${idBytes.toHexString()}")
            }
        }
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) result[index] = byte
            tempID = tempID.substring(2)
            index++
        }
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i/2] = clean.substring(i, i+2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun buildGcsPayload(): ByteArray {

        val list = ArrayList<BitchatPacket>()

        for ((_, pair) in latestAnnouncementByPeer) {
            list.add(pair.second)
        }

        synchronized(messages) {
            list.addAll(messages.values)
        }

        list.sortByDescending { it.timestamp.toLong() }

        val maxBytes = try { configProvider.gcsMaxBytes() } catch (_: Exception) { defaultMaxBytes }
        val fpr = try { configProvider.gcsTargetFpr() } catch (_: Exception) { defaultFpr }
        val p = GCSFilter.deriveP(fpr)
        val nMax = GCSFilter.estimateMaxElementsForSize(maxBytes, p)
        val cap = configProvider.seenCapacity().coerceAtLeast(1)
        val takeN = minOf(nMax, cap, list.size)
        if (takeN <= 0) {
            val p0 = GCSFilter.deriveP(fpr)
            return RequestSyncPacket(p = p0, m = 1, data = ByteArray(0)).encode()
        }
        val ids = list.take(takeN).map { pkt -> PacketIdUtil.computeIdBytes(pkt) }
        val params = GCSFilter.buildFilter(ids, maxBytes, fpr)
        val mVal = if (params.m <= 0L) 1 else params.m
        return RequestSyncPacket(p = params.p, m = mVal, data = params.data).encode()
    }

    private fun pruneStaleAnnouncements() {
        val now = System.currentTimeMillis()
        val stalePeers = mutableListOf<String>()

        for ((peerID, pair) in latestAnnouncementByPeer.entries) {
            val pkt = pair.second
            val age = now - pkt.timestamp.toLong()
            if (age > com.cybersiren.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                stalePeers.add(peerID)
            }
        }

        if (stalePeers.isEmpty()) return

        var totalPrunedMsgs = 0
        for (peerID in stalePeers) {

            val toRemove = mutableListOf<String>()
            synchronized(messages) {
                for ((id, message) in messages) {
                    val sender = message.senderID.joinToString("") { b -> "%02x".format(b) }
                    if (sender == peerID) toRemove.add(id)
                }
            }
            totalPrunedMsgs += toRemove.size

            removeAnnouncementForPeer(peerID)
        }

        Log.d(TAG, "Pruned ${stalePeers.size} stale announcements and $totalPrunedMsgs messages")
    }

    fun removeAnnouncementForPeer(peerID: String) {
        val key = peerID.lowercase()
        if (latestAnnouncementByPeer.remove(key) != null) {
            Log.d(TAG, "Removed stored announcement for peer $peerID")
        }

        val idsToRemove = mutableListOf<String>()
        synchronized(messages) {
            for ((id, message) in messages) {
                val sender = message.senderID.joinToString("") { b -> "%02x".format(b) }
                if (sender == key) {
                    idsToRemove.add(id)
                }
            }
        }

        synchronized(messages) {
            for (id in idsToRemove) {
                messages.remove(id)
            }
        }

        if (idsToRemove.isNotEmpty()) {
            Log.d(TAG, "Pruned ${idsToRemove.size} messages with senders without announcements")
        }
    }
}
