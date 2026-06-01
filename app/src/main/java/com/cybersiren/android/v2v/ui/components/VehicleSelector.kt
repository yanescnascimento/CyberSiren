package com.cybersiren.android.v2v.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.R
import com.cybersiren.android.v2v.model.VehicleType
import com.cybersiren.android.v2v.ui.V2VColors
import com.cybersiren.android.v2v.ui.vehicleLabel

@Composable
fun VehicleSelector(
    selectedType: VehicleType,
    onTypeSelected: (VehicleType) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.v2v_vehicle_type_label),
            color = V2VColors.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VehicleType.values().forEach { type ->
                VehicleChip(
                    type = type,
                    selected = type == selectedType,
                    enabled = enabled,
                    onClick = { onTypeSelected(type) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun VehicleChip(
    type: VehicleType,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = V2VColors.accentFor(type)
    val bg by animateColorAsState(
        targetValue = if (selected) accent else V2VColors.SurfaceLight,
        animationSpec = tween(180),
        label = "chipBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) accent else V2VColors.BorderLight,
        animationSpec = tween(180),
        label = "chipBorder"
    )
    val iconTint = if (selected) V2VColors.OnAccent else accent
    val textColor = if (selected) V2VColors.OnAccent else V2VColors.Ink
    val label = vehicleLabel(type)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = iconFor(type),
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
