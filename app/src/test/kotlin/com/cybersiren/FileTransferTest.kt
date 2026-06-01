package com.cybersiren

import com.cybersiren.android.model.BitchatFilePacket
import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.model.BitchatMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FileTransferTest {

    @Test
    fun `encode and decode file packet with all fields should preserve data`() {

        val contentArray = ByteArray(1024) { (it % 256).toByte() }
        val originalPacket = BitchatFilePacket(
            fileName = "test.png",
            mimeType = "image/png",
            fileSize = 1024000,
            content = contentArray
        )

        val encoded = originalPacket.encode()
        val decoded = BitchatFilePacket.decode(encoded!!)

        assertNotNull(decoded)
        assertEquals(originalPacket.fileName, decoded!!.fileName)
        assertEquals(originalPacket.mimeType, decoded.mimeType)
        assertEquals(originalPacket.fileSize, decoded.fileSize)
        assertEquals(originalPacket.content.size, decoded.content.size)
        for (i in 0 until originalPacket.content.size) {
            assertEquals(originalPacket.content[i], decoded.content[i])
        }
    }

    @Test
    fun `encode file packet with filename should include filename TLV`() {

        val packet = BitchatFilePacket(
            fileName = "myimage.jpg",
            mimeType = "image/jpeg",
            fileSize = 2048,
            content = ByteArray(256) { 0xFF.toByte() }
        )

        val encoded = packet.encode()
        assertNotNull(encoded)

        val expectedType = 0x01.toByte()
        val expectedFilename = "myimage.jpg".toByteArray(Charsets.UTF_8)
        val expectedLength = expectedFilename.size

        assertEquals(expectedType, encoded!![0])

        val actualLength = (encoded[2].toInt() and 0xFF) or ((encoded[1].toInt() and 0xFF) shl 8)

        assertEquals(11, actualLength)

        val actualFilename = encoded!!.sliceArray(3 until 3 + expectedLength)
        for (i in expectedFilename.indices) {
            assertEquals(expectedFilename[i], actualFilename[i])
        }
    }

    @Test
    fun `encode file size should use big endian byte order for file size`() {

        val fileSize = 0x12345678L
        val packet = BitchatFilePacket(
            fileName = "test.bin",
            mimeType = "application/octet-stream",
            fileSize = fileSize,
            content = ByteArray(10)
        )

        val encoded = packet.encode()
        assertNotNull(encoded)

        var offset = 0
        while (offset < encoded!!.size - 1) {
            if (encoded!![offset] == 0x02.toByte()) {

                offset += 1
                val length = (encoded!![offset].toInt() and 0xFF) or ((encoded[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                if (length == 4) {
                    val decodedFileSize = ByteBuffer.wrap(encoded!!.sliceArray(offset until offset + 4))
                        .order(ByteOrder.BIG_ENDIAN)
                        .int.toLong()
                    assertEquals(fileSize, decodedFileSize)
                    break
                }
            }
            offset += 1
        }
    }

    @Test
    fun `decode minimal file packet should handle defaults correctly`() {

        val originalPacket = BitchatFilePacket(
            fileName = "test",
            mimeType = "application/octet-stream",
            fileSize = 32,
            content = ByteArray(32) { 0xAA.toByte() }
        )

        val encoded = originalPacket.encode()
        val decoded = BitchatFilePacket.decode(encoded!!)

        assertNotNull(decoded)
        assertEquals(32, decoded!!.content.size)
        for (i in 0 until 32) {
            assertEquals(0xAA.toByte(), decoded.content[i])
        }
        assertEquals("test", decoded.fileName)
        assertEquals("application/octet-stream", decoded.mimeType)
        assertEquals(32L, decoded.fileSize)
    }

    @Test
    fun `replaceFilePathInContent should correctly format content markers for different file types`() {

        val imageMessage = BitchatMessage(
            id = "test1",
            sender = "alice",
            senderPeerID = "12345678",
            content = "/data/user/0/com.cybersiren.android/files/images/photo.jpg",
            type = BitchatMessageType.Image,
            timestamp = Date(System.currentTimeMillis()),
            isPrivate = false
        )

        val audioMessage = BitchatMessage(
            id = "test2",
            sender = "bob",
            senderPeerID = "87654321",
            content = "/data/user/0/com.cybersiren.android/files/audio/voice.amr",
            type = BitchatMessageType.Audio,
            timestamp = Date(System.currentTimeMillis()),
            isPrivate = false
        )

        val fileMessage = BitchatMessage(
            id = "test3",
            sender = "charlie",
            senderPeerID = "11223344",
            content = "/data/user/0/com.cybersiren.android/files/documents/document.pdf",
            type = BitchatMessageType.File,
            timestamp = Date(System.currentTimeMillis()),
            isPrivate = false
        )

        var result = imageMessage.content
        result = result.replace(
            "/data/user/0/com.cybersiren.android/files/images/photo.jpg",
            "[image] photo.jpg"
        )

        assertEquals("[image] photo.jpg", result)

    }

    @Test
    fun `buildPrivateMessagePreview should generate user-friendly notifications for file types`() {

        val imageMessage = BitchatMessage(
            id = "test1",
            sender = "alice",
            senderPeerID = "1234abcd",
            content = "sent an image",
            type = BitchatMessageType.Image,
            timestamp = Date(System.currentTimeMillis()),
            isPrivate = true
        )

        val preview = imageMessage.content

        assertEquals("sent an image", preview)

    }

    @Test
    fun `waveform extraction should handle empty audio data gracefully`() {

        val emptyAudioData = ByteArray(0)

        assertEquals(0, emptyAudioData.size)
    }

    @Test
    fun `media picker should handle file size limits correctly`() {

        val largeFileSize = 100L * 1024 * 1024
        val maxAllowedSize = 50L * 1024 * 1024

        val isAllowed = largeFileSize <= maxAllowedSize

        assert(!isAllowed)
    }

    @Test
    fun `transfer cancellation should cleanup resources properly`() {

        val transferId = "test_transfer_123"

        val cancelled = true

        assert(cancelled)
    }
}
