package com.cybersiren.android.mesh

import android.util.Log
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.protocol.MessageType
import com.cybersiren.android.model.RoutedPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor

class PacketProcessor(private val myPeerID: String) {
    private val debugManager by lazy { try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "PacketProcessor"
    }

    var delegate: PacketProcessorDelegate? = null

    private fun formatPeerForLog(peerID: String): String {
        val nickname = delegate?.getPeerNickname(peerID)
        return if (nickname != null) "$peerID ($nickname)" else peerID
    }

    private val packetRelayManager = PacketRelayManager(myPeerID)

    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val peerActors = mutableMapOf<String, CompletableDeferred<Unit>>()

    @OptIn(ObsoleteCoroutinesApi::class)
    private fun getOrCreateActorForPeer(peerID: String) = processorScope.actor<RoutedPacket>(
        capacity = Channel.UNLIMITED
    ) {
        Log.d(TAG, "Created packet actor for peer: ${formatPeerForLog(peerID)}")
        try {
            for (packet in channel) {
                Log.d(TAG, "Processing packet type ${packet.packet.type} from ${formatPeerForLog(peerID)} (serialized)")
                handleReceivedPacket(packet)
                Log.d(TAG, "Completed packet type ${packet.packet.type} from ${formatPeerForLog(peerID)}")
            }
        } finally {
            Log.d(TAG, "Packet actor for ${formatPeerForLog(peerID)} terminated")
        }
    }

    private val actors = mutableMapOf<String, kotlinx.coroutines.channels.SendChannel<RoutedPacket>>()

    init {

        setupRelayManager()
    }

    fun processPacket(routed: RoutedPacket) {
        Log.d(TAG, "processPacket ${routed.packet.type}")
        val peerID = routed.peerID

        if (peerID == null) {
            Log.w(TAG, "Received packet with no peer ID, skipping")
            return
        }

        val actor = actors.getOrPut(peerID) { getOrCreateActorForPeer(peerID) }

        processorScope.launch {
            try {
                actor.send(routed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send packet to actor for ${formatPeerForLog(peerID)}: ${e.message}")

                handleReceivedPacket(routed)
            }
        }
    }

    fun setupRelayManager() {
        packetRelayManager.delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize(): Int {
                return delegate?.getNetworkSize() ?: 1
            }

            override fun getBroadcastRecipient(): ByteArray {
                return delegate?.getBroadcastRecipient() ?: ByteArray(0)
            }

            override fun broadcastPacket(routed: RoutedPacket) {
                delegate?.relayPacket(routed)
            }
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return delegate?.sendToPeer(peerID, routed) ?: false
            }
        }
    }

    private suspend fun handleReceivedPacket(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (!delegate?.validatePacketSecurity(packet, peerID)!!) {
            Log.d(TAG, "Packet failed security validation from ${formatPeerForLog(peerID)}")
            return
        }

        var validPacket = true
        val messageType = MessageType.fromValue(packet.type)
        Log.d(TAG, "Processing packet type ${messageType} from ${formatPeerForLog(peerID)}")

        try {
            val mt = messageType?.name ?: packet.type.toString()
            val routeDevice = routed.relayAddress
            val nick = delegate?.getPeerNickname(peerID)
            debugManager?.logIncomingPacket(peerID, nick, mt, routeDevice)
        } catch (_: Exception) { }

        when (messageType) {
            MessageType.ANNOUNCE -> handleAnnounce(routed)
            MessageType.MESSAGE -> handleMessage(routed)
            MessageType.FILE_TRANSFER -> handleMessage(routed)
            MessageType.LEAVE -> handleLeave(routed)
            MessageType.FRAGMENT -> handleFragment(routed)
            MessageType.REQUEST_SYNC -> handleRequestSync(routed)
            MessageType.EMERGENCY_ALERT -> handleEmergencyAlert(routed)
            else -> {

                if (packetRelayManager.isPacketAddressedToMe(packet)) {
                    when (messageType) {
                        MessageType.NOISE_HANDSHAKE -> handleNoiseHandshake(routed)
                        MessageType.NOISE_ENCRYPTED -> handleNoiseEncrypted(routed)
                        MessageType.FILE_TRANSFER -> handleMessage(routed)
                        else -> {
                            validPacket = false
                            Log.w(TAG, "Unknown message type: ${packet.type}")
                        }
                    }
                } else {
                    Log.d(TAG, "Private packet type ${messageType} not addressed to us (from: ${formatPeerForLog(peerID)} to ${packet.recipientID?.let { it.joinToString("") { b -> "%02x".format(b) } }}), skipping")
                }
            }
        }

        if (validPacket) {
            delegate?.updatePeerLastSeen(peerID)

            packetRelayManager.handlePacketRelay(routed)
        }
    }

    private suspend fun handleNoiseHandshake(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise handshake from ${formatPeerForLog(peerID)}")
        delegate?.handleNoiseHandshake(routed)
    }

    private suspend fun handleNoiseEncrypted(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise encrypted message from ${formatPeerForLog(peerID)}")
        delegate?.handleNoiseEncrypted(routed)
    }

    private suspend fun handleAnnounce(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing announce from ${formatPeerForLog(peerID)}")
        delegate?.handleAnnounce(routed)
    }

    private suspend fun handleMessage(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing message from ${formatPeerForLog(peerID)}")
        delegate?.handleMessage(routed)
    }

    private suspend fun handleLeave(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing leave from ${formatPeerForLog(peerID)}")
        delegate?.handleLeave(routed)
    }

    private suspend fun handleFragment(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing fragment from ${formatPeerForLog(peerID)}")

        val reassembledPacket = delegate?.handleFragment(routed.packet)
        if (reassembledPacket != null) {
            Log.d(TAG, "Fragment reassembled, processing complete message")
            handleReceivedPacket(RoutedPacket(reassembledPacket, routed.peerID, routed.relayAddress))
        }

    }

    private suspend fun handleRequestSync(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing REQUEST_SYNC from ${formatPeerForLog(peerID)}")
        delegate?.handleRequestSync(routed)
    }

    private suspend fun handleEmergencyAlert(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing EMERGENCY_ALERT from ${formatPeerForLog(peerID)}")
        delegate?.handleEmergencyAlert(routed)
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Processor Debug Info ===")
            appendLine("Processor Scope Active: ${processorScope.isActive}")
            appendLine("Active Peer Actors: ${actors.size}")
            appendLine("My Peer ID: $myPeerID")

            if (actors.isNotEmpty()) {
                appendLine("Peer Actors:")
                actors.keys.forEach { peerID ->
                    appendLine("  - $peerID")
                }
            }
        }
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down PacketProcessor and ${actors.size} peer actors")

        actors.values.forEach { actor ->
            actor.close()
        }
        actors.clear()

        packetRelayManager.shutdown()

        processorScope.cancel()

        Log.d(TAG, "PacketProcessor shutdown complete")
    }
}

interface PacketProcessorDelegate {

    fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean

    fun updatePeerLastSeen(peerID: String)
    fun getPeerNickname(peerID: String): String?

    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray

    fun handleNoiseHandshake(routed: RoutedPacket): Boolean
    fun handleNoiseEncrypted(routed: RoutedPacket)
    fun handleAnnounce(routed: RoutedPacket)
    fun handleMessage(routed: RoutedPacket)
    fun handleLeave(routed: RoutedPacket)
    fun handleFragment(packet: BitchatPacket): BitchatPacket?
    fun handleRequestSync(routed: RoutedPacket)
    fun handleEmergencyAlert(routed: RoutedPacket)

    fun sendAnnouncementToPeer(peerID: String)
    fun sendCachedMessages(peerID: String)
    fun relayPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}
