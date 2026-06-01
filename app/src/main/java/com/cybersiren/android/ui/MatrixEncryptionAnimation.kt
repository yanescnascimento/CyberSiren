package com.cybersiren.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

private enum class CharacterAnimationState {
    ENCRYPTED,
    DECRYPTING,
    FINAL
}

@Composable
fun shouldAnimateMessage(messageId: String): Boolean {
    val miningMessages by PoWMiningTracker.miningMessages.collectAsStateWithLifecycle()
    return miningMessages.contains(messageId)
}

object PoWMiningTracker {
    private val _miningMessages = MutableStateFlow<Set<String>>(emptySet())
    val miningMessages: StateFlow<Set<String>> = _miningMessages.asStateFlow()

    fun startMiningMessage(messageId: String) {
        _miningMessages.value = _miningMessages.value + messageId
    }

    fun stopMiningMessage(messageId: String) {
        _miningMessages.value = _miningMessages.value - messageId
    }

    fun isMiningMessage(messageId: String): Boolean {
        return _miningMessages.value.contains(messageId)
    }

    fun clearAllMining() {
        _miningMessages.value = emptySet()
    }
}

@Composable
fun MessageWithMatrixAnimation(
    message: com.cybersiren.android.model.BitchatMessage,
    messages: List<com.cybersiren.android.model.BitchatMessage> = emptyList(),
    currentUserNickname: String,
    meshService: com.cybersiren.android.mesh.BluetoothMeshService,
    colorScheme: androidx.compose.material3.ColorScheme,
    timeFormatter: java.text.SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((com.cybersiren.android.model.BitchatMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val isAnimating = shouldAnimateMessage(message.id)

    if (isAnimating) {

        AnimatedMessageDisplay(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            modifier = modifier
        )
    } else {

        val annotatedText = formatMessageAsAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )

        Text(
            text = annotatedText,
            modifier = modifier,
            fontFamily = FontFamily.Monospace,
            softWrap = true
        )
    }
}

@Composable
private fun AnimatedMessageDisplay(
    message: com.cybersiren.android.model.BitchatMessage,
    currentUserNickname: String,
    meshService: com.cybersiren.android.mesh.BluetoothMeshService,
    colorScheme: androidx.compose.material3.ColorScheme,
    timeFormatter: java.text.SimpleDateFormat,
    modifier: Modifier = Modifier
) {

    var animatedContent by remember(message.content) { mutableStateOf(message.content) }
    val isAnimating = shouldAnimateMessage(message.id)

    var characterStates by remember(message.content) {
        mutableStateOf(message.content.map { char ->
            if (char == ' ') CharacterAnimationState.FINAL else CharacterAnimationState.ENCRYPTED
        })
    }

    LaunchedEffect(isAnimating, message.content) {
        if (isAnimating && message.content.isNotEmpty()) {
            val encryptedChars = "!@$%^&*()_+-=[]{}|;:,<>?".toCharArray()

            message.content.forEachIndexed { index, targetChar ->
                if (targetChar != ' ') {
                    launch {
                        delay(index * 50L)

                        while (true) {

                            while (characterStates.getOrNull(index) == CharacterAnimationState.ENCRYPTED) {

                                val newContent = animatedContent.toCharArray()
                                if (index < newContent.size) {
                                    newContent[index] = encryptedChars[Random.nextInt(encryptedChars.size)]
                                    animatedContent = String(newContent)
                                }

                                delay(100L)

                                if (Random.nextFloat() < 0.1f) {

                                    val finalContent = animatedContent.toCharArray()
                                    if (index < finalContent.size) {
                                        finalContent[index] = targetChar
                                        animatedContent = String(finalContent)
                                    }

                                    val finalStates = characterStates.toMutableList()
                                    finalStates[index] = CharacterAnimationState.FINAL
                                    characterStates = finalStates
                                    break
                                }
                            }

                            delay(2000L)

                            val resetStates = characterStates.toMutableList()
                            resetStates[index] = CharacterAnimationState.ENCRYPTED
                            characterStates = resetStates
                        }
                    }
                }
            }
        } else {

            animatedContent = message.content
            characterStates = message.content.map { CharacterAnimationState.FINAL }
        }
    }

    val animatedMessage = message.copy(content = animatedContent)

    val annotatedText = if (isAnimating) {
        formatMessageAsAnnotatedStringWithoutTimestamp(
            message = animatedMessage,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme
        )
    } else {
        formatMessageAsAnnotatedString(
            message = animatedMessage,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
    }

    Text(
        text = annotatedText,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        softWrap = true,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
        style = androidx.compose.ui.text.TextStyle(
            color = colorScheme.onSurface
        )
    )
}

private fun formatMessageAsAnnotatedStringWithoutTimestamp(
    message: com.cybersiren.android.model.BitchatMessage,
    currentUserNickname: String,
    meshService: com.cybersiren.android.mesh.BluetoothMeshService,
    colorScheme: androidx.compose.material3.ColorScheme
): AnnotatedString {

    val timeFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    val fullText = formatMessageAsAnnotatedString(
        message = message,
        currentUserNickname = currentUserNickname,
        meshService = meshService,
        colorScheme = colorScheme,
        timeFormatter = timeFormatter
    )

    val text = fullText.text
    val timestampPattern = """ \[\d{2}:\d{2}:\d{2}].*$""".toRegex()
    val match = timestampPattern.find(text)

    return if (match != null) {

        val endIndex = match.range.first
        AnnotatedString(
            text = text.substring(0, endIndex),
            spanStyles = fullText.spanStyles.filter { it.end <= endIndex },
            paragraphStyles = fullText.paragraphStyles.filter { it.end <= endIndex }
        )
    } else {
        fullText
    }
}
