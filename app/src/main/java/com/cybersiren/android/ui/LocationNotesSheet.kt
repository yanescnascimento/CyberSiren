package com.cybersiren.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.cybersiren.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersiren.android.core.ui.component.sheet.BitchatBottomSheet
import com.cybersiren.android.core.ui.component.sheet.BitchatSheetTopBar
import com.cybersiren.android.core.ui.component.sheet.BitchatSheetTitle
import com.cybersiren.android.geohash.GeohashChannelLevel
import com.cybersiren.android.geohash.LocationChannelManager
import com.cybersiren.android.nostr.LocationNotesManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationNotesSheet(
    geohash: String,
    locationName: String?,
    nickname: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val accentGreen = if (isDark) Color.Green else Color(0xFF008000)

    val notesManager = remember { LocationNotesManager.getInstance() }
    val locationManager = remember { LocationChannelManager.getInstance(context) }

    val notes by notesManager.notes.collectAsStateWithLifecycle()
    val state by notesManager.state.collectAsStateWithLifecycle(LocationNotesManager.State.IDLE)
    val errorMessage by notesManager.errorMessage.collectAsStateWithLifecycle()
    val initialLoadComplete by notesManager.initialLoadComplete.collectAsStateWithLifecycle(false)

    val count = notes.size

    val locationNames by locationManager.locationNames.collectAsStateWithLifecycle()
    val displayLocationName = locationNames[GeohashChannelLevel.BUILDING]?.takeIf { it.isNotEmpty() }
        ?: locationNames[GeohashChannelLevel.BLOCK]?.takeIf { it.isNotEmpty() }

    var draft by remember { mutableStateOf("") }
    val sendButtonEnabled = draft.trim().isNotEmpty() && state != LocationNotesManager.State.NO_RELAYS

    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    LaunchedEffect(Unit) {
        locationManager.refreshChannels()
    }

    LaunchedEffect(geohash) {
        notesManager.setGeohash(geohash)
    }

    DisposableEffect(Unit) {
        onDispose {
            notesManager.cancel()
        }
    }

    BitchatBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 64.dp, bottom = 20.dp)
            ) {
                item(key = "notes_header") {
                    LocationNotesHeader(
                        locationName = displayLocationName,
                        state = state,
                        accentGreen = accentGreen,
                    )
                }

                when {
                    state == LocationNotesManager.State.NO_RELAYS -> {
                        item {
                            NoRelaysRow(
                                onRetry = { notesManager.refresh() }
                            )
                        }
                    }
                    state == LocationNotesManager.State.LOADING && !initialLoadComplete -> {
                        item {
                            LoadingRow()
                        }
                    }
                    notes.isEmpty() -> {
                        item {
                            EmptyRow()
                        }
                    }
                    else -> {
                        items(notes, key = { it.id }) { note ->
                            NoteRow(note = note)
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }

                errorMessage?.let { error ->
                    if (state != LocationNotesManager.State.NO_RELAYS) {
                        item {
                            ErrorRow(
                                message = error,
                                onDismiss = { notesManager.clearError() }
                            )
                        }
                    }
                }
            }

            BitchatSheetTopBar(
                onClose = onDismiss,
                modifier = Modifier.align(Alignment.TopCenter),
                title = {
                    BitchatSheetTitle(
                        text = pluralStringResource(
                            id = R.plurals.location_notes_title,
                            count = count,
                            geohash,
                            count
                        )
                    )
                }
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ){
                Column {

                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        thickness = 1.dp
                    )

                    LocationNotesInputSection(
                        draft = draft,
                        onDraftChange = { draft = it },
                        sendButtonEnabled = sendButtonEnabled,
                        accentGreen = accentGreen,
                        onSend = {
                            val content = draft.trim()
                            if (content.isNotEmpty()) {
                                notesManager.send(content, nickname)
                                draft = ""
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationNotesHeader(
    locationName: String?,
    state: LocationNotesManager.State,
    accentGreen: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
    ) {

        locationName?.let { name ->
            if (name.isNotEmpty()) {
                Text(
                    text = name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = accentGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Text(
            text = stringResource(R.string.location_notes_description),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        if (state == LocationNotesManager.State.NO_RELAYS) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.location_notes_relays_unavailable),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun NoteRow(note: LocationNotesManager.Note) {

    val baseName = note.displayName.split("#", limit = 2).firstOrNull() ?: note.displayName
    val ts = timestampText(note.createdAt)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "@$baseName",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (ts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = ts,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = note.content,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NoRelaysRow(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.location_notes_no_relays_title),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.location_notes_no_relays_desc),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.retry),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onRetry)
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.loading_location_notes),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun EmptyRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.location_notes_empty_title),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.location_notes_empty_desc),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ErrorRow(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "",
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dismiss),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onDismiss)
        )
    }
}

@Composable
private fun LocationNotesInputSection(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendButtonEnabled: Boolean,
    accentGreen: Color,
    onSend: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        Box(
            modifier = Modifier.weight(1f)
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { if (sendButtonEnabled) onSend() }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (draft.isEmpty()) {
                Text(
                    text = stringResource(R.string.location_notes_input_placeholder),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        IconButton(
            onClick = { if (sendButtonEnabled) onSend() },
            enabled = sendButtonEnabled,
            modifier = Modifier.size(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = if (!sendButtonEnabled) {
                            colorScheme.onSurface.copy(alpha = 0.3f)
                        } else {
                            accentGreen.copy(alpha = 0.75f)
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.send_message),
                    modifier = Modifier.size(20.dp),
                    tint = if (!sendButtonEnabled) {
                        colorScheme.onSurface.copy(alpha = 0.5f)
                    } else if (isDark) {
                        Color.Black
                    } else {
                        Color.White
                    }
                )
            }
        }
    }
}

private fun timestampText(createdAt: Int): String {
    val date = Date(createdAt * 1000L)
    val now = Date()

    val calendar = Calendar.getInstance()
    calendar.time = date
    val dateDay = calendar.get(Calendar.DAY_OF_YEAR)
    val dateYear = calendar.get(Calendar.YEAR)

    calendar.time = now
    val nowDay = calendar.get(Calendar.DAY_OF_YEAR)
    val nowYear = calendar.get(Calendar.YEAR)

    val daysDiff = if (dateYear == nowYear) {
        nowDay - dateDay
    } else {

        val diff = (now.time - date.time) / (1000 * 60 * 60 * 24)
        diff.toInt()
    }

    return if (daysDiff < 7) {

        val diffMillis = now.time - date.time
        val diffSeconds = diffMillis / 1000

        when {
            diffSeconds < 60 -> ""
            diffSeconds < 3600 -> {
                val minutes = (diffSeconds / 60).toInt()
                "${minutes}m ago"
            }
            diffSeconds < 86400 -> {
                val hours = (diffSeconds / 3600).toInt()
                "${hours}h ago"
            }
            else -> {
                val days = (diffSeconds / 86400).toInt()
                "${days}d ago"
            }
        }
    } else {

        val sameYear = dateYear == nowYear
        val formatter = if (sameYear) {
            SimpleDateFormat("MMM d", Locale.getDefault())
        } else {
            SimpleDateFormat("MMM d, y", Locale.getDefault())
        }
        formatter.format(date)
    }
}
