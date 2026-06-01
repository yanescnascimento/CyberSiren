package com.cybersiren.android.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import com.cybersiren.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.core.ui.utils.singleOrTripleClickable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TorStatusDot(
    modifier: Modifier = Modifier
) {
    val torProvider = remember { com.cybersiren.android.net.ArtiTorManager.getInstance() }
    val torStatus by torProvider.statusFlow.collectAsState()

    if (torStatus.mode != com.cybersiren.android.net.TorMode.OFF) {
        val dotColor = when {
            torStatus.running && torStatus.bootstrapPercent < 100 -> Color(0xFFFF9500)
            torStatus.running && torStatus.bootstrapPercent >= 100 -> Color(0xFF00C851)
            else -> Color.Red
        }
        Canvas(
            modifier = modifier
        ) {
            val radius = size.minDimension / 2
            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(size.width / 2, size.height / 2)
            )
        }
    }
}

@Composable
fun NoiseSessionIcon(
    sessionState: String?,
    modifier: Modifier = Modifier
) {
    val (icon, color, contentDescription) = when (sessionState) {
        "uninitialized" -> Triple(
            Icons.Outlined.NoEncryption,
            Color(0x87878700),
            stringResource(R.string.cd_ready_for_handshake)
        )
        "handshaking" -> Triple(
            Icons.Outlined.Sync,
            Color(0x87878700),
            stringResource(R.string.cd_handshake_in_progress)
        )
        "established" -> Triple(
            Icons.Filled.Lock,
            Color(0xFFFF9500),
            stringResource(R.string.cd_encrypted)
        )
        else -> {
            Triple(
                Icons.Outlined.Warning,
                Color(0xFFFF4444),
                stringResource(R.string.cd_handshake_failed)
            )
        }
    }

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = color
    )
}

@Composable
fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    LaunchedEffect(value) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.at_symbol),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.primary.copy(alpha = 0.8f)
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colorScheme.primary,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .widthIn(max = 120.dp)
                .horizontalScroll(scrollState)
        )
    }
}

@Composable
fun PeerCounter(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    hasUnreadChannels: Map<String, Int>,
    isConnected: Boolean,
    selectedLocationChannel: com.cybersiren.android.geohash.ChannelID?,
    geohashPeople: List<GeoPerson>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val (peopleCount, countColor) = when (selectedLocationChannel) {
        is com.cybersiren.android.geohash.ChannelID.Location -> {

            val count = geohashPeople.size
            val green = Color(0xFF00C851)
            Pair(count, if (count > 0) green else Color.Gray)
        }
        is com.cybersiren.android.geohash.ChannelID.Mesh,
        null -> {

            val count = connectedPeers.size
            val meshBlue = Color(0xFF007AFF)
            Pair(count, if (isConnected && count > 0) meshBlue else Color.Gray)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onClick() }.padding(end = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Group,
            contentDescription = when (selectedLocationChannel) {
                is com.cybersiren.android.geohash.ChannelID.Location -> stringResource(R.string.cd_geohash_participants)
                else -> stringResource(R.string.cd_connected_peers)
            },
            modifier = Modifier.size(16.dp),
            tint = countColor
        )
        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "$peopleCount",
            style = MaterialTheme.typography.bodyMedium,
            color = countColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        if (joinedChannels.isNotEmpty()) {
            Text(
                text = stringResource(R.string.channel_count_prefix) + "${joinedChannels.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) Color(0xFF00C851) else Color.Red,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ChatHeaderContent(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onTripleClick: () -> Unit,
    onShowAppInfo: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    when {
        currentChannel != null -> {

            ChannelHeader(
                channel = currentChannel,
                onBackClick = onBackClick,
                onLeaveChannel = { viewModel.leaveChannel(currentChannel) },
                onSidebarClick = onSidebarClick
            )
        }
        else -> {

            MainHeader(
                nickname = nickname,
                onNicknameChange = viewModel::setNickname,
                onTitleClick = onShowAppInfo,
                onTripleTitleClick = onTripleClick,
                onSidebarClick = onSidebarClick,
                onLocationChannelsClick = onLocationChannelsClick,
                onLocationNotesClick = onLocationNotesClick,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun ChannelHeader(
    channel: String,
    onBackClick: () -> Unit,
    onLeaveChannel: () -> Unit,
    onSidebarClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxWidth()) {

        Button(
            onClick = onBackClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = colorScheme.primary
            ),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-8).dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_back),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary
                )
            }
        }

        Text(
            text = stringResource(R.string.chat_channel_prefix, channel),
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFFF9500),
            modifier = Modifier
                .align(Alignment.Center)
                .clickable { onSidebarClick() }
        )

        TextButton(
            onClick = onLeaveChannel,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Text(
                text = stringResource(R.string.chat_leave),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Red
            )
        }
    }
}

@Composable
private fun MainHeader(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onTitleClick: () -> Unit,
    onTripleTitleClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val bookmarksStore = remember { com.cybersiren.android.geohash.GeohashBookmarksStore.getInstance(context) }
    val bookmarks by bookmarksStore.bookmarks.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_brand),
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.primary,
                modifier = Modifier.singleOrTripleClickable(
                    onSingleClick = onTitleClick,
                    onTripleClick = onTripleTitleClick
                )
            )

            Spacer(modifier = Modifier.width(2.dp))

            NicknameEditor(
                value = nickname,
                onValueChange = onNicknameChange
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {

            if (hasUnreadPrivateMessages.isNotEmpty()) {

                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = stringResource(R.string.cd_unread_private_messages),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { viewModel.openLatestUnreadPrivateChat() },
                    tint = Color(0xFFFF9500)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                LocationChannelsButton(
                    viewModel = viewModel,
                    onClick = onLocationChannelsClick
                )

                val currentGeohash: String? = when (val sc = selectedLocationChannel) {
                    is com.cybersiren.android.geohash.ChannelID.Location -> sc.channel.geohash
                    else -> null
                }
                if (currentGeohash != null) {
                    val isBookmarked = bookmarks.contains(currentGeohash)
                    Box(
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .size(20.dp)
                            .clickable { bookmarksStore.toggle(currentGeohash) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = stringResource(R.string.cd_toggle_bookmark),
                            tint = if (isBookmarked) Color(0xFF00C851) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            LocationNotesButton(
                viewModel = viewModel,
                onClick = onLocationNotesClick
            )

            TorStatusDot(
                modifier = Modifier
                    .size(8.dp)
                    .padding(start = 0.dp, end = 2.dp)
            )

            PoWStatusIndicator(
                modifier = Modifier,
                style = PoWIndicatorStyle.COMPACT
            )
            Spacer(modifier = Modifier.width(2.dp))
            PeerCounter(
                connectedPeers = connectedPeers.filter { it != viewModel.meshService.myPeerID },
                joinedChannels = joinedChannels,
                hasUnreadChannels = hasUnreadChannels,
                isConnected = isConnected,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                onClick = onSidebarClick
            )
        }
    }
}

@Composable
private fun LocationChannelsButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    val selectedChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val teleported by viewModel.isTeleported.collectAsStateWithLifecycle()

    val (badgeText, badgeColor) = when (selectedChannel) {
        is com.cybersiren.android.geohash.ChannelID.Mesh -> {
            "#mesh" to Color(0xFF007AFF)
        }
        is com.cybersiren.android.geohash.ChannelID.Location -> {
            val geohash = (selectedChannel as com.cybersiren.android.geohash.ChannelID.Location).channel.geohash
            "#$geohash" to Color(0xFF00C851)
        }
        null -> "#mesh" to Color(0xFF007AFF)
    }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = badgeColor
        ),
        contentPadding = PaddingValues(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = badgeColor,
                maxLines = 1
            )

            if (teleported) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.PinDrop,
                    contentDescription = stringResource(R.string.cd_teleported),
                    modifier = Modifier.size(12.dp),
                    tint = badgeColor
                )
            }
        }
    }
}
