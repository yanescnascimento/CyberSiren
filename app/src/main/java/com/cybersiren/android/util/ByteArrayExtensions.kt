package com.cybersiren.android.util

fun ByteArray.toHexString(): String {
    return this.joinToString("") { "%02x".format(it) }
}
