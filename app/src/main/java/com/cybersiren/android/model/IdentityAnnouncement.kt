package com.cybersiren.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.cybersiren.android.util.*

@Parcelize
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,
    val signingPublicKey: ByteArray
) : Parcelable {

    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u);

        companion object {
            fun fromValue(value: UByte): TLVType? {
                return values().find { it.value == value }
            }
        }
    }

    fun encode(): ByteArray? {
        val nicknameData = nickname.toByteArray(Charsets.UTF_8)

        if (nicknameData.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) {
            return null
        }

        val result = mutableListOf<Byte>()

        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())

        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(noisePublicKey.size.toByte())
        result.addAll(noisePublicKey.toList())

        result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte())
        result.add(signingPublicKey.size.toByte())
        result.addAll(signingPublicKey.toList())

        return result.toByteArray()
    }

    companion object {

        fun decode(data: ByteArray): IdentityAnnouncement? {

            val dataCopy = data.copyOf()

            var offset = 0
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null

            while (offset + 2 <= dataCopy.size) {

                val typeValue = dataCopy[offset].toUByte()
                val type = TLVType.fromValue(typeValue)
                offset += 1

                val length = dataCopy[offset].toUByte().toInt()
                offset += 1

                if (offset + length > dataCopy.size) return null

                val value = dataCopy.sliceArray(offset until offset + length)
                offset += length

                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = String(value, Charsets.UTF_8)
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        noisePublicKey = value
                    }
                    TLVType.SIGNING_PUBLIC_KEY -> {
                        signingPublicKey = value
                    }
                    null -> {

                        continue
                    }
                }
            }

            return if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
                IdentityAnnouncement(nickname, noisePublicKey, signingPublicKey)
            } else {
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IdentityAnnouncement

        if (nickname != other.nickname) return false
        if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "IdentityAnnouncement(nickname='$nickname', noisePublicKey=${noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., signingPublicKey=${signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}...)"
    }
}
