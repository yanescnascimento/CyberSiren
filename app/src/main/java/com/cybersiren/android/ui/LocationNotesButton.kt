package com.cybersiren.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cybersiren.android.R
import com.cybersiren.android.geohash.ChannelID
import com.cybersiren.android.geohash.LocationChannelManager
import com.cybersiren.android.nostr.LocationNotesManager

@Composable
fun LocationNotesButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val permissionState by locationManager.permissionState.collectAsStateWithLifecycle()
    val locationServicesEnabled by locationManager.effectiveLocationEnabled.collectAsStateWithLifecycle(false)

    val locationPermissionGranted = permissionState == LocationChannelManager.PermissionState.AUTHORIZED
    val locationEnabled = locationPermissionGranted && locationServicesEnabled

    val notesManager = remember { LocationNotesManager.getInstance() }
    val notes by notesManager.notes.collectAsStateWithLifecycle()
    val notesCount = notes.size

    if (selectedLocationChannel is ChannelID.Mesh && locationEnabled) {
        val hasNotes = notesCount > 0
        IconButton(
            onClick = onClick,
            modifier = modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = stringResource(R.string.cd_location_notes),
                modifier = Modifier.size(16.dp),
                tint = if (hasNotes) colorScheme.primary else Color.Gray
            )
        }
    }
}
