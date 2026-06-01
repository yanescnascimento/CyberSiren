package com.cybersiren.android.v2v.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.R
import com.cybersiren.android.v2v.model.VehicleType
import com.cybersiren.android.v2v.ui.V2VColors
import com.cybersiren.android.v2v.ui.vehicleLabel

@Composable
fun EmergencyButton(
    isActive: Boolean,
    vehicleType: VehicleType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val accent = V2VColors.accentFor(vehicleType)
    val vehicleIcon = iconFor(vehicleType)
    val vehicleName = vehicleLabel(vehicleType)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val pulse = rememberInfiniteTransition(label = "emergencyPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val haloAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloAlpha"
    )
    val haloScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloScale"
    )

    val bg by animateColorAsState(
        targetValue = if (isActive) accent else V2VColors.SurfaceLight,
        animationSpec = tween(220),
        label = "bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive) V2VColors.OnAccent else accent,
        animationSpec = tween(220),
        label = "content"
    )

    val pressScale = if (pressed) 0.97f else 1f
    val activeScale = if (isActive) pulseScale else 1f

    Box(
        modifier = modifier.size(280.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = haloAlpha))
            )
        }

        Column(
            modifier = Modifier
                .size(240.dp)
                .scale(pressScale * activeScale)
                .clip(CircleShape)
                .background(bg)
                .border(
                    width = if (isActive) 0.dp else 6.dp,
                    color = accent,
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled
                ) { onClick() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = vehicleIcon,
                contentDescription = vehicleName,
                tint = contentColor,
                modifier = Modifier.size(88.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (isActive) stringResource(R.string.v2v_stop) else stringResource(R.string.v2v_activate),
                color = contentColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
        }
    }
}
