package com.cybersiren.android.mesh

import android.util.Log
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.protocol.MessageType
import com.cybersiren.android.protocol.MessagePadding
import com.cybersiren.android.model.FragmentPayload
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class FragmentManager {

    companion object {
        private const val TAG = "FragmentManager"

        private const val FRAGMENT_SIZE_THRESHOLD = com.cybersiren.android.util.AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD
        private const val MAX_FRAGMENT_SIZE = com.cybersiren.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE
        private const val FRAGMENT_TIMEOUT = com.cybersiren.android.util.AppConstants.Fragmentation.FRAGMENT_TIMEOUT_MS
        private const val CLEANUP_INTERVAL = com.cybersiren.android.util.AppConstants.Fragmentation.CLEANUP_INTERVAL_MS
    }

    private val incomingFragments = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()

    private val fragmentMetadata = ConcurrentHashMap<String, Triple<UByte, Int, Long>>()

    var delegate: FragmentManagerDelegate? = null

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startPeriodicCleanup()
    }

    fun createFragments(packet: BitchatPacket): List<BitchatPacket> {
        try {
            Log.d(TAG, "Creating fragments for packet type ${packet.type}, payload: ${packet.payload.size} bytes")
        val encoded = packet.toBinaryData()
            if (encoded == null) {
                Log.e(TAG, "Failed to encode packet to binary data")
                return emptyList()
            }
            Log.d(TAG, "Encoded to ${encoded.size} bytes")

        val fullData = try {
                MessagePadding.unpad(encoded)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unpad data: ${e.message}", e)
                return emptyList()
            }
            Log.d(TAG, "Unpadded to ${fullData.size} bytes")

        if (fullData.size <= FRAGMENT_SIZE_THRESHOLD) {
            return listOf(packet)
        }

        val fragments = mutableListOf<BitchatPacket>()

        val fragmentID = FragmentPayload.generateFragmentID()

        val hasRoute = packet.route != null
        val version = if (hasRoute) 2 else 1
        val headerSize = if (version == 2) 15 else 13
        val senderSize = 8
        val recipientSize = if (packet.recipientID != null) 8 else 0

        val routeSize = if (hasRoute) (1 + (packet.route?.size ?: 0) * 8) else 0
        val fragmentHeaderSize = 13
        val paddingBuffer = 16

        val packetOverhead = headerSize + senderSize + recipientSize + routeSize + fragmentHeaderSize + paddingBuffer
        val maxDataSize = (512 - packetOverhead).coerceAtMost(MAX_FRAGMENT_SIZE)

        if (maxDataSize <= 0) {
            Log.e(TAG, "Calculated maxDataSize is non-positive ($maxDataSize). Route too large?")
            return emptyList()
        }

        Log.d(TAG, "Dynamic fragment size: $maxDataSize (MAX: $MAX_FRAGMENT_SIZE, Overhead: $packetOverhead)")

        val fragmentChunks = stride(0, fullData.size, maxDataSize) { offset ->
            val endOffset = minOf(offset + maxDataSize, fullData.size)
            fullData.sliceArray(offset..<endOffset)
        }

        Log.d(TAG, "Creating ${fragmentChunks.size} fragments for ${fullData.size} byte packet (iOS compatible)")

        for (index in fragmentChunks.indices) {
            val fragmentData = fragmentChunks[index]

            val fragmentPayload = FragmentPayload(
                fragmentID = fragmentID,
                index = index,
                total = fragmentChunks.size,
                originalType = packet.type,
                data = fragmentData
            )

            val fragmentPacket = BitchatPacket(
                version = if (packet.route != null) 2u else 1u,
                type = MessageType.FRAGMENT.value,
                ttl = packet.ttl,
                senderID = packet.senderID,
                recipientID = packet.recipientID,
                timestamp = packet.timestamp,
                payload = fragmentPayload.encode(),
                route = packet.route,
                signature = null
            )

            fragments.add(fragmentPacket)
        }

        Log.d(TAG, "Created ${fragments.size} fragments successfully")
            return fragments
        } catch (e: Exception) {
            Log.e(TAG, "Fragment creation failed: ${e.message}", e)
            Log.e(TAG, "Packet type: ${packet.type}, payload: ${packet.payload.size} bytes")
            return emptyList()
        }
    }

    fun handleFragment(packet: BitchatPacket): BitchatPacket? {

        if (packet.payload.size < FragmentPayload.HEADER_SIZE) {
            Log.w(TAG, "Fragment packet too small: ${packet.payload.size}")
            return null
        }

        try {

            val fragmentPayload = FragmentPayload.decode(packet.payload)
            if (fragmentPayload == null || !fragmentPayload.isValid()) {
                Log.w(TAG, "Invalid fragment payload")
                return null
            }

            val fragmentIDString = fragmentPayload.getFragmentIDString()

            Log.d(TAG, "Received fragment ${fragmentPayload.index}/${fragmentPayload.total} for fragmentID: $fragmentIDString, originalType: ${fragmentPayload.originalType}")

            if (!incomingFragments.containsKey(fragmentIDString)) {
                incomingFragments[fragmentIDString] = mutableMapOf()
                fragmentMetadata[fragmentIDString] = Triple(
                    fragmentPayload.originalType,
                    fragmentPayload.total,
                    System.currentTimeMillis()
                )
            }

            incomingFragments[fragmentIDString]?.put(fragmentPayload.index, fragmentPayload.data)

            val fragmentMap = incomingFragments[fragmentIDString]
            if (fragmentMap != null && fragmentMap.size == fragmentPayload.total) {
                Log.d(TAG, "All fragments received for $fragmentIDString, reassembling...")

                val reassembledData = mutableListOf<Byte>()
                for (i in 0 until fragmentPayload.total) {
                    fragmentMap[i]?.let { data ->
                        reassembledData.addAll(data.asIterable())
                    }
                }

                val originalPacket = BitchatPacket.fromBinaryData(reassembledData.toByteArray())
                if (originalPacket != null) {

                    incomingFragments.remove(fragmentIDString)
                    fragmentMetadata.remove(fragmentIDString)

                    val suppressedTtlPacket = originalPacket.copy(ttl = 0u.toUByte())
                    Log.d(TAG, "Successfully reassembled original (${reassembledData.size} bytes); set TTL=0 to suppress relay")
                    return suppressedTtlPacket
                } else {
                    val metadata = fragmentMetadata[fragmentIDString]
                    Log.e(TAG, "Failed to decode reassembled packet (type=${metadata?.first}, total=${metadata?.second})")
                }
            } else {
                val received = fragmentMap?.size ?: 0
                Log.d(TAG, "Fragment ${fragmentPayload.index} stored, have $received/${fragmentPayload.total} fragments for $fragmentIDString")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }

        return null
    }

    private fun <T> stride(from: Int, to: Int, by: Int, transform: (Int) -> T): List<T> {
        val result = mutableListOf<T>()
        var current = from
        while (current < to) {
            result.add(transform(current))
            current += by
        }
        return result
    }

    private fun cleanupOldFragments() {
        val now = System.currentTimeMillis()
        val cutoff = now - FRAGMENT_TIMEOUT

        val oldFragments = fragmentMetadata.filter { it.value.third < cutoff }.map { it.key }

        for (fragmentID in oldFragments) {
            incomingFragments.remove(fragmentID)
            fragmentMetadata.remove(fragmentID)
        }

        if (oldFragments.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${oldFragments.size} old fragment sets (iOS compatible)")
        }
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Fragment Manager Debug Info (iOS Compatible) ===")
            appendLine("Active Fragment Sets: ${incomingFragments.size}")
            appendLine("Fragment Size Threshold: $FRAGMENT_SIZE_THRESHOLD bytes")
            appendLine("Max Fragment Size: $MAX_FRAGMENT_SIZE bytes")

            fragmentMetadata.forEach { (fragmentID, metadata) ->
                val (originalType, totalFragments, timestamp) = metadata
                val received = incomingFragments[fragmentID]?.size ?: 0
                val ageSeconds = (System.currentTimeMillis() - timestamp) / 1000
                appendLine("  - $fragmentID: $received/$totalFragments fragments, type: $originalType, age: ${ageSeconds}s")
            }
        }
    }

    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldFragments()
            }
        }
    }

    fun clearAllFragments() {
        incomingFragments.clear()
        fragmentMetadata.clear()
    }

    fun shutdown() {
        managerScope.cancel()
        clearAllFragments()
    }
}

interface FragmentManagerDelegate {
    fun onPacketReassembled(packet: BitchatPacket)
}
