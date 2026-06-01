package com.cybersiren.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BitchatFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray
) {
    private enum class TLVType(val v: UByte) {
        FILE_NAME(0x01u), FILE_SIZE(0x02u), MIME_TYPE(0x03u), CONTENT(0x04u);
        companion object { fun from(value: UByte) = values().find { it.v == value } }
    }

    fun encode(): ByteArray? {
        try {
            android.util.Log.d("BitchatFilePacket", "Encoding: name=$fileName, size=$fileSize, mime=$mimeType")
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)

        if (nameBytes.size > 0xFFFF || mimeBytes.size > 0xFFFF) {
                android.util.Log.e("BitchatFilePacket", "TLV field too large: name=${nameBytes.size}, mime=${mimeBytes.size} (max: 65535)")
                return null
            }
            if (content.size > 0xFFFF) {
                android.util.Log.d("BitchatFilePacket", "Content exceeds 65535 bytes (${content.size}); will be split into multiple CONTENT TLVs")
            } else {
                android.util.Log.d("BitchatFilePacket", "TLV sizes OK: name=${nameBytes.size}, mime=${mimeBytes.size}, content=${content.size}")
            }
        val sizeFieldLen = 4
        val contentLenFieldLen = 4

        val contentTLVBytes = 1 + contentLenFieldLen + content.size
        val capacity = (1 + 2 + nameBytes.size) + (1 + 2 + sizeFieldLen) + (1 + 2 + mimeBytes.size) + contentTLVBytes
        val buf = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)

        buf.put(TLVType.FILE_NAME.v.toByte())
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)

        buf.put(TLVType.FILE_SIZE.v.toByte())
        buf.putShort(sizeFieldLen.toShort())
        buf.putInt(fileSize.toInt())

        buf.put(TLVType.MIME_TYPE.v.toByte())
        buf.putShort(mimeBytes.size.toShort())
        buf.put(mimeBytes)

        buf.put(TLVType.CONTENT.v.toByte())
        buf.putInt(content.size)
        buf.put(content)

        val result = buf.array()
            android.util.Log.d("BitchatFilePacket", "Encoded successfully: ${result.size} bytes total")
            return result
        } catch (e: Exception) {
            android.util.Log.e("BitchatFilePacket", "Encoding failed: ${e.message}", e)
            return null
        }
    }

    companion object {
        fun decode(data: ByteArray): BitchatFilePacket? {
            android.util.Log.d("BitchatFilePacket", "Decoding ${data.size} bytes")
            try {
                var off = 0
                var name: String? = null
                var size: Long? = null
                var mime: String? = null
                var contentBytes: ByteArray? = null
                while (off + 3 <= data.size) {
                    val t = TLVType.from(data[off].toUByte()) ?: return null
                    off += 1

                    val len: Int
                    if (t == TLVType.CONTENT) {
                        if (off + 4 > data.size) return null
                        len = ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                        off += 4
                    } else {
                        if (off + 2 > data.size) return null
                        len = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                        off += 2
                    }
                    if (len < 0 || off + len > data.size) return null
                    val value = data.copyOfRange(off, off + len)
                    off += len
                    when (t) {
                        TLVType.FILE_NAME -> name = String(value, Charsets.UTF_8)
                        TLVType.FILE_SIZE -> {
                            if (len != 4) return null
                            val bb = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
                            size = bb.int.toLong()
                        }
                        TLVType.MIME_TYPE -> mime = String(value, Charsets.UTF_8)
                        TLVType.CONTENT -> {

                            if (contentBytes == null) contentBytes = value else {

                                contentBytes = (contentBytes!! + value)
                            }
                        }
                    }
                }
                val n = name ?: return null
                val c = contentBytes ?: return null
                val s = size ?: c.size.toLong()
                val m = mime ?: "application/octet-stream"
                val result = BitchatFilePacket(n, s, m, c)
                android.util.Log.d("BitchatFilePacket", "Decoded: name=$n, size=$s, mime=$m, content=${c.size} bytes")
                return result
            } catch (e: Exception) {
                android.util.Log.e("BitchatFilePacket", "Decoding failed: ${e.message}", e)
                return null
            }
        }
    }
}
