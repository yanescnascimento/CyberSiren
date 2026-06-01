package com.cybersiren.android.ui.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cybersiren.android.R
import com.cybersiren.android.features.file.FileUtils

@Composable
fun FilePickerButton(
    modifier: Modifier = Modifier,
    onFileReady: (String) -> Unit
) {
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {

            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val path = FileUtils.copyFileForSending(context, uri)
            if (!path.isNullOrBlank()) onFileReady(path)
        }
    }

    IconButton(
        onClick = {

            filePicker.launch(arrayOf("*/*"))
        },
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Attachment,
            contentDescription = stringResource(R.string.cd_pick_file),
            tint = Color.Gray,
            modifier = Modifier.size(20.dp).rotate(90f)
        )
    }
}
