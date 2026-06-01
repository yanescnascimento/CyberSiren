package com.cybersiren.android.model

import com.cybersiren.android.protocol.MessageType

data class FragmentPayload(
    val fragmentID: ByteArray,
    val index: Int,
    val total: Int,
    val originalType: UByte,
    val data: ByteArray
) {

    companion object {
        const val HEADER_SIZE = 13
        const val FRAGMENT_ID_SIZE = 8

        fun decode(payloadData: ByteArray): FragmentPayload? {
            if (payloadData.size < HEADER_SIZE) {
                return null
            }

            try {

                val fragmentID = payloadData.sliceArray(0..<FRAGMENT_ID_SIZE)

                val index = ((payloadData[8].toInt() and 0xFF) shl 8) or
                           (payloadData[9].toInt() and 0xFF)

                val total = ((payloadData[10].toInt() and 0xFF) shl 8) or
                           (payloadData[11].toInt() and 0xFF)

                val originalType = payloadData[12].toUByte()

                val data = if (payloadData.size > HEADER_SIZE) {
                    payloadData.sliceArray(HEADER_SIZE..<payloadData.size)
                } else {
                    ByteArray(0)
                }

                return FragmentPayload(fragmentID, index, total, originalType, data)

            } catch (e: Exception) {
                return null
            }
        }

        fun generateFragmentID(): ByteArray {
            val fragmentID = ByteArray(FRAGMENT_ID_SIZE)
            kotlin.random.Random.nextBytes(fragmentID)
            return fragmentID
        }
    }

    fun encode(): ByteArray {
        val payload = ByteArray(HEADER_SIZE + data.size)

        System.arraycopy(fragmentID, 0, payload, 0, FRAGMENT_ID_SIZE)

        payload[8] = ((index shr 8) and 0xFF).toByte()
        payload[9] = (index and 0xFF).toByte()

        payload[10] = ((total shr 8) and 0xFF).toByte()
        payload[11] = (total and 0xFF).toByte()

        payload[12] = originalType.toByte()

        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, payload, HEADER_SIZE, data.size)
        }

        return payload
    }

    fun getFragmentIDString(): String {
        return fragmentID.joinToString("") { "%02x".format(it) }
    }

    fun isValid(): Boolean {
        return fragmentID.size == FRAGMENT_ID_SIZE &&
               index >= 0 &&
               total > 0 &&
               index < total &&
               data.isNotEmpty()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FragmentPayload

        if (!fragmentID.contentEquals(other.fragmentID)) return false
        if (index != other.index) return false
        if (total != other.total) return false
        if (originalType != other.originalType) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fragmentID.contentHashCode()
        result = 31 * result + index
        result = 31 * result + total
        result = 31 * result + originalType.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "FragmentPayload(fragmentID=${getFragmentIDString()}, index=$index, total=$total, originalType=$originalType, dataSize=${data.size})"
    }
}
