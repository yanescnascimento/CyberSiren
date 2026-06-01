package com.cybersiren.android.nostr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.random.Random

object NostrProofOfWork {

    private const val TAG = "NostrProofOfWork"

    fun calculateDifficulty(eventIdHex: String): Int {
        var count = 0

        for (i in eventIdHex.indices) {
            val nibble = eventIdHex[i].toString().toInt(16)
            if (nibble == 0) {
                count += 4
            } else {

                count += when (nibble) {
                    1 -> 3
                    2, 3 -> 2
                    4, 5, 6, 7 -> 1
                    else -> 0
                }
                break
            }
        }

        return count
    }

    fun validateDifficulty(event: NostrEvent, minimumDifficulty: Int): Boolean {
        if (minimumDifficulty <= 0) return true

        if (!hasNonce(event)) {
            Log.w(TAG, "Event ${event.id.take(16)}... missing nonce tag; treating as no PoW")
            return false
        }

        val actualDifficulty = calculateDifficulty(event.id)
        val committedDifficulty = getCommittedDifficulty(event)

        Log.d(TAG, "Validating PoW: actual=$actualDifficulty, required=$minimumDifficulty, committed=$committedDifficulty")

        if (actualDifficulty < minimumDifficulty) {
            Log.w(TAG, "Event ${event.id.take(16)}... has insufficient difficulty: $actualDifficulty < $minimumDifficulty")
            return false
        }

        if (committedDifficulty != null && committedDifficulty < minimumDifficulty) {
            Log.w(TAG, "Event ${event.id.take(16)}... has committed difficulty $committedDifficulty but achieved $actualDifficulty (possible spam)")
            return false
        }

        return true
    }

    suspend fun mineEvent(
        event: NostrEvent,
        targetDifficulty: Int,
        maxIterations: Int = 1_000_000
    ): NostrEvent? = withContext(Dispatchers.Default) {
        if (targetDifficulty <= 0) return@withContext event

        Log.d(TAG, "Starting PoW mining for difficulty $targetDifficulty...")
        val startTime = System.currentTimeMillis()

        var nonce = Random.nextLong(0, 1_000_000).toString()
        var iterations = 0

        while (iterations < maxIterations) {

            val eventWithNonce = addNonceTag(event, nonce, targetDifficulty)

            val eventId = eventWithNonce.computeEventIdHex()
            val actualDifficulty = calculateDifficulty(eventId)

            if (actualDifficulty >= targetDifficulty) {
                val timeElapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "PoW mining successful! Difficulty: $actualDifficulty, iterations: $iterations, time: ${timeElapsed}ms")

                return@withContext eventWithNonce.copy(id = eventId)
            }

            nonce = (nonce.toLongOrNull()?.plus(1) ?: Random.nextLong()).toString()
            iterations++

            if (iterations % 100_000 == 0) {
                val timeElapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "PoW mining progress: $iterations iterations, ${timeElapsed}ms elapsed")
            }
        }

        val timeElapsed = System.currentTimeMillis() - startTime
        Log.w(TAG, "PoW mining failed after $maxIterations iterations (${timeElapsed}ms)")
        return@withContext null
    }

    private fun addNonceTag(event: NostrEvent, nonce: String, targetDifficulty: Int): NostrEvent {
        val newTags = event.tags.toMutableList()

        newTags.removeAll { tag -> tag.isNotEmpty() && tag[0] == "nonce" }

        newTags.add(listOf("nonce", nonce, targetDifficulty.toString()))

        val updatedCreatedAt = (System.currentTimeMillis() / 1000).toInt()

        return event.copy(
            tags = newTags,
            createdAt = updatedCreatedAt
        )
    }

    private fun getCommittedDifficulty(event: NostrEvent): Int? {
        val nonceTag = event.tags.find { tag ->
            tag.isNotEmpty() && tag[0] == "nonce" && tag.size >= 3
        }

        return nonceTag?.get(2)?.toIntOrNull()
    }

    fun hasNonce(event: NostrEvent): Boolean {
        return event.tags.any { tag -> tag.isNotEmpty() && tag[0] == "nonce" }
    }

    fun getNonce(event: NostrEvent): String? {
        val nonceTag = event.tags.find { tag ->
            tag.isNotEmpty() && tag[0] == "nonce" && tag.size >= 2
        }

        return nonceTag?.get(1)
    }

    fun estimateWork(difficulty: Int): Long {
        return if (difficulty <= 0) 1L else 1L shl difficulty
    }

    fun estimateMiningTime(difficulty: Int, hashesPerSecond: Int = 100_000): String {
        val estimatedHashes = estimateWork(difficulty)
        val estimatedSeconds = estimatedHashes / hashesPerSecond

        return when {
            estimatedSeconds < 1 -> "< 1 second"
            estimatedSeconds < 60 -> "${estimatedSeconds}s"
            estimatedSeconds < 3600 -> "${estimatedSeconds / 60}m ${estimatedSeconds % 60}s"
            estimatedSeconds < 86400 -> "${estimatedSeconds / 3600}h ${(estimatedSeconds % 3600) / 60}m"
            else -> "${estimatedSeconds / 86400}d ${(estimatedSeconds % 86400) / 3600}h"
        }
    }
}
