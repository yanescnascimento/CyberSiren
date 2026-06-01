package com.cybersiren.android.v2v.ui.sender

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.R
import com.cybersiren.android.v2v.model.VehicleType
import com.cybersiren.android.v2v.ui.V2VColors
import com.cybersiren.android.v2v.ui.vehicleLabel
import com.cybersiren.android.v2v.ui.components.EmergencyButton
import com.cybersiren.android.v2v.ui.components.StatusDisplay
import com.cybersiren.android.v2v.ui.components.VehicleSelector

@Composable
fun SenderModeScreen(
    isEmergencyActive: Boolean,
    selectedVehicleType: VehicleType,
    currentLocation: Location?,
    currentSpeed: Float,
    currentHeading: Float,
    connectedPeers: Int,
    onVehicleTypeSelected: (VehicleType) -> Unit,
    onEmergencyToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = V2VColors.accentFor(selectedVehicleType)
    val vehicleName = vehicleLabel(selectedVehicleType)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(V2VColors.BackgroundLight)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = if (isEmergencyActive) stringResource(R.string.v2v_sender_title_active) else stringResource(R.string.v2v_sender_title_ready),
            color = V2VColors.Ink,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )

        val subtitle = buildAnnotatedString {
            if (isEmergencyActive) {
                append(stringResource(R.string.v2v_sender_subtitle_active_prefix))
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append(vehicleName.lowercase())
                }
                append(stringResource(R.string.v2v_sender_subtitle_active_suffix))
            } else {
                append(stringResource(R.string.v2v_sender_subtitle_ready_prefix))
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append(vehicleName)
                }
                append(stringResource(R.string.v2v_sender_subtitle_ready_suffix))
            }
        }
        Text(
            text = subtitle,
            color = V2VColors.Muted,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
        )

        VehicleSelector(
            selectedType = selectedVehicleType,
            onTypeSelected = onVehicleTypeSelected,
            enabled = !isEmergencyActive,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            EmergencyButton(
                isActive = isEmergencyActive,
                vehicleType = selectedVehicleType,
                onClick = onEmergencyToggle,
                enabled = true
            )
        }

        Spacer(Modifier.weight(1f))

        StatusDisplay(
            location = currentLocation,
            speed = currentSpeed,
            heading = currentHeading,
            connectedPeers = connectedPeers,
            accentColor = accent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )
    }
}
