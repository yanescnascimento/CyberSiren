package com.cybersiren.android.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cybersiren.android.features.file.FileUtils
import com.cybersiren.android.model.BitchatFilePacket

@Composable
fun FileMessageItem(
    packet: BitchatFilePacket,
    onFileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth(0.8f)
            .clickable { showDialog = true },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = stringResource(com.cybersiren.android.R.string.cd_file),
                tint = getFileIconColor(packet.fileName),
                modifier = Modifier.size(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {

                    Text(
                        text = packet.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = FileUtils.formatFileSize(packet.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FileTypeBadge(mimeType = packet.mimeType)
                }
            }
        }
    }

    if (showDialog) {
        FileViewerDialog(
            packet = packet,
            onDismiss = { showDialog = false },
            onSaveToDevice = { content, fileName ->

                android.util.Log.d("FileSharing", "Would save file: $fileName")
            }
        )
    }
}

@Composable
private fun FileTypeBadge(mimeType: String) {
    val (text, color) = when {
        mimeType.startsWith("application/pdf") -> "PDF" to Color(0xFFDC2626)
        mimeType.startsWith("text/") -> "TXT" to Color(0xFF059669)
        mimeType.startsWith("image/") -> "IMG" to Color(0xFF7C3AED)
        mimeType.startsWith("audio/") -> "AUD" to Color(0xFFEA580C)
        mimeType.startsWith("video/") -> "VID" to Color(0xFF2563EB)
        mimeType.contains("document") -> "DOC" to Color(0xFF1D4ED8)
        mimeType.contains("zip") || mimeType.contains("rar") -> "ZIP" to Color(0xFF7C2D12)
        else -> "FILE" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
    )
}

private fun getFileIconColor(fileName: String): Color {
    val extension = fileName.substringAfterLast(".", "").lowercase()
    return when (extension) {
        "pdf" -> Color(0xFFDC2626)
        "doc", "docx" -> Color(0xFF1D4ED8)
        "xls", "xlsx" -> Color(0xFF059669)
        "ppt", "pptx" -> Color(0xFFEA580C)
        "txt", "json", "xml" -> Color(0xFF7C3AED)
        "jpg", "png", "gif", "webp" -> Color(0xFF2563EB)
        "mp3", "wav", "m4a" -> Color(0xFFEA580C)
        "mp4", "avi", "mov" -> Color(0xFFDC2626)
        "zip", "rar", "7z" -> Color(0xFF7C2D12)
        else -> Color(0xFF6B7280)
    }
}
