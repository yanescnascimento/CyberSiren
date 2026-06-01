package com.cybersiren.android.ui

import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.ui.theme.BASE_FONT_SIZE
import java.util.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cybersiren.android.R

data class GeoPerson(
    val id: String,
    val displayName: String,
    val lastSeen: Date
)

@Composable
fun GeohashPeopleList(
    viewModel: ChatViewModel,
    onTapPerson: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val isTeleported by viewModel.isTeleported.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()

    Column {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.geohash_people_header),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        if (geohashPeople.isEmpty()) {

            Text(
                text = stringResource(R.string.nobody_around),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = BASE_FONT_SIZE.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        } else {

            val myHex = remember(selectedLocationChannel) {
                when (val channel = selectedLocationChannel) {
                    is com.cybersiren.android.geohash.ChannelID.Location -> {
                        try {
                            val identity = com.cybersiren.android.nostr.NostrIdentityBridge.deriveIdentity(
                                forGeohash = channel.channel.geohash,
                                context = viewModel.getApplication()
                            )
                            identity.publicKeyHex.lowercase()
                        } catch (e: Exception) {
                            Log.e("GeohashPeopleList", "Failed to derive identity: ${e.message}")
                            null
                        }
                    }
                    else -> null
                }
            }

            val orderedPeople = remember(geohashPeople, myHex) {
                geohashPeople.sortedWith { a, b ->
                    when {
                        myHex != null && a.id == myHex && b.id != myHex -> -1
                        myHex != null && b.id == myHex && a.id != myHex -> 1
                        else -> b.lastSeen.compareTo(a.lastSeen)
                    }
                }
            }

            val baseNameCounts = remember(geohashPeople) {
                val counts = mutableMapOf<String, Int>()
                geohashPeople.forEach { person ->
                    val (b, _) = com.cybersiren.android.ui.splitSuffix(person.displayName)
                    counts[b] = (counts[b] ?: 0) + 1
                }
                counts
            }

            val firstID = orderedPeople.firstOrNull()?.id

            orderedPeople.forEach { person ->
                GeohashPersonItem(
                    person = person,
                    isFirst = person.id == firstID,
                    isMe = myHex != null && person.id == myHex,
                    hasUnreadDM = unreadPrivateMessages.contains("nostr_${person.id.take(16)}"),
                    isTeleported = person.id != myHex && viewModel.isPersonTeleported(person.id),
                    isMyTeleported = person.id == myHex && isTeleported,
                    nickname = nickname,
                    colorScheme = colorScheme,
                    viewModel = viewModel,
                    showHashSuffix = (baseNameCounts[com.cybersiren.android.ui.splitSuffix(person.displayName).first] ?: 0) > 1,
                    onTap = {
                        if (person.id != myHex) {

                            viewModel.startGeohashDM(person.id)
                            onTapPerson()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun GeohashPersonItem(
    person: GeoPerson,
    isFirst: Boolean,
    isMe: Boolean,
    hasUnreadDM: Boolean,
    isTeleported: Boolean,
    isMyTeleported: Boolean,
    nickname: String,
    colorScheme: ColorScheme,
    viewModel: ChatViewModel,
    showHashSuffix: Boolean,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .padding(top = if (isFirst) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        if (hasUnreadDM) {

            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = stringResource(R.string.cd_unread_message),
                modifier = Modifier.size(12.dp),
                tint = Color(0xFFFF9500)
            )
        } else {

            val (iconName, iconColor) = when {
                isMe && isMyTeleported -> "face.dashed" to Color(0xFFFF9500)
                isTeleported -> "face.dashed" to colorScheme.onSurface
                isMe -> "face.smiling" to Color(0xFFFF9500)
                else -> "face.smiling" to colorScheme.onSurface
            }

            val icon = when (iconName) {
                "face.dashed" -> Icons.Outlined.Explore
                else -> Icons.Outlined.LocationOn
            }

            Icon(
                imageVector = icon,
                contentDescription = if (isTeleported || isMyTeleported) "Teleported user" else "User",
                modifier = Modifier.size(12.dp),
                tint = iconColor.copy(alpha = if (iconName == "face.dashed") 0.6f else 1.0f)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        val (baseNameRaw, suffixRaw) = com.cybersiren.android.ui.splitSuffix(person.displayName)
        val baseName = truncateNickname(baseNameRaw)
        val suffix = if (showHashSuffix) suffixRaw else ""

        val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
        val assignedColor = viewModel.colorForNostrPubkey(person.id, isDark)
        val baseColor = if (isMe) Color(0xFFFF9500) else assignedColor

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {

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

            if (isMe) {
                Text(
                    text = stringResource(R.string.you_suffix),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = baseColor
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))
    }
}
