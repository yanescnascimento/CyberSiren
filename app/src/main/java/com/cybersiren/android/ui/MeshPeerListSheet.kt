package com.cybersiren.android.ui

import com.cybersiren.android.R
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersiren.android.core.ui.component.button.CloseButton
import com.cybersiren.android.core.ui.component.sheet.BitchatBottomSheet
import com.cybersiren.android.core.ui.component.sheet.BitchatSheetCenterTopBar
import com.cybersiren.android.core.ui.component.sheet.BitchatSheetTitle
import com.cybersiren.android.core.ui.component.sheet.BitchatSheetTopBar
import com.cybersiren.android.geohash.ChannelID
import com.cybersiren.android.ui.theme.BASE_FONT_SIZE
import com.cybersiren.android.nostr.GeohashAliasRegistry
import com.cybersiren.android.nostr.GeohashConversationRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshPeerListSheet(
    isPresented: Boolean,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onShowVerification: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadChannelMessages by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val peerRSSI by viewModel.peerRSSI.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

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

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 64.dp, bottom = 20.dp)
                ) {

                    if (joinedChannels.isNotEmpty()) {
                        item(key = "channels_header") {
                            Text(
                                text = stringResource(id = R.string.channels).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurface.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .padding(top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(
                            items = joinedChannels.toList(),
                            key = { "channel_$it" }
                        ) { channel ->
                            val isSelected = channel == currentChannel
                            val unreadCount = unreadChannelMessages[channel] ?: 0

                            ChannelRow(
                                channel = channel,
                                isSelected = isSelected,
                                unreadCount = unreadCount,
                                colorScheme = colorScheme,
                                onChannelClick = {

                                    if (channel.startsWith("@")) {

                                        val peerName = channel.removePrefix("@")
                                        val peerID =
                                            peerNicknames.entries.firstOrNull { it.value == peerName }?.key
                                    if (peerID != null) {
                                            viewModel.showPrivateChatSheet(peerID)
                                            onDismiss()
                                        }
                                    } else {

                                        viewModel.switchToChannel(channel)
                                        onDismiss()
                                    }
                                },
                                onLeaveChannel = {
                                    viewModel.leaveChannel(channel)
                                },
                            )
                        }
                    }

                    item(key = "people_section") {
                        when (selectedLocationChannel) {
                            is ChannelID.Location -> {

                                GeohashPeopleList(
                                    viewModel = viewModel,
                                    onTapPerson = onDismiss
                                )
                            }

                            else -> {

                                PeopleSection(
                                    modifier = Modifier.padding(top = if (joinedChannels.isNotEmpty()) 16.dp else 0.dp),
                                    connectedPeers = connectedPeers,
                                    peerNicknames = peerNicknames,
                                    peerRSSI = peerRSSI,
                                    nickname = nickname,
                                    colorScheme = colorScheme,
                                    selectedPrivatePeer = selectedPrivatePeer,
                                    viewModel = viewModel,
                                     onPrivateChatStart = { peerID ->
                                         viewModel.showPrivateChatSheet(peerID)
                                         onDismiss()
                                     }
                                )
                            }
                        }
                    }
                }

                BitchatSheetTopBar(
                    title = {
                        BitchatSheetTitle(text = stringResource(id = R.string.your_network))
                    },
                    backgroundAlpha = topBarAlpha,
                    actions = {
                        if (selectedLocationChannel !is ChannelID.Location) {
                            IconButton(
                                onClick = onShowVerification,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCode,
                                    contentDescription = stringResource(R.string.verify_title),
                                    tint = colorScheme.onSurface.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    onClose = onDismiss,
                )
            }
        }

    }
}

@Composable
private fun ChannelRow(
    channel: String,
    isSelected: Boolean,
    unreadCount: Int,
    colorScheme: ColorScheme,
    onChannelClick: () -> Unit,
    onLeaveChannel: () -> Unit,
) {
    Surface(
        onClick = onChannelClick,
        color = if (isSelected) {
            colorScheme.primaryContainer.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                if (unreadCount > 0) {
                    UnreadBadge(
                        count = unreadCount,
                        colorScheme = colorScheme
                    )
                }

                Text(
                    text = channel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                CloseButton(
                    onClick = onLeaveChannel,
                )
            }
        }
    }
}

@Composable
fun PeopleSection(
    modifier: Modifier  = Modifier,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    nickname: String,
    colorScheme: ColorScheme,
    selectedPrivatePeer: String?,
    viewModel: ChatViewModel,
    onPrivateChatStart: (String) -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(id = R.string.people).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = colorScheme.onSurface.copy(alpha = 0.7f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 4.dp)
        )

        if (connectedPeers.isEmpty()) {
            Text(
                text = stringResource(id = R.string.no_one_connected),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 12.dp)
            )
        }

        val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
        val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
        val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
        val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()
        val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()

        val peerFavoriteStates = remember(favoritePeers, peerFingerprints, connectedPeers) {
            connectedPeers.associateWith { peerID ->

                val fingerprint = peerFingerprints[peerID]
                fingerprint != null && favoritePeers.contains(fingerprint)
            }
        }

        val peerVerifiedStates = remember(verifiedFingerprints, peerFingerprints, connectedPeers) {
            connectedPeers.associateWith { peerID ->
                viewModel.isPeerVerified(peerID, verifiedFingerprints)
            }
        }

        val noiseHexByPeerID: Map<String, String> = connectedPeers.associateWith { pid ->
            try {
                viewModel.meshService.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { null }
        }.filterValues { it != null }.mapValues { it.value!! }

        Log.d("SidebarComponents", "Recomposing with ${favoritePeers.size} favorites, peer states: $peerFavoriteStates")

        val sortedPeers = connectedPeers.sortedWith(
            compareBy<String> { !hasUnreadPrivateMessages.contains(it) }
            .thenByDescending { privateChats[it]?.maxByOrNull { msg -> msg.timestamp }?.timestamp?.time ?: 0L }
            .thenBy { !(peerFavoriteStates[it] ?: false) }
            .thenBy { (if (it == nickname) "You" else (peerNicknames[it] ?: it)).lowercase() }
        )

        val hex64Regex = Regex("^[0-9a-fA-F]{64}$")

        fun computeDisplayNameForPeerId(key: String): String {
            return if (key == nickname) "You" else (peerNicknames[key] ?: (privateChats[key]?.lastOrNull()?.sender ?: key.take(12)))
        }

        val baseNameCounts = mutableMapOf<String, Int>()

        sortedPeers.forEach { pid ->
            val dn = computeDisplayNameForPeerId(pid)
            val (b, _) = splitSuffix(dn)
            if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
        }

        val offlineFavorites = com.cybersiren.android.favorites.FavoritesPersistenceService.shared.getOurFavorites()
        offlineFavorites.forEach { fav ->
            val favPeerID = fav.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
            val isMappedToConnected = noiseHexByPeerID.values.any { it.equals(favPeerID, ignoreCase = true) }
            if (!isMappedToConnected) {
                val dn = peerNicknames[favPeerID] ?: fav.peerNickname
                val (b, _) = splitSuffix(dn)
                if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
            }
        }

        val connectedIds = sortedPeers.toSet()
        val appendedOfflineIds = mutableSetOf<String>()
        privateChats.keys
            .filter { key ->
                (key.startsWith("nostr_") || hex64Regex.matches(key)) &&
                        !connectedIds.contains(key) &&
                        !noiseHexByPeerID.values.any { it.equals(key, ignoreCase = true) }
            }
            .forEach { convKey ->
                val dn = peerNicknames[convKey] ?: (privateChats[convKey]?.lastOrNull()?.sender ?: convKey.take(12))
                val (b, _) = splitSuffix(dn)
                if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
            }

        sortedPeers.forEach { peerID ->
            val isFavorite = peerFavoriteStates[peerID] ?: false
            val isVerified = peerVerifiedStates[peerID] ?: false

            val noiseHex = noiseHexByPeerID[peerID]
            val meshUnread = hasUnreadPrivateMessages.contains(peerID)
            val nostrUnread = if (noiseHex != null) hasUnreadPrivateMessages.contains(noiseHex) else false
            val combinedHasUnread = meshUnread || nostrUnread
            val combinedUnreadCount = (
                privateChats[peerID]?.count { msg -> msg.sender != nickname && meshUnread } ?: 0
            ) + (
                if (noiseHex != null) privateChats[noiseHex]?.count { msg -> msg.sender != nickname && nostrUnread } ?: 0 else 0
            )

            val displayName = if (peerID == nickname) "You" else (peerNicknames[peerID] ?: (privateChats[peerID]?.lastOrNull()?.sender ?: peerID.take(12)))
            val (bName, _) = splitSuffix(displayName)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val directMap by viewModel.peerDirect.collectAsStateWithLifecycle()
            val isDirectLive = directMap[peerID] ?: try { viewModel.meshService.getPeerInfo(peerID)?.isDirectConnection == true } catch (_: Exception) { false }
            PeerItem(
                peerID = peerID,
                displayName = displayName,
                isDirect = isDirectLive,
                isSelected = peerID == selectedPrivatePeer,
                isFavorite = isFavorite,
                isVerified = isVerified,
                hasUnreadDM = combinedHasUnread,
                colorScheme = colorScheme,
                viewModel = viewModel,
                onItemClick = { onPrivateChatStart(peerID) },
                onToggleFavorite = {
                    Log.d("SidebarComponents", "Sidebar toggle favorite: peerID=$peerID, currentFavorite=$isFavorite")
                    viewModel.toggleFavorite(peerID)
                },
                unreadCount = if (combinedUnreadCount > 0) combinedUnreadCount else if (combinedHasUnread) 1 else 0,
                showNostrGlobe = false,
                showHashSuffix = showHash
            )
        }

        offlineFavorites.forEach { fav ->
            val favPeerID = fav.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }

            val isMappedToConnected = noiseHexByPeerID.values.any { it.equals(favPeerID, ignoreCase = true) }
            if (isMappedToConnected) return@forEach

            val nostrConvKey: String? = try {
                val npubOrHex = com.cybersiren.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(fav.peerNoisePublicKey)
                if (npubOrHex != null) {
                    val hex = if (npubOrHex.startsWith("npub")) {
                        val (hrp, data) = com.cybersiren.android.nostr.Bech32.decode(npubOrHex)
                        if (hrp == "npub") data.joinToString("") { "%02x".format(it) } else null
                    } else {
                        npubOrHex.lowercase()
                    }
                    hex?.let { "nostr_${it.take(16)}" }
                } else null
            } catch (_: Exception) { null }

            val hasUnread = hasUnreadPrivateMessages.contains(favPeerID) || (nostrConvKey != null && hasUnreadPrivateMessages.contains(nostrConvKey))

            val mappedConnectedPeerID = noiseHexByPeerID.entries.firstOrNull { it.value.equals(favPeerID, ignoreCase = true) }?.key
            val dn = peerNicknames[favPeerID] ?: fav.peerNickname
            val (bName, _) = splitSuffix(dn)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val isVerified = viewModel.isNoisePublicKeyVerified(fav.peerNoisePublicKey, verifiedFingerprints)

            val unreadCount = (
                privateChats[favPeerID]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(favPeerID) } ?: 0
            ) + (
                if (nostrConvKey != null) privateChats[nostrConvKey]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(nostrConvKey) } ?: 0 else 0
            )

            PeerItem(
                peerID = favPeerID,
                displayName = dn,
                isDirect = false,
                isSelected = (mappedConnectedPeerID ?: favPeerID) == selectedPrivatePeer,
                isFavorite = true,
                isVerified = isVerified,
                hasUnreadDM = hasUnread,
                colorScheme = colorScheme,
                viewModel = viewModel,
                onItemClick = { onPrivateChatStart(mappedConnectedPeerID ?: favPeerID) },
                onToggleFavorite = {
                    Log.d("SidebarComponents", "Sidebar toggle favorite (offline): peerID=$favPeerID")
                    viewModel.toggleFavorite(favPeerID)
                },
                unreadCount = if (unreadCount > 0) unreadCount else if (hasUnread) 1 else 0,
                showNostrGlobe = (fav.isMutual && fav.peerNostrPublicKey != null),
                showHashSuffix = showHash
            )
            appendedOfflineIds.add(favPeerID)
        }

    }
}

@Composable
private fun PeerItem(
    peerID: String,
    displayName: String,
    isDirect: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    isVerified: Boolean,
    hasUnreadDM: Boolean,
    colorScheme: ColorScheme,
    viewModel: ChatViewModel,
    onItemClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    unreadCount: Int = 0,
    showNostrGlobe: Boolean = false,
    showHashSuffix: Boolean = true
) {
    val currentNickname by viewModel.nickname.collectAsStateWithLifecycle()

    val (baseNameRaw, suffixRaw) = splitSuffix(displayName)
    val baseName = truncateNickname(baseNameRaw)
    val suffix = if (showHashSuffix) suffixRaw else ""
    val isMe = displayName == "You" || peerID == currentNickname

    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val assignedColor = viewModel.colorForMeshPeer(peerID, isDark)
    val baseColor = if (isMe) Color(0xFFFF9500) else assignedColor

    Surface(
        onClick = onItemClick,
        color = if (isSelected) {
            colorScheme.primaryContainer.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                if (hasUnreadDM) {

                    Icon(
                        imageVector = Icons.Filled.Email,
                        contentDescription = stringResource(R.string.cd_unread_message),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFF9500)
                    )
                } else if (showNostrGlobe) {

                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = stringResource(R.string.cd_reachable_via_nostr),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF9C27B0)
                    )
                } else if (!isDirect && isFavorite) {

                    Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = stringResource(R.string.cd_offline_favorite),
                        modifier = Modifier.size(16.dp),
                        tint = Color.Gray
                    )
                } else {
                    Icon(
                        imageVector = if (isDirect) Icons.Outlined.Bluetooth else Icons.Filled.Route,
                        contentDescription = if (isDirect) "Direct Bluetooth" else "Routed",
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {

                    Text(
                        text = baseName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = BASE_FONT_SIZE.sp,
                            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = baseColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (suffix.isNotEmpty()) {
                        Text(
                            text = suffix,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = BASE_FONT_SIZE.sp
                            ),
                            color = baseColor.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            if (isVerified) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.Verified,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF32D74B)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        modifier = Modifier.size(16.dp),
                        tint = if (isFavorite) Color(0xFFFFD700) else Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(
    count: Int,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Box(
            modifier = modifier
                .background(
                    color = Color(0xFFFFD700),
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = Color.Black
            )
        }
    }
}

private fun convertRSSIToSignalStrength(rssi: Int?): Int {
    if (rssi == null) return 0

    return when {
        rssi >= -40 -> 100
        rssi >= -55 -> 85
        rssi >= -70 -> 70
        rssi >= -85 -> 50
        rssi >= -100 -> 25
        else -> 0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatSheet(
    isPresented: Boolean,
    peerID: String,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerDirectMap by viewModel.peerDirect.collectAsStateWithLifecycle()
    val peerSessionStates by viewModel.peerSessionStates.collectAsStateWithLifecycle()
    val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
    val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()

    val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()

    LaunchedEffect(peerID) {
        viewModel.startPrivateChat(peerID)
    }

    val isNostrPeer = peerID.startsWith("nostr_") || peerID.startsWith("nostr:")

    val displayName = peerNicknames[peerID] ?: peerID.take(12)
    val titleText = remember(peerID, peerNicknames) {
        if (isNostrPeer) {
            val gh = GeohashConversationRegistry.get(peerID) ?: "geohash"
            val fullPubkey = GeohashAliasRegistry.get(peerID) ?: ""
            val name = if (fullPubkey.isNotEmpty()) {
                viewModel.geohashViewModel.displayNameForGeohashConversation(fullPubkey, gh)
            } else {
                peerNicknames[peerID] ?: "unknown"
            }
            "#$gh/@$name"
        } else {
            peerNicknames[peerID] ?: peerID.take(12)
        }
    }

    val messages = privateChats[peerID] ?: emptyList()
    val isDirect = peerDirectMap[peerID] == true
    val isConnected = connectedPeers.contains(peerID) || isDirect
    val sessionState = peerSessionStates[peerID]
    val fingerprint = peerFingerprints[peerID]
    val isFavorite = remember(favoritePeers, fingerprint) {
        if (fingerprint != null) favoritePeers.contains(fingerprint) else viewModel.isFavorite(peerID)
    }

    val isVerified = remember(peerID, verifiedFingerprints) {
        viewModel.isPeerVerified(peerID, verifiedFingerprints)
    }

    val securityModifier = if (!isNostrPeer) {
        Modifier.clickable { viewModel.showSecurityVerificationSheet() }
    } else {
        Modifier
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    if (isPresented) {
        BitchatBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(modifier = Modifier.height(64.dp))

                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))

                    var forceScrollToBottom by remember { mutableStateOf(false) }
                    var isScrolledUp by remember { mutableStateOf(false) }

                    MessagesList(
                        messages = messages,
                        currentUserNickname = nickname,
                        meshService = viewModel.meshService,
                        modifier = Modifier.weight(1f),
                        forceScrollToBottom = forceScrollToBottom,
                        onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                        onNicknameClick = {  },
                        onMessageLongPress = {  },
                        onCancelTransfer = { msg -> viewModel.cancelMediaSend(msg.id) },
                        onImageClick = { _, _, _ ->  }
                    )

                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))

                    var messageText by remember {
                        mutableStateOf(
                            androidx.compose.ui.text.input.TextFieldValue(
                                ""
                            )
                        )
                    }

                    ChatInputSection(
                        messageText = messageText,
                        onMessageTextChange = { newText ->
                            messageText = newText
                            viewModel.updateMentionSuggestions(newText.text)
                        },
                        onSend = {
                            if (messageText.text.trim().isNotEmpty()) {
                                viewModel.sendMessage(messageText.text.trim())
                                messageText = androidx.compose.ui.text.input.TextFieldValue("")
                                forceScrollToBottom = !forceScrollToBottom
                            }
                        },
                        onSendVoiceNote = { peer, channel, path ->
                            viewModel.sendVoiceNote(peer, channel, path)
                        },
                        onSendImageNote = { peer, channel, path ->
                            viewModel.sendImageNote(peer, channel, path)
                        },
                        onSendFileNote = { peer, channel, path ->
                            viewModel.sendFileNote(peer, channel, path)
                        },
                        showCommandSuggestions = false,
                        commandSuggestions = emptyList(),
                        showMentionSuggestions = false,
                        mentionSuggestions = emptyList(),
                        onCommandSuggestionClick = { },
                        onMentionSuggestionClick = { },
                        selectedPrivatePeer = peerID,
                        currentChannel = null,
                        nickname = nickname,
                        colorScheme = colorScheme,
                        showMediaButtons = true
                    )
                }

                BitchatSheetCenterTopBar(
                    onClose = onDismiss,
                    modifier = Modifier.align(Alignment.TopCenter),
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 16.dp)
                                .size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.chat_back),
                                tint = colorScheme.onSurface
                            )
                        }
                    },
                    title = {

                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when {
                                isDirect -> {
                                    Icon(
                                        imageVector = Icons.Outlined.SettingsInputAntenna,
                                        contentDescription = stringResource(R.string.cd_connected_peers),
                                        modifier = Modifier.size(14.dp),
                                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                isConnected -> {
                                    Icon(
                                        imageVector = Icons.Filled.Route,
                                        contentDescription = stringResource(R.string.cd_ready_for_handshake),
                                        modifier = Modifier.size(14.dp),
                                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                isNostrPeer -> {
                                    Icon(
                                        imageVector = Icons.Filled.Public,
                                        contentDescription = stringResource(R.string.cd_nostr_reachable),
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFF9C27B0)
                                    )
                                }
                            }

                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = if (isNostrPeer) Color(0xFFFF9500) else colorScheme.onSurface
                        )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.then(securityModifier)
                            ) {
                                if (!isNostrPeer) {
                                    NoiseSessionIcon(
                                        sessionState = sessionState,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                if (isVerified) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Verified,
                                        contentDescription = stringResource(R.string.verify_title),
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFF32D74B)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { viewModel.toggleFavorite(peerID) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                                    contentDescription = if (isFavorite) stringResource(R.string.cd_remove_favorite) else stringResource(R.string.cd_add_favorite),
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isFavorite) Color(0xFFFFD700) else colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}
