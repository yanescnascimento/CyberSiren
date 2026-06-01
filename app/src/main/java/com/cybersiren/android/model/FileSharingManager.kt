package com.cybersiren.android.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cybersiren.android.features.file.FileUtils
import java.io.File

object FileSharingManager {

    private const val TAG = "FileSharingManager"

    fun createFilePacketFromUri(
        context: Context,
        uri: Uri,
        originalName: String? = null
    ): BitchatFilePacket? {
        return try {

            val fileName = originalName ?: getFileNameFromUri(context, uri) ?: "unknown_file"

            val localPath = FileUtils.copyFileForSending(context, uri) ?: return null

            val mimeType = FileUtils.getMimeTypeFromExtension(fileName)

            val file = File(localPath)
            val content = file.readBytes()
            val fileSize = file.length()

            file.delete()

            val packet = BitchatFilePacket(
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                content = content
            )

            Log.d(TAG, "Created file packet: name=$fileName, size=${FileUtils.formatFileSize(fileSize)}, mime=$mimeType")
            packet

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create file packet from URI", e)
            null
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get filename from URI", e)
            uri.lastPathSegment
        }
    }

    data class ReceivedFileInfo(
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val content: ByteArray
    )

    fun processReceivedFile(packet: BitchatFilePacket): ReceivedFileInfo {
        return ReceivedFileInfo(
            fileName = packet.fileName,
            fileSize = packet.fileSize,
            mimeType = packet.mimeType,
            content = packet.content
        )
    }
}
