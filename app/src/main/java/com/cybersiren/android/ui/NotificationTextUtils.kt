package com.cybersiren.android.ui

import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.model.BitchatMessageType

object NotificationTextUtils {

    fun buildPrivateMessagePreview(message: BitchatMessage): String {
        return try {
            when (message.type) {
                BitchatMessageType.Image -> "sent an image"
                BitchatMessageType.Audio -> "sent a voice message"
                BitchatMessageType.File -> {

                    val name = try { java.io.File(message.content).name } catch (_: Exception) { null }
                    if (!name.isNullOrBlank()) {
                        val lower = name.lowercase()
                        val icon = when {
                            lower.endsWith(".pdf") -> ""
                            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> ""
                            lower.endsWith(".doc") || lower.endsWith(".docx") -> ""
                            lower.endsWith(".xls") || lower.endsWith(".xlsx") -> ""
                            lower.endsWith(".ppt") || lower.endsWith(".pptx") -> ""
                            else -> ""
                        }
                        "$icon $name"
                    } else {
                        "sent a file"
                    }
                }
                else -> message.content
            }
        } catch (_: Exception) {

            message.content
        }
    }
}
