package com.cybersiren.android.nostr

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class NostrEventDeduplicator(
    private val maxCapacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        private const val TAG = "NostrDeduplicator"
        private const val DEFAULT_CAPACITY = com.cybersiren.android.util.AppConstants.Nostr.DEFAULT_DEDUP_CAPACITY

        @Volatile
        private var INSTANCE: NostrEventDeduplicator? = null

        fun getInstance(): NostrEventDeduplicator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NostrEventDeduplicator().also { INSTANCE = it }
            }
        }
    }

    private data class LRUNode(
        val eventId: String,
        var prev: LRUNode? = null,
        var next: LRUNode? = null
    )

    private val nodeMap = ConcurrentHashMap<String, LRUNode>()

    private val head = LRUNode("HEAD")
    private val tail = LRUNode("TAIL")

    private val lruLock = Any()

    @Volatile
    private var totalChecks = 0L
    @Volatile
    private var duplicateCount = 0L
    @Volatile
    private var evictionCount = 0L

    init {

        head.next = tail
        tail.prev = head

        Log.d(TAG, "Initialized NostrEventDeduplicator with capacity: $maxCapacity")
    }

    fun isDuplicate(eventId: String): Boolean {
        totalChecks++

        synchronized(lruLock) {
            val existingNode = nodeMap[eventId]

            if (existingNode != null) {

                moveToFront(existingNode)
                duplicateCount++

                if (duplicateCount % 100 == 0L) {
                    Log.v(TAG, "Duplicate event detected: $eventId (${duplicateCount} total duplicates)")
                }

                return true
            } else {

                addToFront(eventId)

                if (nodeMap.size > maxCapacity) {
                    evictOldest()
                }

                return false
            }
        }
    }

    fun processEvent(event: NostrEvent, processor: (NostrEvent) -> Unit): Boolean {
        return if (!isDuplicate(event.id)) {
            processor(event)
            true
        } else {
            false
        }
    }

    fun getStats(): DeduplicationStats {
        synchronized(lruLock) {
            return DeduplicationStats(
                capacity = maxCapacity,
                currentSize = nodeMap.size,
                totalChecks = totalChecks,
                duplicateCount = duplicateCount,
                evictionCount = evictionCount,
                hitRate = if (totalChecks > 0) (duplicateCount.toDouble() / totalChecks.toDouble()) else 0.0
            )
        }
    }

    fun clear() {
        synchronized(lruLock) {
            nodeMap.clear()
            head.next = tail
            tail.prev = head

            totalChecks = 0L
            duplicateCount = 0L
            evictionCount = 0L

            Log.d(TAG, "Cleared all cached event IDs")
        }
    }

    fun contains(eventId: String): Boolean {
        return nodeMap.containsKey(eventId)
    }

    fun size(): Int = nodeMap.size

    private fun addToFront(eventId: String) {
        val newNode = LRUNode(eventId)
        nodeMap[eventId] = newNode

        newNode.next = head.next
        newNode.prev = head
        head.next?.prev = newNode
        head.next = newNode
    }

    private fun moveToFront(node: LRUNode) {

        node.prev?.next = node.next
        node.next?.prev = node.prev

        node.next = head.next
        node.prev = head
        head.next?.prev = node
        head.next = node
    }

    private fun removeTail(): LRUNode? {
        val lastNode = tail.prev
        if (lastNode == head) {
            return null
        }

        lastNode?.prev?.next = tail
        tail.prev = lastNode?.prev

        if (lastNode != null) {
            nodeMap.remove(lastNode.eventId)
        }

        return lastNode
    }

    private fun evictOldest() {
        while (nodeMap.size > maxCapacity) {
            val evictedNode = removeTail()
            if (evictedNode != null) {
                evictionCount++

                if (evictionCount % 500 == 0L) {
                    Log.v(TAG, "Evicted event ID: ${evictedNode.eventId} (${evictionCount} total evictions)")
                }
            } else {
                break
            }
        }
    }
}

data class DeduplicationStats(
    val capacity: Int,
    val currentSize: Int,
    val totalChecks: Long,
    val duplicateCount: Long,
    val evictionCount: Long,
    val hitRate: Double
) {
    override fun toString(): String {
        return "DeduplicationStats(capacity=$capacity, size=$currentSize, " +
               "checks=$totalChecks, duplicates=$duplicateCount, evictions=$evictionCount, " +
               "hitRate=${"%.2f".format(hitRate * 100)}%)"
    }
}
