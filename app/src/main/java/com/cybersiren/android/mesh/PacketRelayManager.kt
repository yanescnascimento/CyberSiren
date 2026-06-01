package com.cybersiren.android.mesh
import com.cybersiren.android.protocol.MessageType

import android.util.Log
import com.cybersiren.android.model.RoutedPacket
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.util.toHexString
import kotlinx.coroutines.*
import kotlin.random.Random

class PacketRelayManager(private val myPeerID: String) {
    private val debugManager by lazy { try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "PacketRelayManager"
    }

    private fun isRelayEnabled(): Boolean = try {
        com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().packetRelayEnabled.value
    } catch (_: Exception) { true }

    var delegate: PacketRelayManagerDelegate? = null

    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun handlePacketRelay(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        Log.d(TAG, "Evaluating relay for packet type ${packet.type} from ${peerID} (TTL: ${packet.ttl})")

        if (isPacketAddressedToMe(packet)) {
            Log.d(TAG, "Packet addressed to us, skipping relay")
            return
        }

        if (peerID == myPeerID) {
            Log.d(TAG, "Packet from ourselves, skipping relay")
            return
        }

        if (packet.ttl == 0u.toUByte()) {
            Log.d(TAG, "TTL expired, not relaying packet")
            return
        }

        val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        Log.d(TAG, "Decremented TTL from ${packet.ttl} to ${relayPacket.ttl}")

        val route = relayPacket.route
        if (!route.isNullOrEmpty()) {

            if (route.map { it.toHexString() }.toSet().size < route.size) {
                Log.w(TAG, "Packet with duplicate hops dropped")
                return
            }
            val myIdBytes = hexStringToPeerBytes(myPeerID)
            val index = route.indexOfFirst { it.contentEquals(myIdBytes) }
            if (index >= 0) {
                val nextHopIdHex: String? = run {
                    val nextIndex = index + 1
                    if (nextIndex < route.size) {
                        route[nextIndex].toHexString()
                    } else {

                        relayPacket.recipientID?.toHexString()
                    }
                }
                if (nextHopIdHex != null) {
                    val success = try { delegate?.sendToPeer(nextHopIdHex, RoutedPacket(relayPacket, peerID, routed.relayAddress)) } catch (_: Exception) { false } ?: false
                    if (success) {
                        Log.i(TAG, "Source-route relay: ${peerID.take(8)} -> ${nextHopIdHex.take(8)} (type ${'$'}{packet.type}, TTL ${'$'}{relayPacket.ttl})")
                        return
                    } else {
                        Log.w(TAG, "Source-route next hop ${nextHopIdHex.take(8)} not directly connected; falling back to broadcast")
                    }
                }
            }
        }

        val shouldRelay = isRelayEnabled() && shouldRelayPacket(relayPacket, peerID)
        if (shouldRelay) {
            relayPacket(RoutedPacket(relayPacket, peerID, routed.relayAddress))
        } else {
            Log.d(TAG, "Relay decision: NOT relaying packet type ${packet.type}")
        }
    }

    internal fun isPacketAddressedToMe(packet: BitchatPacket): Boolean {
        val recipientID = packet.recipientID

        if (recipientID == null) {
            return false
        }

        val broadcastRecipient = delegate?.getBroadcastRecipient()
        if (broadcastRecipient != null && recipientID.contentEquals(broadcastRecipient)) {
            return false
        }

        val recipientIDString = recipientID.toHexString()
        return recipientIDString == myPeerID
    }

    private fun shouldRelayPacket(packet: BitchatPacket, fromPeerID: String): Boolean {

        if (packet.ttl >= 4u) {
            Log.d(TAG, "High TTL (${packet.ttl}), relaying")
            return true
        }

        val networkSize = delegate?.getNetworkSize() ?: 1

        if (networkSize <= 3) {
            Log.d(TAG, "Small network (${networkSize} peers), relaying")
            return true
        }

        val relayProb = when {
            networkSize <= 10 -> 1.0
            networkSize <= 30 -> 0.85
            networkSize <= 50 -> 0.7
            networkSize <= 100 -> 0.55
            else -> 0.4
        }

        val shouldRelay = Random.nextDouble() < relayProb
        Log.d(TAG, "Network size: ${networkSize}, Relay probability: ${relayProb}, Decision: ${shouldRelay}")

        return shouldRelay
    }

    private fun relayPacket(routed: RoutedPacket) {
        Log.d(TAG, "Relaying packet type ${routed.packet.type} with TTL ${routed.packet.ttl}")
        delegate?.broadcastPacket(routed)
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Relay Manager Debug Info ===")
            appendLine("Relay Scope Active: ${relayScope.isActive}")
            appendLine("My Peer ID: ${myPeerID}")
            appendLine("Network Size: ${delegate?.getNetworkSize() ?: "unknown"}")
        }
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down PacketRelayManager")
        relayScope.cancel()
    }
}

interface PacketRelayManagerDelegate {

    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray

    fun broadcastPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}

private fun hexStringToPeerBytes(hex: String): ByteArray {
    val result = ByteArray(8)
    var idx = 0
    var out = 0
    while (idx + 1 < hex.length && out < 8) {
        val b = hex.substring(idx, idx + 2).toIntOrNull(16)?.toByte() ?: 0
        result[out++] = b
        idx += 2
    }
    return result
}
