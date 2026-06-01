package com.cybersiren.android.v2v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.cybersiren.android.R
import com.cybersiren.android.v2v.model.AlertMode
import com.cybersiren.android.v2v.model.TransportLogEntry
import com.cybersiren.android.v2v.model.TransportType
import com.cybersiren.android.v2v.ui.components.ModeToggle
import com.cybersiren.android.v2v.ui.receiver.ReceiverModeScreen
import com.cybersiren.android.v2v.ui.sender.SenderModeScreen
import android.app.Activity

@Composable
fun V2VScreen(
    viewModel: V2VViewModel,
    modifier: Modifier = Modifier
) {
    val alertMode by viewModel.alertMode.collectAsStateWithLifecycle()
    val isEmergencyActive by viewModel.isEmergencyActive.collectAsStateWithLifecycle()
    val selectedVehicleType by viewModel.selectedVehicleType.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val currentSpeed by viewModel.currentSpeed.collectAsStateWithLifecycle()
    val currentHeading by viewModel.currentHeading.collectAsStateWithLifecycle()
    val activeAlerts by viewModel.activeAlerts.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val mockEnabled by viewModel.mockEnabled.collectAsStateWithLifecycle()
    val silentMode by viewModel.silentMode.collectAsStateWithLifecycle()

    var settingsOpen by remember { mutableStateOf(false) }
    var logsOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var localeTag by remember { mutableStateOf(V2VLocalePrefs.getSavedLocale(context)) }

    val transportLogs by viewModel.transportLogs.collectAsStateWithLifecycle()
    val bleAvgLatency by viewModel.bleAvgLatency.collectAsStateWithLifecycle()
    val firebaseAvgLatency by viewModel.firebaseAvgLatency.collectAsStateWithLifecycle()
    val bleLossPercent by viewModel.bleLossPercent.collectAsStateWithLifecycle()
    val firebaseLossPercent by viewModel.firebaseLossPercent.collectAsStateWithLifecycle()
    val bleSendCount by viewModel.bleSendCount.collectAsStateWithLifecycle()
    val bleRecvCount by viewModel.bleRecvCount.collectAsStateWithLifecycle()
    val firebaseSendCount by viewModel.firebaseSendCount.collectAsStateWithLifecycle()
    val firebaseRecvCount by viewModel.firebaseRecvCount.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(V2VColors.BackgroundLight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeToggle(
                    currentMode = alertMode,
                    onModeChange = { viewModel.setMode(it) },
                    enabled = !isEmergencyActive,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = { settingsOpen = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.v2v_settings_title)
                    )
                }
            }
        }

        when (alertMode) {
            AlertMode.SENDER -> {
                SenderModeScreen(
                    isEmergencyActive = isEmergencyActive,
                    selectedVehicleType = selectedVehicleType,
                    currentLocation = currentLocation,
                    currentSpeed = currentSpeed,
                    currentHeading = currentHeading,
                    connectedPeers = connectedPeers,
                    onVehicleTypeSelected = { viewModel.selectVehicleType(it) },
                    onEmergencyToggle = { viewModel.toggleEmergencyBroadcast() },
                    modifier = Modifier.weight(1f)
                )
            }
            AlertMode.RECEIVER -> {
                ReceiverModeScreen(
                    activeAlerts = activeAlerts,
                    currentLocation = currentLocation,
                    connectedPeers = connectedPeers,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (settingsOpen) {
        fun applyLocale(tag: String) {
            localeTag = tag
            V2VLocalePrefs.setLocale(context, tag)
            (context as? Activity)?.recreate()

        }

        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text(text = stringResource(R.string.v2v_settings_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.v2v_settings_mock_label))
                            Text(
                                text = stringResource(R.string.v2v_settings_mock_hint),
                                color = V2VColors.Muted
                            )
                        }
                        Switch(
                            checked = mockEnabled,
                            onCheckedChange = { viewModel.setMockEnabled(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.v2v_settings_silent_label))
                            Text(
                                text = stringResource(R.string.v2v_settings_silent_hint),
                                color = V2VColors.Muted
                            )
                        }
                        Switch(
                            checked = silentMode,
                            onCheckedChange = { viewModel.setSilentMode(it) }
                        )
                    }

                    Column {
                        Text(text = stringResource(R.string.v2v_settings_language_label))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(
                                onClick = {
                                    applyLocale("en")
                                }
                            ) { Text(text = stringResource(R.string.v2v_lang_en)) }
                            TextButton(
                                onClick = {
                                    applyLocale("es")
                                }
                            ) { Text(text = stringResource(R.string.v2v_lang_es)) }
                            TextButton(
                                onClick = {
                                    applyLocale("pt")
                                }
                            ) { Text(text = stringResource(R.string.v2v_lang_pt)) }
                        }
                    }

                    TextButton(
                        onClick = { logsOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "${stringResource(R.string.v2v_logs_btn)}")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { settingsOpen = false }) {
                    Text(text = stringResource(R.string.close_plain))
                }
            }
        )
    }

    if (logsOpen) {
        val now = System.currentTimeMillis()
        AlertDialog(
            onDismissRequest = { logsOpen = false },
            title = { Text(text = stringResource(R.string.v2v_transport_logs_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransportMetricCard(
                            label = "BLE",
                            avgLatency = bleAvgLatency,
                            lossPercent = bleLossPercent,
                            sendCount = bleSendCount,
                            recvCount = bleRecvCount,
                            modifier = Modifier.weight(1f)
                        )
                        TransportMetricCard(
                            label = "Firebase",
                            avgLatency = firebaseAvgLatency,
                            lossPercent = firebaseLossPercent,
                            sendCount = firebaseSendCount,
                            recvCount = firebaseRecvCount,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Divider()
                    Spacer(Modifier.height(6.dp))

                    viewModel.sessionLogPath()?.let { path ->
                        Text(
                            text = "$path",
                            color = V2VColors.Muted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (transportLogs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.v2v_logs_empty),
                                color = V2VColors.Muted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(transportLogs) { entry ->
                                TransportLogRow(entry = entry, now = now)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { logsOpen = false }) {
                    Text(text = stringResource(R.string.close_plain))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearTransportLogs() }) {
                    Text(text = stringResource(R.string.v2v_logs_clear))
                }
            }
        )
    }
}

@Composable
private fun TransportMetricCard(
    label: String,
    avgLatency: Long,
    lossPercent: Float,
    sendCount: Int,
    recvCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                text = "⌀ ${avgLatency}ms",
                fontSize = 11.sp,
                color = if (avgLatency > 500) Color(0xFFE57373) else V2VColors.Muted
            )
            Text(
                text = "loss %.1f%%".format(lossPercent),
                fontSize = 11.sp,
                color = if (lossPercent > 10f) Color(0xFFE57373) else V2VColors.Muted
            )
            Text(
                text = "↑$sendCount ↓$recvCount",
                fontSize = 11.sp,
                color = V2VColors.Muted
            )
        }
    }
}

@Composable
private fun TransportLogRow(entry: TransportLogEntry, now: Long) {
    val transportColor = if (entry.transport == TransportType.BLE) Color(0xFF4FC3F7) else Color(0xFFFFB74D)
    val dirSymbol = entry.direction.symbol
    val statusIcon = if (entry.success) "" else ""
    val statusColor = if (entry.success) Color(0xFF81C784) else Color(0xFFE57373)
    val latencyText = entry.latencyMs?.let { "${it}ms" } ?: "—"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = entry.transport.label.take(3), color = transportColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(text = dirSymbol, fontSize = 12.sp)
        Text(text = statusIcon, color = statusColor, fontSize = 12.sp)
        Text(text = latencyText, fontSize = 11.sp, modifier = Modifier.width(50.dp), fontFamily = FontFamily.Monospace)
        Text(
            text = entry.details.take(28),
            fontSize = 10.sp,
            color = V2VColors.Muted,
            modifier = Modifier.weight(1f)
        )
        Text(text = entry.ageText(now), fontSize = 10.sp, color = V2VColors.Muted)
    }
}
