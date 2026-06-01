package com.cybersiren.android.v2v.ui.components

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.R
import com.cybersiren.android.v2v.ui.V2VColors
import com.cybersiren.android.v2v.ui.peersUnit

@Composable
fun StatusDisplay(
    location: Location?,
    speed: Float,
    heading: Float,
    connectedPeers: Int,
    modifier: Modifier = Modifier,
    accentColor: Color = V2VColors.Accent
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(V2VColors.SurfaceLight)
            .border(1.dp, V2VColors.BorderLight, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = V2VColors.InkSoft,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            if (location != null) {
                Text(
                    text = stringResource(
                        R.string.v2v_lat_lon_fmt,
                        String.format("%.4f", location.latitude),
                        String.format("%.4f", location.longitude)
                    ),
                    color = V2VColors.Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = stringResource(R.string.v2v_getting_gps),
                    color = V2VColors.Muted,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            MetricCell(
                icon = Icons.Filled.Speed,
                value = "${(speed * 3.6f).toInt()}",
                unit = "km/h",
                label = stringResource(R.string.v2v_metric_speed),
                modifier = Modifier.weight(1f)
            )
            Divider()
            MetricCell(
                icon = Icons.Filled.Explore,
                value = headingDirection(heading),
                unit = "${heading.toInt()}°",
                label = stringResource(R.string.v2v_metric_heading),
                modifier = Modifier.weight(1f)
            )
            Divider()
            MetricCell(
                icon = Icons.Filled.Wifi,
                value = "$connectedPeers",
                unit = peersUnit(connectedPeers),
                label = stringResource(R.string.v2v_metric_connected),
                highlight = connectedPeers > 0,
                accent = accentColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCell(
    icon: ImageVector,
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    accent: Color = V2VColors.Accent
) {
    val valueColor = if (highlight) accent else V2VColors.Ink
    val iconTint = if (highlight) accent else V2VColors.InkSoft

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = unit,
                color = V2VColors.Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = V2VColors.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(52.dp)
            .background(V2VColors.BorderLight)
    )
}

private fun headingDirection(heading: Float): String = when {
    heading >= 337.5 || heading < 22.5 -> "N"
    heading < 67.5 -> "NE"
    heading < 112.5 -> "E"
    heading < 157.5 -> "SE"
    heading < 202.5 -> "S"
    heading < 247.5 -> "SW"
    heading < 292.5 -> "W"
    heading < 337.5 -> "NW"
    else -> "?"
}
