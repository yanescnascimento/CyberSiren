package com.cybersiren.android.v2v.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.R
import com.cybersiren.android.v2v.model.AlertMode
import com.cybersiren.android.v2v.ui.V2VColors

@Composable
fun ModeToggle(
    currentMode: AlertMode,
    onModeChange: (AlertMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(V2VColors.SurfaceLight)
            .border(1.dp, V2VColors.BorderLight, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SegmentButton(
            label = stringResource(R.string.v2v_mode_send),
            icon = Icons.Filled.GraphicEq,
            selected = currentMode == AlertMode.SENDER,
            enabled = enabled,
            onClick = { onModeChange(AlertMode.SENDER) },
            modifier = Modifier.weight(1f)
        )
        SegmentButton(
            label = stringResource(R.string.v2v_mode_receive),
            icon = Icons.Filled.Hearing,
            selected = currentMode == AlertMode.RECEIVER,
            enabled = enabled,
            onClick = { onModeChange(AlertMode.RECEIVER) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(
        targetValue = if (selected) V2VColors.Ink else Color.Transparent,
        animationSpec = tween(180),
        label = "segBg"
    )
    val fg = if (selected) V2VColors.OnAccent else V2VColors.InkSoft

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
