package com.cybersiren.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybersiren.android.ui.theme.BASE_FONT_SIZE
import androidx.compose.ui.res.stringResource
import com.cybersiren.android.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.cybersiren.android.core.ui.component.sheet.BitchatBottomSheet
import com.cybersiren.android.model.BitchatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatUserSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    targetNickname: String,
    selectedMessage: BitchatMessage? = null,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val standardGreen = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
    val standardBlue = Color(0xFF007AFF)
    val standardRed = Color(0xFFFF3B30)
    val standardGrey = if (isDark) Color(0xFF8E8E93) else Color(0xFF6D6D70)

    if (isPresented) {
        BitchatBottomSheet(
            onDismissRequest = onDismiss,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = stringResource(R.string.at_nickname, targetNickname),
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = if (selectedMessage != null) stringResource(R.string.choose_action_message_or_user) else stringResource(R.string.choose_action_user),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {

                    selectedMessage?.let { message ->
                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_copy_message_title),
                                subtitle = stringResource(R.string.action_copy_message_subtitle),
                                titleColor = standardGrey,
                                onClick = {

                                    clipboardManager.setText(AnnotatedString(message.content))
                                    onDismiss()
                                }
                            )
                        }
                    }

                    if (selectedMessage?.sender != viewModel.nickname.value) {

                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_slap_title, targetNickname),
                                subtitle = stringResource(R.string.action_slap_subtitle),
                                titleColor = standardBlue,
                                onClick = {

                                    viewModel.sendMessage("/slap $targetNickname")
                                    onDismiss()
                                }
                            )
                        }

                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_hug_title, targetNickname),
                                subtitle = stringResource(R.string.action_hug_subtitle),
                                titleColor = standardGreen,
                                onClick = {

                                    viewModel.sendMessage("/hug $targetNickname")
                                    onDismiss()
                                }
                            )
                        }

                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_block_title, targetNickname),
                                subtitle = stringResource(R.string.action_block_subtitle),
                                titleColor = standardRed,
                                onClick = {

                                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
                                    if (selectedLocationChannel is com.cybersiren.android.geohash.ChannelID.Location) {

                                        viewModel.blockUserInGeohash(targetNickname)
                                    } else {

                                        viewModel.sendMessage("/block $targetNickname")
                                    }
                                    onDismiss()
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.cancel_lower),
                        fontSize = BASE_FONT_SIZE.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun UserActionRow(
    title: String,
    subtitle: String,
    titleColor: Color,
    onClick: () -> Unit
) {

    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontSize = BASE_FONT_SIZE.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )

            Text(
                text = subtitle,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
