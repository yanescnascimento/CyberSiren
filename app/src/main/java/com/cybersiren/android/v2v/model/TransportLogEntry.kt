package com.cybersiren.android.v2v.model

import java.util.UUID

enum class TransportType(val label: String) {
    BLE("BLE Mesh"),
    FIREBASE("Firebase Cloud")
}

enum class TransportDirection(val symbol: String) {
    SEND("↑"),
    RECEIVE("↓")
}

data class TransportLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val transport: TransportType,
    val direction: TransportDirection,
    val messageId: String,
    val latencyMs: Long? = null,
    val success: Boolean = true,
    val payloadBytes: Int = 0,
    val details: String = ""
) {

    fun ageText(now: Long = System.currentTimeMillis()): String {
        val diff = (now - timestamp) / 1000
        return when {
            diff < 5 -> "agora"
            diff < 60 -> "${diff}s atrás"
            diff < 3600 -> "${diff / 60}m atrás"
            else -> "${diff / 3600}h atrás"
        }
    }
}
