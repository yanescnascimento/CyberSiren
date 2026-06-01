package com.cybersiren.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.cybersiren.wear.data.WearAlert
import com.cybersiren.wear.data.WearAlertRepository
import com.cybersiren.wear.data.WearUrgency

@Composable
fun V2VWearScreen() {
    val alerts by WearAlertRepository.alerts.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        if (alerts.isEmpty()) {
            EmptyState()
        } else {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { Header(count = alerts.size) }
                items(alerts.size) { i -> AlertCard(alerts[i]) }
            }
        }
    }
}

@Composable
private fun Header(count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "V2V",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.primary
        )
        Text(
            text = "$count alert${if (count == 1) "" else "s"}",
            fontSize = 10.sp,
            color = Color(0xFFB0BEC5)
        )
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun AlertCard(alert: WearAlert) {
    val color = urgencyColor(alert.urgency)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                text = "${alert.vehicleType.emoji} ${alert.vehicleType.displayName}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${alert.distanceLabel} · ${alert.direction}",
                fontSize = 11.sp,
                color = Color.White
            )
            Text(
                text = urgencyLabel(alert.urgency),
                fontSize = 9.sp,
                color = Color(0xFFCFD8DC)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "", fontSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(text = "All clear", fontSize = 12.sp, color = Color.White)
            Text(text = "No alerts nearby", fontSize = 10.sp, color = Color(0xFF90A4AE))
        }
    }
}

private fun urgencyColor(u: WearUrgency): Color = when (u) {
    WearUrgency.CRITICAL -> Color(0xFFFF5252)
    WearUrgency.HIGH -> Color(0xFFFF9800)
    WearUrgency.MEDIUM -> Color(0xFFFFC107)
    WearUrgency.LOW -> Color(0xFF4FC3F7)
}

private fun urgencyLabel(u: WearUrgency): String = when (u) {
    WearUrgency.CRITICAL -> "Very close — give way"
    WearUrgency.HIGH -> "Approaching"
    WearUrgency.MEDIUM -> "Nearby"
    WearUrgency.LOW -> "Far"
}
