package com.cybersiren.android.v2v.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.cybersiren.android.R
import com.cybersiren.android.v2v.model.ReceivedAlert
import com.cybersiren.android.v2v.model.UrgencyLevel
import com.cybersiren.android.v2v.ui.V2VColors
import com.cybersiren.android.v2v.ui.directionLabel
import com.cybersiren.android.v2v.ui.vehicleLabel

@Composable
fun AlertHighlightCard(
    alert: ReceivedAlert,
    modifier: Modifier = Modifier
) {
    val accent = V2VColors.accentFor(alert.alert.vehicleType)
    val urgency = alert.urgencyLevel
    val isCritical = urgency == UrgencyLevel.CRITICAL
    val isHigh = urgency == UrgencyLevel.HIGH
    val vehicleName = vehicleLabel(alert.alert.vehicleType)

    val pulse = rememberInfiniteTransition(label = "alertPulse")
    val haloAlpha by pulse.animateFloat(
        initialValue = if (isCritical) 0.35f else 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloAlpha"
    )
    val borderWidth by animateColorAsState(
        targetValue = if (isCritical) accent else if (isHigh) accent.copy(alpha = 0.75f) else V2VColors.BorderLight,
        animationSpec = tween(250),
        label = "border"
    )

    Box(modifier = modifier) {
        if (isCritical) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(accent.copy(alpha = haloAlpha))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(V2VColors.SurfaceLight)
                .border(
                    width = if (isCritical) 3.dp else 2.dp,
                    color = borderWidth,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconFor(alert.alert.vehicleType),
                        contentDescription = vehicleName,
                        tint = V2VColors.OnAccent,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vehicleName.uppercase(),
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = urgencyLabel(urgency),
                        color = V2VColors.Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = distanceValue(alert),
                        color = V2VColors.Ink,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1).sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = distanceUnit(alert),
                        color = V2VColors.Muted,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                DirectionBadge(
                    direction = alert.relativeDirection,
                    accent = accent
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = null,
                    tint = V2VColors.InkSoft,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${alert.alert.speedKmh.toInt()} km/h",
                    color = V2VColors.InkSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "·",
                    color = V2VColors.Muted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = timeAgo(alert.ageSeconds),
                    color = V2VColors.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (isCritical) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = stringResource(R.string.v2v_critical_banner),
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun AlertCompactRow(
    alert: ReceivedAlert,
    modifier: Modifier = Modifier
) {
    val accent = V2VColors.accentFor(alert.alert.vehicleType)
    val vehicleName = vehicleLabel(alert.alert.vehicleType)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(V2VColors.SurfaceLight)
            .border(1.dp, V2VColors.BorderLight, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconFor(alert.alert.vehicleType),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = vehicleName,
                color = V2VColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${directionLabel(alert.relativeDirection)} · ${alert.alert.speedKmh.toInt()} km/h",
                color = V2VColors.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = distanceValue(alert),
                color = V2VColors.Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = distanceUnit(alert),
                color = V2VColors.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Icon(
            imageVector = directionIcon(alert.relativeDirection),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun DirectionBadge(
    direction: String,
    accent: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = directionIcon(direction),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = directionLabel(direction),
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

@Composable
private fun urgencyLabel(level: UrgencyLevel): String = when (level) {
    UrgencyLevel.CRITICAL -> stringResource(R.string.v2v_urgency_critical)
    UrgencyLevel.HIGH -> stringResource(R.string.v2v_urgency_high)
    UrgencyLevel.MEDIUM -> stringResource(R.string.v2v_urgency_medium)
    UrgencyLevel.LOW -> stringResource(R.string.v2v_urgency_low)
}

private fun distanceValue(alert: ReceivedAlert): String {
    val d = alert.distanceMeters
    return when {
        !d.isFinite() || d < 0f -> "—"
        d < 100 -> d.toInt().toString()
        d < 1000 -> ((d / 10).toInt() * 10).toString()
        d < 99_000 -> String.format("%.1f", d / 1000)
        else -> "99+"
    }
}

private fun distanceUnit(alert: ReceivedAlert): String {
    val d = alert.distanceMeters
    return when {
        !d.isFinite() || d < 0f -> ""
        d < 1000 -> "m"
        else -> "km"
    }
}

private fun directionIcon(direction: String): ImageVector = when (direction.lowercase()) {
    "ahead" -> Icons.Filled.ArrowUpward
    "behind" -> Icons.Filled.ArrowDownward
    "left" -> Icons.Filled.ChevronLeft
    "right" -> Icons.Filled.ChevronRight
    else -> Icons.Filled.HelpOutline
}

@Composable
private fun directionText(direction: String): String = directionLabel(direction)

@Composable
private fun timeAgo(seconds: Long): String = when {
    seconds < 5 -> stringResource(R.string.v2v_time_now)
    seconds < 60 -> stringResource(R.string.v2v_time_seconds_ago, seconds)
    else -> stringResource(R.string.v2v_time_minutes_ago, seconds / 60)
}
