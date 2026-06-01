package com.cybersiren.android.protocol

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = com.cybersiren.android.util.AppConstants.Protocol.COMPRESSION_THRESHOLD_BYTES

    fun shouldCompress(data: ByteArray): Boolean {

        if (data.size < COMPRESSION_THRESHOLD) return false

        val byteFrequency = mutableMapOf<Byte, Int>()
        for (byte in data) {
            byteFrequency[byte] = (byteFrequency[byte] ?: 0) + 1
        }

        val uniqueByteRatio = byteFrequency.size.toDouble() / minOf(data.size, 256).toDouble()
        return uniqueByteRatio < 0.9
    }

    fun compress(data: ByteArray): ByteArray? {

        if (data.size < COMPRESSION_THRESHOLD) return null

        try {

            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(data)
            deflater.finish()

            val outputStream = ByteArrayOutputStream(data.size)
            val buffer = ByteArray(1024)

            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                outputStream.write(buffer, 0, count)
            }
            deflater.end()

            val compressedData = outputStream.toByteArray()

            return if (compressedData.size > 0 && compressedData.size < data.size) {
                compressedData
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {

        try {
            val inflater = Inflater(true)
            inflater.setInput(compressedData)

            val decompressedBuffer = ByteArray(originalSize)
            val actualSize = inflater.inflate(decompressedBuffer)
            inflater.end()

            return if (actualSize == originalSize) {
                decompressedBuffer
            } else if (actualSize > 0) {

                decompressedBuffer.copyOfRange(0, actualSize)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d("CompressionUtil", "Raw deflate decompression failed: ${e.message}, trying with zlib headers...")

            try {
                val inflater = Inflater(false)
                inflater.setInput(compressedData)

                val decompressedBuffer = ByteArray(originalSize)
                val actualSize = inflater.inflate(decompressedBuffer)
                inflater.end()

                return if (actualSize == originalSize) {
                    decompressedBuffer
                } else if (actualSize > 0) {
                    decompressedBuffer.copyOfRange(0, actualSize)
                } else {
                    null
                }
            } catch (fallbackException: Exception) {
                Log.e("CompressionUtil", "Both raw deflate and zlib decompression failed: ${fallbackException.message}")
                return null
            }
        }
    }

    fun testCompression(): Boolean {
        try {

            val testMessage = "This is a test message that should compress well. ".repeat(10)
            val originalData = testMessage.toByteArray()

            Log.d("CompressionUtil", "Testing deflate compression with ${originalData.size} bytes")

            val shouldCompress = shouldCompress(originalData)
            Log.d("CompressionUtil", "shouldCompress() returned: $shouldCompress")

            if (!shouldCompress) {
                Log.e("CompressionUtil", "shouldCompress failed for test data")
                return false
            }

            val compressed = compress(originalData)
            if (compressed == null) {
                Log.e("CompressionUtil", "Compression failed")
                return false
            }

            Log.d("CompressionUtil", "Compressed ${originalData.size} bytes to ${compressed.size} bytes (${(compressed.size.toDouble() / originalData.size * 100).toInt()}%)")

            val decompressed = decompress(compressed, originalData.size)
            if (decompressed == null) {
                Log.e("CompressionUtil", "Decompression failed")
                return false
            }

            val isIdentical = originalData.contentEquals(decompressed)
            Log.d("CompressionUtil", "Data integrity check: $isIdentical")

            if (!isIdentical) {
                Log.e("CompressionUtil", "Decompressed data doesn't match original")
                return false
            }

            Log.i("CompressionUtil", "deflate compression test PASSED - ready for iOS compatibility")
            return true

        } catch (e: Exception) {
            Log.e("CompressionUtil", "deflate compression test failed: ${e.message}")
            return false
        }
    }
}
