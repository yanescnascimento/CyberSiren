package com.cybersiren.android.v2v.ui.receiver

import android.location.Location
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.R
import com.cybersiren.android.v2v.ui.peersUnit
import com.cybersiren.android.v2v.model.ReceivedAlert
import com.cybersiren.android.v2v.ui.V2VColors
import com.cybersiren.android.v2v.ui.components.AlertCompactRow
import com.cybersiren.android.v2v.ui.components.AlertHighlightCard

@Composable
fun ReceiverModeScreen(
    activeAlerts: List<ReceivedAlert>,
    currentLocation: Location?,
    connectedPeers: Int,
    modifier: Modifier = Modifier
) {
    val sorted = remember(activeAlerts) { activeAlerts.sortedBy { it.distanceMeters } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(V2VColors.BackgroundLight)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Header(
            connectedPeers = connectedPeers,
            alertCount = sorted.size
        )

        Spacer(Modifier.height(16.dp))

        if (sorted.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "highlight_${sorted.first().alert.messageId}") {
                    AlertHighlightCard(
                        alert = sorted.first(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (sorted.size > 1) {
                    item(key = "divider") {
                        Text(
                            text = stringResource(R.string.v2v_receiver_other_alerts),
                            color = V2VColors.Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }
                    items(
                        items = sorted.drop(1),
                        key = { it.alert.messageId }
                    ) { alert ->
                        AlertCompactRow(
                            alert = alert,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        currentLocation?.let { LocationFooter(it) }
    }
}

@Composable
private fun Header(
    connectedPeers: Int,
    alertCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = if (alertCount > 0) stringResource(R.string.v2v_receiver_title_attention) else stringResource(R.string.v2v_receiver_title_listening),
                color = V2VColors.Ink,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when {
                    alertCount == 0 -> stringResource(R.string.v2v_receiver_subtitle_none)
                    alertCount == 1 -> stringResource(R.string.v2v_receiver_subtitle_one)
                    else -> stringResource(R.string.v2v_receiver_subtitle_many, alertCount)
                },
                color = V2VColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        PeersChip(connectedPeers = connectedPeers)
    }
}

@Composable
private fun PeersChip(connectedPeers: Int) {
    val connected = connectedPeers > 0
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (connected) V2VColors.Accent.copy(alpha = 0.12f) else V2VColors.SurfaceLight)
            .border(1.dp, if (connected) V2VColors.Accent.copy(alpha = 0.25f) else V2VColors.BorderLight, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Wifi,
            contentDescription = null,
            tint = if (connected) V2VColors.Accent else V2VColors.Muted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = peersUnit(connectedPeers),
            color = if (connected) V2VColors.Accent else V2VColors.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "radarPulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(V2VColors.Safe.copy(alpha = alpha))
                )
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(V2VColors.SafeSoft)
                        .border(1.dp, V2VColors.Safe.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = V2VColors.Safe,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
            text = stringResource(R.string.v2v_receiver_clear_title),
                color = V2VColors.Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
            text = stringResource(R.string.v2v_receiver_clear_subtitle),
                color = V2VColors.Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2VColors.SurfaceLight)
                    .border(1.dp, V2VColors.BorderLight, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = V2VColors.InkSoft,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.v2v_receiver_listening_chip),
                    color = V2VColors.InkSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun LocationFooter(location: Location) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            tint = V2VColors.Muted,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Lat ${String.format("%.4f", location.latitude)}   Lon ${String.format("%.4f", location.longitude)}",
            color = V2VColors.Muted,
            fontSize = 12.sp
        )
    }
}
