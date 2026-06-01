package com.cybersiren.android.sync

import com.cybersiren.android.protocol.BitchatPacket
import java.security.MessageDigest

object PacketIdUtil {
    fun computeIdBytes(packet: BitchatPacket): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(packet.type.toByte())
        md.update(packet.senderID)

        val ts = packet.timestamp.toLong()
        for (i in 7 downTo 0) {
            md.update(((ts ushr (i * 8)) and 0xFF).toByte())
        }
        md.update(packet.payload)
        val digest = md.digest()
        return digest.copyOf(16)
    }

    fun computeIdHex(packet: BitchatPacket): String {
        return computeIdBytes(packet).joinToString("") { b -> "%02x".format(b) }
    }
}
