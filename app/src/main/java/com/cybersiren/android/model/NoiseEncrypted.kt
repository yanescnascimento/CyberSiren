package com.cybersiren.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class NoisePayloadType(val value: UByte) {
    PRIVATE_MESSAGE(0x01u),
    READ_RECEIPT(0x02u),
    DELIVERED(0x03u),
    VERIFY_CHALLENGE(0x10u),
    VERIFY_RESPONSE(0x11u),
    FILE_TRANSFER(0x20u);

    companion object {
        fun fromValue(value: UByte): NoisePayloadType? {
            return values().find { it.value == value }
        }
    }
}

@Parcelize
data class NoisePayload(
    val type: NoisePayloadType,
    val data: ByteArray
) : Parcelable {

    fun encode(): ByteArray {
        val result = ByteArray(1 + data.size)
        result[0] = type.value.toByte()
        data.copyInto(result, destinationOffset = 1)
        return result
    }

    companion object {

        fun decode(data: ByteArray): NoisePayload? {
            if (data.isEmpty()) return null

            val typeValue = data[0].toUByte()
            val type = NoisePayloadType.fromValue(typeValue) ?: return null

            val payloadData = if (data.size > 1) {
                data.copyOfRange(1, data.size)
            } else {
                ByteArray(0)
            }

            return NoisePayload(type, payloadData)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NoisePayload

        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

@Parcelize
data class PrivateMessagePacket(
    val messageID: String,
    val content: String
) : Parcelable {

    private enum class TLVType(val value: UByte) {
        MESSAGE_ID(0x00u),
        CONTENT(0x01u);

        companion object {
            fun fromValue(value: UByte): TLVType? {
                return values().find { it.value == value }
            }
        }
    }

    fun encode(): ByteArray? {
        val messageIDData = messageID.toByteArray(Charsets.UTF_8)
        val contentData = content.toByteArray(Charsets.UTF_8)

        if (messageIDData.size > 255 || contentData.size > 255) {
            return null
        }

        val result = mutableListOf<Byte>()

        result.add(TLVType.MESSAGE_ID.value.toByte())
        result.add(messageIDData.size.toByte())
        result.addAll(messageIDData.toList())

        result.add(TLVType.CONTENT.value.toByte())
        result.add(contentData.size.toByte())
        result.addAll(contentData.toList())

        return result.toByteArray()
    }

    companion object {

        fun decode(data: ByteArray): PrivateMessagePacket? {
            var offset = 0
            var messageID: String? = null
            var content: String? = null

            while (offset + 2 <= data.size) {

                val typeValue = data[offset].toUByte()
                val type = TLVType.fromValue(typeValue) ?: return null
                offset += 1

                val length = data[offset].toUByte().toInt()
                offset += 1

                if (offset + length > data.size) return null

                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    TLVType.MESSAGE_ID -> {
                        messageID = String(value, Charsets.UTF_8)
                    }
                    TLVType.CONTENT -> {
                        content = String(value, Charsets.UTF_8)
                    }
                }
            }

            return if (messageID != null && content != null) {
                PrivateMessagePacket(messageID, content)
            } else {
                null
            }
        }
    }

    override fun toString(): String {
        return "PrivateMessagePacket(messageID='$messageID', content='${content.take(50)}${if (content.length > 50) "..." else ""}')"
    }
}

@Parcelize
data class ReadReceipt(
    val originalMessageID: String,
    val readerPeerID: String? = null
) : Parcelable
