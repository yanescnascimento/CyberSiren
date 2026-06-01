package com.cybersiren.android.util

import java.util.Arrays

data class ByteArrayWrapper(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ByteArrayWrapper
        return Arrays.equals(bytes, other.bytes)
    }

    override fun hashCode(): Int {
        return Arrays.hashCode(bytes)
    }

    fun toHexString(): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
