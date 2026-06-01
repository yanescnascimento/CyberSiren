package com.cybersiren.android.ui

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cybersiren.android.favorites.FavoritesPersistenceService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.cybersiren.android.mesh.BluetoothMeshDelegate
import com.cybersiren.android.mesh.BluetoothMeshService
import com.cybersiren.android.service.MeshServiceHolder
import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.model.BitchatMessageType
import com.cybersiren.android.nostr.NostrIdentityBridge
import com.cybersiren.android.protocol.BitchatPacket

import kotlinx.coroutines.launch
import com.cybersiren.android.util.NotificationIntervalManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.random.Random
import com.cybersiren.android.services.VerificationService
import com.cybersiren.android.identity.SecureIdentityStateManager
import com.cybersiren.android.noise.NoiseSession
import com.cybersiren.android.nostr.GeohashAliasRegistry
import com.cybersiren.android.util.dataFromHexString
import com.cybersiren.android.util.hexEncodedString
import java.security.MessageDigest

class ChatViewModel(
    application: Application,
    initialMeshService: BluetoothMeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {

    var meshService: BluetoothMeshService = initialMeshService
        private set
    private val debugManager by lazy { try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "ChatViewModel"
    }

    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendVoiceNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendFileNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendImageNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun getCurrentNpub(): String? {
        return try {
            NostrIdentityBridge
                .getCurrentNostrIdentity(getApplication())
                ?.npub
        } catch (_: Exception) {
            null
        }
    }

    fun buildMyQRString(nickname: String, npub: String?): String {
        return VerificationService.buildMyQRString(nickname, npub) ?: ""
    }

    private val state = ChatState(
        scope = viewModelScope,
    )

    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    private val dataManager = DataManager(application.applicationContext)
    private val identityManager by lazy { SecureIdentityStateManager(getApplication()) }
    private val messageManager = MessageManager(state)
    private val channelManager = ChannelManager(state, messageManager, dataManager, viewModelScope)

    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = meshService.hasEstablishedSession(peerID)
        override fun initiateHandshake(peerID: String) = meshService.initiateNoiseHandshake(peerID)
        override fun getMyPeerID(): String = meshService.myPeerID
    }

    val privateChatManager = PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    private val commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager)
    private val notificationManager = NotificationManager(
      application.applicationContext,
      NotificationManagerCompat.from(application.applicationContext),
      NotificationIntervalManager()
    )

    private val verificationHandler = VerificationHandler(
        context = application.applicationContext,
        scope = viewModelScope,
        getMeshService = { meshService },
        identityManager = identityManager,
        state = state,
        notificationManager = notificationManager,
        messageManager = messageManager
    )
    val verifiedFingerprints = verificationHandler.verifiedFingerprints

    private val mediaSendingManager = MediaSendingManager(state, messageManager, channelManager) { meshService }

    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(application.applicationContext) },
        getMyPeerID = { meshService.myPeerID },
        getMeshService = { meshService }
    )

    val geohashViewModel = GeohashViewModel(
        application = application,
        state = state,
        messageManager = messageManager,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        dataManager = dataManager,
        notificationManager = notificationManager
    )

    val messages: StateFlow<List<BitchatMessage>> = state.messages
    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val nickname: StateFlow<String> = state.nickname
    val isConnected: StateFlow<Boolean> = state.isConnected
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: StateFlow<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: StateFlow<Set<String>> = state.unreadPrivateMessages
    val joinedChannels: StateFlow<Set<String>> = state.joinedChannels
    val currentChannel: StateFlow<String?> = state.currentChannel
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = state.channelMessages
    val unreadChannelMessages: StateFlow<Map<String, Int>> = state.unreadChannelMessages
    val passwordProtectedChannels: StateFlow<Set<String>> = state.passwordProtectedChannels
    val showPasswordPrompt: StateFlow<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: StateFlow<String?> = state.passwordPromptChannel
    val hasUnreadChannels = state.hasUnreadChannels
    val hasUnreadPrivateMessages = state.hasUnreadPrivateMessages
    val showCommandSuggestions: StateFlow<Boolean> = state.showCommandSuggestions
    val commandSuggestions: StateFlow<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: StateFlow<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: StateFlow<List<String>> = state.mentionSuggestions
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerSessionStates: StateFlow<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val peerRSSI: StateFlow<Map<String, Int>> = state.peerRSSI
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val showAppInfo: StateFlow<Boolean> = state.showAppInfo
    val showMeshPeerList: StateFlow<Boolean> = state.showMeshPeerList
    val privateChatSheetPeer: StateFlow<String?> = state.privateChatSheetPeer
    val showVerificationSheet: StateFlow<Boolean> = state.showVerificationSheet
    val showSecurityVerificationSheet: StateFlow<Boolean> = state.showSecurityVerificationSheet
    val selectedLocationChannel: StateFlow<com.cybersiren.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: StateFlow<Boolean> = state.isTeleported
    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val teleportedGeo: StateFlow<Set<String>> = state.teleportedGeo
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts

    init {

        loadAndInitialize()

        viewModelScope.launch {
            try { com.cybersiren.android.services.AppStateStore.peers.collect { peers ->
                state.setConnectedPeers(peers)
                state.setIsConnected(peers.isNotEmpty())
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.cybersiren.android.services.AppStateStore.publicMessages.collect { msgs ->

                state.setMessages(msgs)
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.cybersiren.android.services.AppStateStore.privateMessages.collect { byPeer ->

                state.setPrivateChats(byPeer)

                try {
                    val seen = com.cybersiren.android.services.SeenMessageStore.getInstance(getApplication())
                    val myNick = state.getNicknameValue() ?: meshService.myPeerID
                    val unread = mutableSetOf<String>()
                    byPeer.forEach { (peer, list) ->
                        if (list.any { msg -> msg.sender != myNick && !seen.hasRead(msg.id) }) unread.add(peer)
                    }
                    state.setUnreadPrivateMessages(unread)
                } catch (_: Exception) { }
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.cybersiren.android.services.AppStateStore.channelMessages.collect { byChannel ->

                state.setChannelMessages(byChannel)
            } } catch (_: Exception) { }
        }

        viewModelScope.launch {
            com.cybersiren.android.mesh.TransferProgressManager.events.collect { evt ->
                mediaSendingManager.handleTransferProgressEvent(evt)
            }
        }

    }

    fun cancelMediaSend(messageId: String) {

        mediaSendingManager.cancelMediaSend(messageId)
    }

    private fun loadAndInitialize() {

        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)

        val (joinedChannels, protectedChannels) = channelManager.loadChannelData()
        state.setJoinedChannels(joinedChannels)
        state.setPasswordProtectedChannels(protectedChannels)

        joinedChannels.forEach { channel ->
            if (!state.getChannelMessagesValue().containsKey(channel)) {
                val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                state.setChannelMessages(updatedChannelMessages)
            }
        }

        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()

        dataManager.logAllFavorites()
        logCurrentFavoriteState()

        initializeSessionStateMonitoring()

        viewModelScope.launch {
            com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().debugMessages.collect { msgs ->
                if (com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().verboseLoggingEnabled.value) {

                    val selectedLocation = state.selectedLocationChannel.value
                    if (selectedLocation is com.cybersiren.android.geohash.ChannelID.Mesh) {

                        msgs.lastOrNull()?.let { dm ->
                            messageManager.addSystemMessage(dm.content)
                        }
                    }
                }
            }
        }

        geohashViewModel.initialize()

        com.cybersiren.android.favorites.FavoritesPersistenceService.initialize(getApplication())

        verificationHandler.loadVerifiedFingerprints()

        try {
            val nostrTransport = com.cybersiren.android.nostr.NostrTransport.getInstance(getApplication())
            nostrTransport.senderPeerID = meshService.myPeerID
        } catch (_: Exception) { }

    }

    override fun onCleared() {
        super.onCleared()

    }

    fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        meshService.sendBroadcastAnnounce()
    }

    private fun ensureGeohashDMSubscriptionIfNeeded(convKey: String) {
        try {
            val repoField = GeohashViewModel::class.java.getDeclaredField("repo")
            repoField.isAccessible = true
            val repo = repoField.get(geohashViewModel) as com.cybersiren.android.nostr.GeohashRepository
            val gh = repo.getConversationGeohash(convKey)
            if (!gh.isNullOrEmpty()) {
                val subMgrField = GeohashViewModel::class.java.getDeclaredField("subscriptionManager")
                subMgrField.isAccessible = true
                val subMgr = subMgrField.get(geohashViewModel) as com.cybersiren.android.nostr.NostrSubscriptionManager
                val identity = com.cybersiren.android.nostr.NostrIdentityBridge.deriveIdentity(gh, getApplication())
                val subId = "geo-dm-$gh"
                val currentDmSubField = GeohashViewModel::class.java.getDeclaredField("currentDmSubId")
                currentDmSubField.isAccessible = true
                val currentId = currentDmSubField.get(geohashViewModel) as String?
                if (currentId != subId) {
                    (currentId)?.let { subMgr.unsubscribe(it) }
                    currentDmSubField.set(geohashViewModel, subId)
                    subMgr.subscribeGiftWraps(
                        pubkey = identity.publicKeyHex,
                        sinceMs = System.currentTimeMillis() - 172800000L,
                        id = subId,
                        handler = { event ->
                            val dmHandlerField = GeohashViewModel::class.java.getDeclaredField("dmHandler")
                            dmHandlerField.isAccessible = true
                            val dmHandler = dmHandlerField.get(geohashViewModel) as com.cybersiren.android.nostr.NostrDirectMessageHandler
                            dmHandler.onGiftWrap(event, gh, identity)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureGeohashDMSubscriptionIfNeeded failed: ${e.message}")
        }
    }

    fun joinChannel(channel: String, password: String? = null): Boolean {
        return channelManager.joinChannel(channel, password, meshService.myPeerID)
    }

    fun switchToChannel(channel: String?) {
        channelManager.switchToChannel(channel)
    }

    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        meshService.sendMessage("left $channel")
    }

    fun startPrivateChat(peerID: String) {

        if (peerID.startsWith("nostr_")) {
            ensureGeohashDMSubscriptionIfNeeded(peerID)
        }

        val success = privateChatManager.startPrivateChat(peerID, meshService)
        if (success) {

            setCurrentPrivateChatPeer(peerID)

            clearNotificationsForSender(peerID)

            try {
                val seen = com.cybersiren.android.services.SeenMessageStore.getInstance(getApplication())
                val chats = state.getPrivateChatsValue()
                val messages = chats[peerID] ?: emptyList()
                messages.forEach { msg ->
                    try { seen.markRead(msg.id) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }

    fun endPrivateChat() {
        privateChatManager.endPrivateChat()

        setCurrentPrivateChatPeer(null)

        clearMeshMentionNotifications()

        hidePrivateChatSheet()
    }

    fun openLatestUnreadPrivateChat() {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: meshService.myPeerID
            val chats = state.getPrivateChatsValue()

            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {

                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {

                ensureGeohashDMSubscriptionIfNeeded(targetKey)
                targetKey
            } else {

                val canonical = com.cybersiren.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                    selectedPeerID = targetKey,
                    connectedPeers = state.getConnectedPeersValue(),
                    meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                    meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                    nostrPubHexForAlias = { alias -> com.cybersiren.android.nostr.GeohashAliasRegistry.get(alias) },
                    findNoiseKeyForNostr = { key -> com.cybersiren.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
                canonical ?: targetKey
            }

            showPrivateChatSheet(openPeer)
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }

    fun sendMessage(content: String) {
        if (content.isEmpty()) return

        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            commandProcessor.processCommand(content, meshService, meshService.myPeerID, { messageContent, mentions, channel ->
                if (selectedLocationForCommand is com.cybersiren.android.geohash.ChannelID.Location) {

                    geohashViewModel.sendGeohashMessage(
                        messageContent,
                        selectedLocationForCommand.channel,
                        meshService.myPeerID,
                        state.getNicknameValue()
                    )
                } else {

                    meshService.sendMessage(messageContent, mentions, channel)
                }
            })
            return
        }

        val mentions = messageManager.parseMentions(content, meshService.getPeerNicknames().values.toSet(), state.getNicknameValue())

        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()

        if (selectedPeer != null) {

            selectedPeer = com.cybersiren.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = selectedPeer,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                nostrPubHexForAlias = { alias -> com.cybersiren.android.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.cybersiren.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
            ).also { canonical ->
                if (canonical != state.getSelectedPrivateChatPeerValue()) {
                    privateChatManager.startPrivateChat(canonical, meshService)

                    if (state.getPrivateChatSheetPeerValue() != null) {
                        showPrivateChatSheet(canonical)
                    }
                }
            }

            val recipientNickname = meshService.getPeerNicknames()[selectedPeer]
            privateChatManager.sendPrivateMessage(
                content,
                selectedPeer,
                recipientNickname,
                state.getNicknameValue(),
                meshService.myPeerID
            ) { messageContent, peerID, recipientNicknameParam, messageId ->

                val router = com.cybersiren.android.services.MessageRouter.getInstance(getApplication(), meshService)
                router.sendPrivate(messageContent, peerID, recipientNicknameParam, messageId)
            }
        } else {

            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.cybersiren.android.geohash.ChannelID.Location) {

                geohashViewModel.sendGeohashMessage(content, selectedLocationChannel.channel, meshService.myPeerID, state.getNicknameValue())
            } else {

                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: meshService.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = meshService.myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = currentChannelValue
                )

                if (currentChannelValue != null) {
                    channelManager.addChannelMessage(currentChannelValue, message, meshService.myPeerID)

                    if (channelManager.hasChannelKey(currentChannelValue)) {
                        channelManager.sendEncryptedChannelMessage(
                            content,
                            mentions,
                            currentChannelValue,
                            state.getNicknameValue(),
                            meshService.myPeerID,
                            onEncryptedPayload = { encryptedData ->

                                meshService.sendMessage(content, mentions, currentChannelValue)
                            },
                            onFallback = {
                                meshService.sendMessage(content, mentions, currentChannelValue)
                            }
                        )
                    } else {
                        meshService.sendMessage(content, mentions, currentChannelValue)
                    }
                } else {
                    messageManager.addMessage(message)
                    meshService.sendMessage(content, mentions, null)
                }
            }
        }
    }

    fun getPeerIDForNickname(nickname: String): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }

    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)

        try {
            var noiseKey: ByteArray? = null
            var nickname: String = meshService.getPeerNicknames()[peerID] ?: peerID

            val peerInfo = meshService.getPeerInfo(peerID)
            if (peerInfo?.noisePublicKey != null) {
                noiseKey = peerInfo.noisePublicKey
                nickname = peerInfo.nickname
            } else {

                if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    try {
                        noiseKey = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

                        val rel = com.cybersiren.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey!!)
                        if (rel != null) nickname = rel.peerNickname
                    } catch (_: Exception) { }
                }
            }

            if (noiseKey != null) {

                val identityManager = com.cybersiren.android.identity.SecureIdentityStateManager(getApplication())
                val fingerprint = identityManager.generateFingerprint(noiseKey!!)
                val isNowFavorite = dataManager.favoritePeers.contains(fingerprint)

                com.cybersiren.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey!!,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                try {
                    val myNostr = com.cybersiren.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(getApplication())
                    val announcementContent = if (isNowFavorite) "[FAVORITED]:${myNostr?.npub ?: ""}" else "[UNFAVORITED]:${myNostr?.npub ?: ""}"

                    if (meshService.hasEstablishedSession(peerID)) {

                        meshService.sendPrivateMessage(
                            announcementContent,
                            peerID,
                            nickname,
                            java.util.UUID.randomUUID().toString()
                        )
                    } else {
                        val nostrTransport = com.cybersiren.android.nostr.NostrTransport.getInstance(getApplication())
                        nostrTransport.senderPeerID = meshService.myPeerID
                        nostrTransport.sendFavoriteNotification(peerID, isNowFavorite)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        logCurrentFavoriteState()
    }

    private fun logCurrentFavoriteState() {
        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "StateFlow favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i("ChatViewModel", "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i("ChatViewModel", "==============================")
    }

    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                updateReactiveStates()
            }
        }
    }

    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()

        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            meshService.getSessionState(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)

        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                com.cybersiren.android.services.MessageRouter
                    .getInstance(getApplication(), meshService)
                    .onSessionEstablished(peerID)
            }
        }

        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)
        fingerprints.forEach { (peerID, fingerprint) ->
            identityManager.cachePeerFingerprint(peerID, fingerprint)
            val info = try { meshService.getPeerInfo(peerID) } catch (_: Exception) { null }
            val noiseKeyHex = info?.noisePublicKey?.hexEncodedString()
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fingerprint)
            }
            info?.nickname?.takeIf { it.isNotBlank() }?.let { nickname ->
                identityManager.cacheFingerprintNickname(fingerprint, nickname)
            }
        }

        val nicknames = meshService.getPeerNicknames()
        state.setPeerNicknames(nicknames)

        val rssiValues = meshService.getPeerRSSI()
        state.setPeerRSSI(rssiValues)

        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                meshService.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) { }

        currentPeers.forEach { peerID ->
            if (meshService.getSessionState(peerID) is NoiseSession.NoiseSessionState.Established) {
                verificationHandler.sendPendingVerificationIfNeeded(peerID)
            }
        }
    }

    fun isPeerVerified(peerID: String, verifiedFingerprints: Set<String>): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = verificationHandler.getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && verifiedFingerprints.contains(fingerprint)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray, verifiedFingerprints: Set<String>): Boolean {
        val fingerprint = verificationHandler.fingerprintFromNoiseBytes(noisePublicKey)
        return verifiedFingerprints.contains(fingerprint)
    }

    fun unverifyFingerprint(peerID: String) {
        verificationHandler.unverifyFingerprint(peerID)
    }

    fun beginQRVerification(qr: VerificationService.VerificationQR): Boolean {
        return verificationHandler.beginQRVerification(qr)
    }

    fun getDebugStatus(): String {
        return meshService.getDebugStatus()
    }

    fun setCurrentPrivateChatPeer(peerID: String?) {
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }

    fun setCurrentGeohash(geohash: String?) {
        notificationManager.setCurrentGeohash(geohash)
    }

    fun clearNotificationsForSender(peerID: String) {
        notificationManager.clearNotificationsForSender(peerID)
    }

    fun clearNotificationsForGeohash(geohash: String) {
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    fun clearMeshMentionNotifications() {
        notificationManager.clearMeshMentionNotifications()
    }

    private var reopenSidebarAfterVerification = false

    fun showVerificationSheet(fromSidebar: Boolean = false) {
        if (fromSidebar) {
            reopenSidebarAfterVerification = true
        }
        state.setShowVerificationSheet(true)
    }

    fun hideVerificationSheet() {
        state.setShowVerificationSheet(false)
        if (reopenSidebarAfterVerification) {
            reopenSidebarAfterVerification = false
            state.setShowMeshPeerList(true)
        }
    }

    fun showSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(true)
    }

    fun hideSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(false)
    }

    fun showMeshPeerList() {
        state.setShowMeshPeerList(true)
    }

    fun hideMeshPeerList() {
        state.setShowMeshPeerList(false)
    }

    fun showPrivateChatSheet(peerID: String) {
        state.setPrivateChatSheetPeer(peerID)
    }

    fun hidePrivateChatSheet() {
        state.setPrivateChatSheetPeer(null)
    }

    fun getPeerFingerprintForDisplay(peerID: String): String? {
        return verificationHandler.getPeerFingerprintForDisplay(peerID)
    }

    fun getMyFingerprint(): String {
        return verificationHandler.getMyFingerprint()
    }

    fun resolvePeerDisplayNameForFingerprint(peerID: String): String {
        return verificationHandler.resolvePeerDisplayNameForFingerprint(peerID)
    }

    fun verifyFingerprintValue(fingerprint: String) {
        verificationHandler.verifyFingerprintValue(fingerprint)
    }

    fun unverifyFingerprintValue(fingerprint: String) {
        verificationHandler.unverifyFingerprintValue(fingerprint)
    }

    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }

    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }

    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, meshService, this)
    }

    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(nickname, currentText)
    }

    override fun didReceiveMessage(message: BitchatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }

    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyChallenge(peerID, payload)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyResponse(peerID, payload)
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }

    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }

    override fun isFavorite(peerID: String): Boolean {
        return meshDelegateHandler.isFavorite(peerID)
    }

    fun panicClearAllData() {
        Log.w(TAG, "PANIC MODE ACTIVATED - Clearing all sensitive data")

        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()
        dataManager.clearAllData()

        try {
            com.cybersiren.android.services.SeenMessageStore.getInstance(getApplication()).clear()
        } catch (_: Exception) { }

        clearAllMeshServiceData()

        clearAllCryptographicData()

        notificationManager.clearAllNotifications()

        com.cybersiren.android.features.file.FileUtils.clearAllMedia(getApplication())

        try {

            try {
                val store = com.cybersiren.android.geohash.GeohashBookmarksStore.getInstance(getApplication())
                store.clearAll()
            } catch (_: Exception) { }

            geohashViewModel.panicReset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset Nostr/geohash: ${e.message}")
        }

        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)

        recreateMeshServiceAfterPanic()

        Log.w(TAG, "PANIC MODE COMPLETED - New identity: ${meshService.myPeerID}")
    }

    private fun recreateMeshServiceAfterPanic() {
        val oldPeerID = meshService.myPeerID

        MeshServiceHolder.clear()

        val freshMeshService = MeshServiceHolder.getOrCreate(getApplication())

        meshService = freshMeshService
        meshService.delegate = this

        meshService.startServices()
        meshService.sendBroadcastAnnounce()

        Log.d(
            TAG,
            "Mesh service recreated. Old peerID: $oldPeerID, New peerID: ${meshService.myPeerID}"
        )
    }

    private fun clearAllMeshServiceData() {
        try {

            meshService.clearAllInternalData()

            Log.d(TAG, "Cleared all mesh service data")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing mesh service data: ${e.message}")
        }
    }

    private fun clearAllCryptographicData() {
        try {

            meshService.clearAllEncryptionData()

            try {
                val identityManager = SecureIdentityStateManager(getApplication())
                identityManager.clearIdentityData()

                try {
                    identityManager.clearSecureValues("favorite_relationships", "favorite_peerid_index")
                } catch (_: Exception) { }
                Log.d(TAG, "Cleared secure identity state and secure favorites store")
            } catch (e: Exception) {
                Log.d(TAG, "SecureIdentityStateManager not available or already cleared: ${e.message}")
            }

            try {
                FavoritesPersistenceService.shared.clearAllFavorites()
                Log.d(TAG, "Cleared FavoritesPersistenceService relationships")
            } catch (_: Exception) { }

            Log.d(TAG, "Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cryptographic data: ${e.message}")
        }
    }

    fun geohashParticipantCount(geohash: String): Int {
        return geohashViewModel.geohashParticipantCount(geohash)
    }

    fun beginGeohashSampling(geohashes: List<String>) {
        geohashViewModel.beginGeohashSampling(geohashes)
    }

    fun endGeohashSampling() {

    }

    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return geohashViewModel.isPersonTeleported(pubkeyHex)
    }

    fun startGeohashDM(pubkeyHex: String) {
        geohashViewModel.startGeohashDM(pubkeyHex) { convKey ->
            showPrivateChatSheet(convKey)
        }
    }

    fun selectLocationChannel(channel: com.cybersiren.android.geohash.ChannelID) {
        geohashViewModel.selectLocationChannel(channel)
    }

    fun blockUserInGeohash(targetNickname: String) {
        geohashViewModel.blockUserInGeohash(targetNickname)
    }

    fun showAppInfo() {
        state.setShowAppInfo(true)
    }

    fun hideAppInfo() {
        state.setShowAppInfo(false)
    }

    fun handleBackPressed(): Boolean {
        return when {

            state.getShowAppInfoValue() -> {
                hideAppInfo()
                true
            }

            state.getShowPasswordPromptValue() -> {
                state.setShowPasswordPrompt(false)
                state.setPasswordPromptChannel(null)
                true
            }

            state.getSelectedPrivateChatPeerValue() != null || state.getPrivateChatSheetPeerValue() != null -> {
                endPrivateChat()
                true
            }

            state.getCurrentChannelValue() != null -> {
                switchToChannel(null)
                true
            }

            else -> false
        }
    }

    fun colorForMeshPeer(peerID: String, isDark: Boolean): androidx.compose.ui.graphics.Color {

        val seed = "noise:${peerID.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }

    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        return geohashViewModel.colorForNostrPubkey(pubkeyHex, isDark)
}

}
