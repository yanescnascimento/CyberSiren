package com.cybersiren.android.mesh

import android.content.Context
import android.util.Log
import com.cybersiren.android.crypto.EncryptionService
import com.cybersiren.android.model.BitchatMessage
import com.cybersiren.android.protocol.MessagePadding
import com.cybersiren.android.model.RoutedPacket
import com.cybersiren.android.model.IdentityAnnouncement
import com.cybersiren.android.model.NoisePayload
import com.cybersiren.android.model.NoisePayloadType
import com.cybersiren.android.protocol.BitchatPacket
import com.cybersiren.android.protocol.MessageType
import com.cybersiren.android.protocol.SpecialRecipients
import com.cybersiren.android.model.RequestSyncPacket
import com.cybersiren.android.sync.GossipSyncManager
import com.cybersiren.android.util.toHexString
import com.cybersiren.android.services.VerificationService
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.sign
import kotlin.random.Random

class BluetoothMeshService(private val context: Context) {
    private val debugManager by lazy { try { com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "BluetoothMeshService"
        private val MAX_TTL: UByte = com.cybersiren.android.util.AppConstants.MESSAGE_TTL_HOPS
    }

    private val encryptionService = EncryptionService(context)

    val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val peerManager = PeerManager()
    private val fragmentManager = FragmentManager()
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID, context.applicationContext)
    internal val connectionManager = BluetoothConnectionManager(context, myPeerID, fragmentManager)
    private val packetProcessor = PacketProcessor(myPeerID)
    private lateinit var gossipSyncManager: GossipSyncManager

    private val serviceNotificationManager = com.cybersiren.android.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext),
        com.cybersiren.android.util.NotificationIntervalManager()
    )

    private var isActive = false

    var delegate: BluetoothMeshDelegate? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var terminated = false

    init {
        Log.i(TAG, "Initializing BluetoothMeshService for peer=$myPeerID")
        VerificationService.configure(encryptionService)
        setupDelegates()
        messageHandler.packetProcessor = packetProcessor

        gossipSyncManager = GossipSyncManager(
            myPeerID = myPeerID,
            scope = serviceScope,
            configProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = try {
                    com.cybersiren.android.ui.debug.DebugPreferenceManager.getSeenPacketCapacity(500)
                } catch (_: Exception) { 500 }

                override fun gcsMaxBytes(): Int = try {
                    com.cybersiren.android.ui.debug.DebugPreferenceManager.getGcsMaxFilterBytes(400)
                } catch (_: Exception) { 400 }

                override fun gcsTargetFpr(): Double = try {
                    com.cybersiren.android.ui.debug.DebugPreferenceManager.getGcsFprPercent(1.0) / 100.0
                } catch (_: Exception) { 0.01 }
            }
        )

        gossipSyncManager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) {
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                connectionManager.sendPacketToPeer(peerID, packet)
            }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                return signPacketBeforeBroadcast(packet)
            }
        }

        peerManager.isPeerDirectlyConnected = { peerID ->
            connectionManager.addressPeerMap.containsValue(peerID)
        }

        Log.d(TAG, "Delegates set up; GossipSyncManager initialized")
    }

    private fun startPeriodicDebugLogging() {
        serviceScope.launch {
            Log.d(TAG, "Starting periodic debug logging loop")
            while (isActive) {
                try {
                    delay(10000)
                    if (isActive) {
                        val debugInfo = getDebugStatus()
                        Log.d(TAG, "=== PERIODIC DEBUG STATUS ===\n$debugInfo\n=== END DEBUG STATUS ===")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic debug logging: ${e.message}")
                }
            }
            Log.d(TAG, "Periodic debug logging loop ended (isActive=$isActive)")
        }
    }

    private fun sendPeriodicBroadcastAnnounce() {
        serviceScope.launch {
            Log.d(TAG, "Starting periodic announce loop")
            while (isActive) {
                try {
                    delay(30000)
                    sendBroadcastAnnounce()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic broadcast announce: ${e.message}")
                }
            }
            Log.d(TAG, "Periodic announce loop ended (isActive=$isActive)")
        }
    }

    private fun setupDelegates() {
        Log.d(TAG, "Setting up component delegates")

        try {
            val resolver: (String) -> String? = { pid -> peerManager.getPeerNickname(pid) }
            connectionManager.setNicknameResolver(resolver)
            debugManager?.setNicknameResolver(resolver)
        } catch (_: Exception) { }

        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerListUpdated(peerIDs: List<String>) {

                try { com.cybersiren.android.services.AppStateStore.setPeers(peerIDs) } catch (_: Exception) { }

                delegate?.didUpdatePeerList(peerIDs)
            }
            override fun onPeerRemoved(peerID: String) {
                try { gossipSyncManager.removeAnnouncementForPeer(peerID) } catch (_: Exception) { }

                try { com.cybersiren.android.services.meshgraph.MeshGraphService.getInstance().removePeer(peerID) } catch (_: Exception) { }

                try {
                    encryptionService.removePeer(peerID)
                    Log.d(TAG, "Removed Noise session for offline peer $peerID")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove Noise session for $peerID: ${e.message}")
                }
            }
        }

        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {

                serviceScope.launch {
                    Log.d(TAG, "Key exchange completed with $peerID; sending follow-ups")
                    delay(100)
                    sendAnnouncementToPeer(peerID)

                    delay(1000)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }

            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {

                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    ttl = MAX_TTL
                )

                val signedPacket = signPacketBeforeBroadcast(responsePacket)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                Log.d(TAG, "Sent Noise handshake response to $peerID (${response.size} bytes)")
            }

            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }
        }

        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String): Boolean {
                return delegate?.isFavorite(peerID) ?: false
            }

            override fun isPeerOnline(peerID: String): Boolean {
                return peerManager.isPeerActive(peerID)
            }

            override fun sendPacket(packet: BitchatPacket) {
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
        }

        messageHandler.delegate = object : MessageHandlerDelegate {

            override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
                return peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun removePeer(peerID: String) {
                peerManager.removePeer(peerID)
            }

            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getMyNickname(): String? {
                return delegate?.getNickname()
            }

            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }

            override fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean): Boolean {
                return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
            }

            override fun sendPacket(packet: BitchatPacket) {

                val signedPacket = signPacketBeforeBroadcast(packet)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
            }

            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.verifySignature(packet, peerID)
            }

            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
                return securityManager.encryptForPeer(data, recipientPeerID)
            }

            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }

            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
                return encryptionService.verifyEd25519Signature(signature, data, publicKey)
            }

            override fun hasNoiseSession(peerID: String): Boolean {
                return encryptionService.hasEstablishedSession(peerID)
            }

            override fun initiateNoiseHandshake(peerID: String) {
                try {

                    val handshakeData = encryptionService.initiateHandshake(peerID)

                    if (handshakeData != null) {
                        val packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.NOISE_HANDSHAKE.value,
                            senderID = hexStringToByteArray(myPeerID),
                            recipientID = hexStringToByteArray(peerID),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = handshakeData,
                            ttl = MAX_TTL
                        )

                        val signedPacket = signPacketBeforeBroadcast(packet)
                        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                        Log.d(TAG, "Initiated Noise handshake with $peerID (${handshakeData.size} bytes)")
                    } else {
                        Log.w(TAG, "Failed to generate Noise handshake data for $peerID")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initiate Noise handshake with $peerID: ${e.message}")
                }
            }

            override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? {
                return try {
                    encryptionService.processHandshakeMessage(payload, peerID)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process handshake message from $peerID: ${e.message}")
                    null
                }
            }

            override fun updatePeerIDBinding(newPeerID: String, nickname: String,
                                           publicKey: ByteArray, previousPeerID: String?) {

                Log.d(TAG, "Updating peer ID binding: $newPeerID (was: $previousPeerID) with nickname: $nickname and public key: ${publicKey.toHexString().take(16)}...")

                peerManager.addOrUpdatePeer(newPeerID, nickname)

                val fingerprint = peerManager.storeFingerprintForPeer(newPeerID, publicKey)

                try {
                    com.cybersiren.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(publicKey)?.let { npub ->
                        com.cybersiren.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(newPeerID, npub)
                    }
                } catch (_: Exception) { }

                previousPeerID?.let { oldPeerID ->
                    peerManager.removePeer(oldPeerID)
                }

                Log.d(TAG, "Updated peer ID binding: $newPeerID (was: $previousPeerID), fingerprint: ${fingerprint.take(16)}...")
            }

            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return delegate?.decryptChannelMessage(encryptedContent, channel)
            }

            override fun onMessageReceived(message: BitchatMessage) {

                try {
                    when {
                        message.isPrivate -> {
                            val peer = message.senderPeerID ?: ""
                            if (peer.isNotEmpty()) com.cybersiren.android.services.AppStateStore.addPrivateMessage(peer, message)
                        }
                        message.channel != null -> {
                            com.cybersiren.android.services.AppStateStore.addChannelMessage(message.channel!!, message)
                        }
                        else -> {
                            com.cybersiren.android.services.AppStateStore.addPublicMessage(message)
                        }
                    }
                } catch (_: Exception) { }

                delegate?.didReceiveMessage(message)

                if (delegate == null && message.isPrivate) {
                    try {
                        val senderPeerID = message.senderPeerID
                        if (senderPeerID != null) {
                            val nick = try { peerManager.getPeerNickname(senderPeerID) } catch (_: Exception) { null } ?: senderPeerID
                            val preview = com.cybersiren.android.ui.NotificationTextUtils.buildPrivateMessagePreview(message)
                            serviceNotificationManager.setAppBackgroundState(true)
                            serviceNotificationManager.showPrivateMessageNotification(senderPeerID, nick, preview)
                        }
                    } catch (_: Exception) { }
                }
            }

            override fun onChannelLeave(channel: String, fromPeer: String) {
                delegate?.didReceiveChannelLeave(channel, fromPeer)
            }

            override fun onDeliveryAckReceived(messageID: String, peerID: String) {
                delegate?.didReceiveDeliveryAck(messageID, peerID)
            }

            override fun onReadReceiptReceived(messageID: String, peerID: String) {
                delegate?.didReceiveReadReceipt(messageID, peerID)
            }

            override fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                delegate?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
            }

            override fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                delegate?.didReceiveVerifyResponse(peerID, payload, timestampMs)
            }

            override fun onEmergencyAlertReceived(packet: BitchatPacket, fromPeerID: String) {

                delegate?.didReceiveEmergencyAlert(packet, fromPeerID)
            }
        }

        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.validatePacket(packet, peerID)
            }

            override fun updatePeerLastSeen(peerID: String) {
                peerManager.updatePeerLastSeen(peerID)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
                return runBlocking { securityManager.handleNoiseHandshake(routed) }
            }

            override fun handleNoiseEncrypted(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleNoiseEncrypted(routed) }
            }

            override fun handleAnnounce(routed: RoutedPacket) {
                serviceScope.launch {

                    val isFirst = messageHandler.handleAnnounce(routed)

                    val deviceAddress = routed.relayAddress
                    val pid = routed.peerID
                    if (deviceAddress != null && pid != null) {

                        val isDirect = routed.packet.ttl == com.cybersiren.android.util.AppConstants.MESSAGE_TTL_HOPS

                        if (isDirect) {

                            connectionManager.addressPeerMap[deviceAddress] = pid
                            Log.d(TAG, "Mapped device $deviceAddress to peer $pid (TTL=${routed.packet.ttl})")

                            try { peerManager.refreshPeerList() } catch (_: Exception) { }

                            try { gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000) } catch (_: Exception) { }
                        }
                    }

                    try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                }
            }

            override fun handleMessage(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleMessage(routed) }

                try {
                    val pkt = routed.packet
                    val isBroadcast = (pkt.recipientID == null || pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && pkt.type == MessageType.MESSAGE.value) {
                        gossipSyncManager.onPublicPacketSeen(pkt)
                    }
                } catch (_: Exception) { }
            }

            override fun handleLeave(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleLeave(routed) }
            }

            override fun handleFragment(packet: BitchatPacket): BitchatPacket? {

                try {
                    val isBroadcast = (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && packet.type == MessageType.FRAGMENT.value) {
                        gossipSyncManager.onPublicPacketSeen(packet)
                    }
                } catch (_: Exception) { }
                return fragmentManager.handleFragment(packet)
            }

            override fun sendAnnouncementToPeer(peerID: String) {
                this@BluetoothMeshService.sendAnnouncementToPeer(peerID)
            }

            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }

            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
            }

            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return connectionManager.sendToPeer(peerID, routed)
            }

            override fun handleRequestSync(routed: RoutedPacket) {

                val fromPeer = routed.peerID ?: return
                val req = RequestSyncPacket.decode(routed.packet.payload) ?: return
                gossipSyncManager.handleRequestSync(fromPeer, req)
            }

            override fun handleEmergencyAlert(routed: RoutedPacket) {

                serviceScope.launch { messageHandler.handleEmergencyAlert(routed) }
            }
        }

        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
        override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: android.bluetooth.BluetoothDevice?) {

            try {
                com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance().logIncoming(
                    packet = packet,
                    fromPeerID = peerID,
                    fromNickname = null,
                    fromDeviceAddress = device?.address,
                    myPeerID = myPeerID
                )
            } catch (_: Exception) { }
            packetProcessor.processPacket(RoutedPacket(packet, peerID, device?.address))
        }

            override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
                val addr = device.address
                Log.i(TAG, "Device connected: $addr")

                serviceScope.launch {
                    delay(200)
                    Log.d(TAG, "Sending broadcast announce after connection from $addr")
                    sendBroadcastAnnounce()
                }

                val existingPeer = connectionManager.addressPeerMap[addr]
                if (existingPeer == null) {

                    val tempPeerId = "dev_${addr.replace(":", "").takeLast(8).lowercase()}"
                    Log.i(TAG, "Adding temporary peer: $tempPeerId for device $addr")
                    peerManager.addOrUpdatePeer(tempPeerId, "Device $addr")
                    connectionManager.addressPeerMap[addr] = tempPeerId
                }

                try {
                    val peer = connectionManager.addressPeerMap[addr]
                    val nick = peer?.let { peerManager.getPeerNickname(it) } ?: "unknown"
                    com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance()
                        .logPeerConnection(peer ?: "unknown", nick, addr, isInbound = !connectionManager.isClientConnection(addr)!!)
                } catch (_: Exception) { }
            }

            override fun onDeviceDisconnected(device: android.bluetooth.BluetoothDevice) {
                val addr = device.address
                Log.i(TAG, "Device disconnected: $addr")

                val peer = connectionManager.addressPeerMap[addr]

                connectionManager.addressPeerMap.remove(addr)

                try { peerManager.refreshPeerList() } catch (_: Exception) { }

                if (peer != null) {
                    Log.d(TAG, "Removing peer $peer after device $addr disconnected")

                    if (peer.startsWith("dev_")) {
                        peerManager.removePeer(peer)
                    }

                    try {
                        val nick = peerManager.getPeerNickname(peer) ?: "unknown"
                        com.cybersiren.android.ui.debug.DebugSettingsManager.getInstance()
                            .logPeerDisconnection(peer, nick, addr)
                    } catch (_: Exception) { }
                }
            }

            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {

                connectionManager.addressPeerMap[deviceAddress]?.let { peerID ->
                    peerManager.updatePeerRSSI(peerID, rssi)
                }
            }
        }
    }

    fun startServices() {

        if (isActive) {
            Log.w(TAG, "Mesh service already active, ignoring duplicate start request")
            return
        }
        if (terminated) {

            Log.e(TAG, "Mesh service instance was terminated; create a new instance instead of restarting")
            return
        }

        Log.i(TAG, "Starting Bluetooth mesh service with peer ID: $myPeerID")

        if (connectionManager.startServices()) {
            isActive = true

            sendPeriodicBroadcastAnnounce()
            Log.d(TAG, "Started periodic broadcast announcements (every 30 seconds)")

            gossipSyncManager.start()
            Log.d(TAG, "GossipSyncManager started")
        } else {
            Log.e(TAG, "Failed to start Bluetooth services")
        }
    }

    fun stopServices() {
        if (!isActive) {
            Log.w(TAG, "Mesh service not active, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping Bluetooth mesh service")
        isActive = false

        sendLeaveAnnouncement()

        serviceScope.launch {
            Log.d(TAG, "Stopping subcomponents and cancelling scope...")
            delay(200)

            gossipSyncManager.stop()
            Log.d(TAG, "GossipSyncManager stopped")
            connectionManager.stopServices()
            Log.d(TAG, "BluetoothConnectionManager stop requested")
            peerManager.shutdown()
            fragmentManager.shutdown()
            securityManager.shutdown()
            storeForwardManager.shutdown()
            messageHandler.shutdown()
            packetProcessor.shutdown()

            terminated = true
            serviceScope.cancel()
            Log.i(TAG, "BluetoothMeshService terminated and scope cancelled")
        }
    }

    fun isReusable(): Boolean {
        val reusable = !terminated && serviceScope.isActive && connectionManager.isReusable()
        if (!reusable) {
            Log.d(TAG, "isReusable=false (terminated=$terminated, scopeActive=${serviceScope.isActive}, connReusable=${connectionManager.isReusable()})")
        }
        return reusable
    }

    fun broadcastPacket(packet: BitchatPacket) {
        serviceScope.launch {
            val signedPacket = signPacketBeforeBroadcast(packet)
            connectionManager.broadcastPacket(RoutedPacket(signedPacket))
            Log.d(TAG, "Broadcast packet type ${packet.type}")
        }
    }

    fun getConnectedPeerCount(): Int = peerManager.getActivePeerCount()

    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        if (content.isEmpty()) return

        serviceScope.launch {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = content.toByteArray(Charsets.UTF_8),
                signature = null,
                ttl = MAX_TTL
            )

            val signedPacket = signPacketBeforeBroadcast(packet)
            connectionManager.broadcastPacket(RoutedPacket(signedPacket))

            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    fun sendFileBroadcast(file: com.cybersiren.android.model.BitchatFilePacket) {
        try {
            Log.d(TAG, "sendFileBroadcast: name=${file.fileName}, size=${file.fileSize}")
            val payload = file.encode()
            if (payload == null) {
                Log.e(TAG, "Failed to encode file packet in sendFileBroadcast")
                return
            }
            Log.d(TAG, "Encoded payload: ${payload.size} bytes")
        serviceScope.launch {
            val packet = BitchatPacket(
                version = 2u,
                type = MessageType.FILE_TRANSFER.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = MAX_TTL
            )
            val signed = signPacketBeforeBroadcast(packet)

            val transferId = sha256Hex(payload)
            connectionManager.broadcastPacket(RoutedPacket(signed, transferId = transferId))
            try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
        }
            } catch (e: Exception) {
            Log.e(TAG, "sendFileBroadcast failed: ${e.message}", e)
            Log.e(TAG, "File: name=${file.fileName}, size=${file.fileSize}")
        }
    }

    fun sendFilePrivate(recipientPeerID: String, file: com.cybersiren.android.model.BitchatFilePacket) {
        try {
            Log.d(TAG, "sendFilePrivate (ENCRYPTED): to=$recipientPeerID, name=${file.fileName}, size=${file.fileSize}")

            serviceScope.launch {

                if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                    try {

                        val filePayload = file.encode()
                        if (filePayload == null) {
                            Log.e(TAG, "Failed to encode file packet for private send")
                            return@launch
                        }
                        Log.d(TAG, "Encoded file TLV: ${filePayload.size} bytes")

                        val noisePayload = com.cybersiren.android.model.NoisePayload(
                            type = com.cybersiren.android.model.NoisePayloadType.FILE_TRANSFER,
                            data = filePayload
                        )

                        val encrypted = encryptionService.encrypt(noisePayload.encode(), recipientPeerID)
                        if (encrypted == null) {
                            Log.e(TAG, "Failed to encrypt file for $recipientPeerID")
                            return@launch
                        }
                        Log.d(TAG, "Encrypted file payload: ${encrypted.size} bytes")

                        val packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.NOISE_ENCRYPTED.value,
                            senderID = hexStringToByteArray(myPeerID),
                            recipientID = hexStringToByteArray(recipientPeerID),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = encrypted,
                            signature = null,
                            ttl = com.cybersiren.android.util.AppConstants.MESSAGE_TTL_HOPS
                        )

                        val signed = signPacketBeforeBroadcast(packet)

                        val transferId = sha256Hex(filePayload)
                        connectionManager.broadcastPacket(RoutedPacket(signed, transferId = transferId))
                        Log.d(TAG, "Sent encrypted file to $recipientPeerID")

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to encrypt file for $recipientPeerID: ${e.message}", e)
                    }
                } else {

                    Log.w(TAG, "No Noise session with $recipientPeerID for file transfer, initiating handshake")
                    messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendFilePrivate failed: ${e.message}", e)
            Log.e(TAG, "File: to=$recipientPeerID, name=${file.fileName}, size=${file.fileSize}")
        }
    }

    fun cancelFileTransfer(transferId: String): Boolean {
        return connectionManager.cancelTransfer(transferId)
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { bytes.size.toString(16) }

    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty()) return
        if (recipientNickname.isEmpty()) return

        serviceScope.launch {
            val finalMessageID = messageID ?: java.util.UUID.randomUUID().toString()

            Log.d(TAG, "Sending PM to $recipientPeerID: ${content.take(30)}...")

            if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                try {

                    val privateMessage = com.cybersiren.android.model.PrivateMessagePacket(
                        messageID = finalMessageID,
                        content = content
                    )

                    val tlvData = privateMessage.encode()
                    if (tlvData == null) {
                        Log.e(TAG, "Failed to encode private message with TLV")
                        return@launch
                    }

                    val messagePayload = com.cybersiren.android.model.NoisePayload(
                        type = com.cybersiren.android.model.NoisePayloadType.PRIVATE_MESSAGE,
                        data = tlvData
                    )

                    val encrypted = encryptionService.encrypt(messagePayload.encode(), recipientPeerID)

                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = hexStringToByteArray(recipientPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encrypted,
                        signature = null,
                        ttl = MAX_TTL
                    )

                    val signedPacket = signPacketBeforeBroadcast(packet)
                    connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                    Log.d(TAG, "Sent encrypted private message to $recipientPeerID (${encrypted.size} bytes)")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt private message for $recipientPeerID: ${e.message}")
                }
            } else {

                Log.d(TAG, "No session with $recipientPeerID, initiating handshake")
                messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)

            }
        }
    }

    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        serviceScope.launch {
            Log.d(TAG, "Sending read receipt for message $messageID to $recipientPeerID")

            val geo = runCatching { com.cybersiren.android.services.MessageRouter.tryGetInstance() }.getOrNull()
            val isGeoAlias = try {
                val map = com.cybersiren.android.nostr.GeohashAliasRegistry.snapshot()
                map.containsKey(recipientPeerID)
            } catch (_: Exception) { false }
            if (isGeoAlias && geo != null) {
                geo.sendReadReceipt(com.cybersiren.android.model.ReadReceipt(messageID), recipientPeerID)
                return@launch
            }

            try {

                val seenStore = try { com.cybersiren.android.services.SeenMessageStore.getInstance(context.applicationContext) } catch (_: Exception) { null }
                if (seenStore?.hasRead(messageID) == true) {
                    Log.d(TAG, "Skipping read receipt for $messageID - already marked read")
                    return@launch
                }

                val readReceiptPayload = com.cybersiren.android.model.NoisePayload(
                    type = com.cybersiren.android.model.NoisePayloadType.READ_RECEIPT,
                    data = messageID.toByteArray(Charsets.UTF_8)
                )

                val encrypted = encryptionService.encrypt(readReceiptPayload.encode(), recipientPeerID)

                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = com.cybersiren.android.util.AppConstants.MESSAGE_TTL_HOPS
                )

                val signedPacket = signPacketBeforeBroadcast(packet)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                Log.d(TAG, "Sent read receipt to $recipientPeerID for message $messageID")

                try { seenStore?.markRead(messageID) } catch (_: Exception) { }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt to $recipientPeerID: ${e.message}")
            }
        }
    }

    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = VerificationService.buildVerifyChallenge(noiseKeyHex, nonceA)
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_CHALLENGE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID, "verify challenge")
    }

    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = VerificationService.buildVerifyResponse(noiseKeyHex, nonceA) ?: return
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_RESPONSE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID, "verify response")
    }

    private fun sendNoisePayloadToPeer(payload: NoisePayload, recipientPeerID: String, label: String) {
        serviceScope.launch {
            try {
                val encrypted = encryptionService.encrypt(payload.encode(), recipientPeerID)
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = com.cybersiren.android.util.AppConstants.MESSAGE_TTL_HOPS
                )

                val signedPacket = signPacketBeforeBroadcast(packet)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                Log.d(TAG, "Sent $label to $recipientPeerID (${payload.data.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send $label to $recipientPeerID: ${e.message}")
            }
        }
    }

    fun sendBroadcastAnnounce() {
        Log.d(TAG, "Sending broadcast announce")
        serviceScope.launch {
            val nickname = try { com.cybersiren.android.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { myPeerID }

            val staticKey = encryptionService.getStaticPublicKey()
            if (staticKey == null) {
                Log.e(TAG, "No static public key available for announcement")
                return@launch
            }

            val signingKey = encryptionService.getSigningPublicKey()
            if (signingKey == null) {
                Log.e(TAG, "No signing public key available for announcement")
                return@launch
            }

            val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
            var tlvPayload = announcement.encode()
            if (tlvPayload == null) {
                Log.e(TAG, "Failed to encode announcement as TLV")
                return@launch
            }

            try {
                val directPeers = getDirectPeerIDsForGossip()
                if (directPeers.isNotEmpty()) {
                    val gossip = com.cybersiren.android.services.meshgraph.GossipTLV.encodeNeighbors(directPeers)
                    tlvPayload = tlvPayload + gossip
                }

                try {
                    com.cybersiren.android.services.meshgraph.MeshGraphService.getInstance()
                        .updateFromAnnouncement(myPeerID, nickname, directPeers, System.currentTimeMillis().toULong())
                } catch (_: Exception) { }
            } catch (_: Exception) { }

            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = MAX_TTL,
                senderID = myPeerID,
                payload = tlvPayload
            )

            val signedPacket = encryptionService.signData(announcePacket.toBinaryDataForSigning()!!)?.let { signature ->
                announcePacket.copy(signature = signature)
            } ?: announcePacket

            connectionManager.broadcastPacket(RoutedPacket(signedPacket))
            Log.d(TAG, "Sent iOS-compatible signed TLV announce (${tlvPayload.size} bytes)")

            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return

        val nickname = try { com.cybersiren.android.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { myPeerID }

        val staticKey = encryptionService.getStaticPublicKey()
        if (staticKey == null) {
            Log.e(TAG, "No static public key available for peer announcement")
            return
        }

        val signingKey = encryptionService.getSigningPublicKey()
        if (signingKey == null) {
            Log.e(TAG, "No signing public key available for peer announcement")
            return
        }

        val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
        var tlvPayload = announcement.encode()
        if (tlvPayload == null) {
            Log.e(TAG, "Failed to encode peer announcement as TLV")
            return
        }

        try {
            val directPeers = getDirectPeerIDsForGossip()
            if (directPeers.isNotEmpty()) {
                val gossip = com.cybersiren.android.services.meshgraph.GossipTLV.encodeNeighbors(directPeers)
                tlvPayload = tlvPayload + gossip
            }

            try {
                com.cybersiren.android.services.meshgraph.MeshGraphService.getInstance()
                    .updateFromAnnouncement(myPeerID, nickname, directPeers, System.currentTimeMillis().toULong())
            } catch (_: Exception) { }
        } catch (_: Exception) { }

        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = tlvPayload
        )

        val signedPacket = encryptionService.signData(packet.toBinaryDataForSigning()!!)?.let { signature ->
            packet.copy(signature = signature)
        } ?: packet

        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
        peerManager.markPeerAsAnnouncedTo(peerID)
        Log.d(TAG, "Sent iOS-compatible signed TLV peer announce to $peerID (${tlvPayload.size} bytes)")

        try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
    }

    private fun getDirectPeerIDsForGossip(): List<String> {
        return try {

            val verified = peerManager.getVerifiedPeers()
            val direct = verified.filter { it.value.isDirectConnection }.keys.toList()
            direct.take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sendLeaveAnnouncement() {
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = byteArrayOf()
        )

        val signedPacket = signPacketBeforeBroadcast(packet)
        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
    }

    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()

    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()

    fun hasEstablishedSession(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }

    fun getSessionState(peerID: String): com.cybersiren.android.noise.NoiseSession.NoiseSessionState {
        return encryptionService.getSessionState(peerID)
    }

    fun initiateNoiseHandshake(peerID: String) {

        messageHandler.delegate?.initiateNoiseHandshake(peerID)
    }

    fun getPeerFingerprint(peerID: String): String? {
        return peerManager.getFingerprintForPeer(peerID)
    }

    fun getActivePeerCount(): Int {
        return try { peerManager.getActivePeerCount() } catch (_: Exception) { 0 }
    }

    fun getPeerInfo(peerID: String): PeerInfo? {
        return peerManager.getPeerInfo(peerID)
    }

    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
    }

    fun getIdentityFingerprint(): String {
        return encryptionService.getIdentityFingerprint()
    }

    fun getStaticNoisePublicKey(): ByteArray? {
        return encryptionService.getStaticPublicKey()
    }

    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }

    fun getEncryptedPeers(): List<String> {

        return emptyList()
    }

    fun getDeviceAddressForPeer(peerID: String): String? {
        return connectionManager.addressPeerMap.entries.find { it.value == peerID }?.key
    }

    fun getDeviceAddressToPeerMapping(): Map<String, String> {
        return connectionManager.addressPeerMap.toMap()
    }

    fun printDeviceAddressesForPeers(): String {
        return peerManager.getDebugInfoWithDeviceAddresses(connectionManager.addressPeerMap)
    }

    fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Bluetooth Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine()
            appendLine(connectionManager.getDebugInfo())
            appendLine()
            appendLine(peerManager.getDebugInfo(connectionManager.addressPeerMap))
            appendLine()
            appendLine(peerManager.getFingerprintDebugInfo())
            appendLine()
            appendLine(fragmentManager.getDebugInfo())
            appendLine()
            appendLine(securityManager.getDebugInfo())
            appendLine()
            appendLine(storeForwardManager.getDebugInfo())
            appendLine()
            appendLine(messageHandler.getDebugInfo())
            appendLine()
            appendLine(packetProcessor.getDebugInfo())
        }
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0

        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }

        return result
    }

    private fun signPacketBeforeBroadcast(packet: BitchatPacket): BitchatPacket {
        return try {

            val withRoute = try {
                val rec = packet.recipientID
                if (rec != null && !rec.contentEquals(SpecialRecipients.BROADCAST)) {
                    val dest = rec.joinToString("") { b -> "%02x".format(b) }
                    val path = com.cybersiren.android.services.meshgraph.RoutePlanner.shortestPath(myPeerID, dest)
                    if (path != null && path.size >= 3) {

                        val intermediates = path.subList(1, path.size - 1)
                        val hopsBytes = intermediates.map { hexStringToByteArray(it) }
                        Log.d(TAG, "Signed packet type ${packet.type} (route ${hopsBytes.size} hops: $intermediates)")

                        packet.copy(route = hopsBytes, version = 2u)
                    } else packet.copy(route = null)
                } else packet
            } catch (_: Exception) { packet }

            val packetDataForSigning = withRoute.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "Failed to encode packet type ${packet.type} for signing, sending unsigned")
                return withRoute
            }

            val signature = encryptionService.signData(packetDataForSigning)
            if (signature != null) {
                Log.d(TAG, "Signed packet type ${packet.type} (signature ${signature.size} bytes)")
                withRoute.copy(signature = signature)
            } else {
                Log.w(TAG, "Failed to sign packet type ${packet.type}, sending unsigned")
                withRoute
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error signing packet type ${packet.type}: ${e.message}, sending unsigned")
            packet
        }
    }

    fun clearAllInternalData() {
        Log.w(TAG, "Clearing all mesh service internal data")
        try {

            stopServices()

            fragmentManager.clearAllFragments()
            storeForwardManager.clearAllCache()
            securityManager.clearAllData()
            peerManager.clearAllPeers()
            peerManager.clearAllFingerprints()
            Log.d(TAG, "Cleared all mesh service internal data")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing mesh service internal data: ${e.message}")
        }
    }

    fun clearAllEncryptionData() {
        Log.w(TAG, "Clearing all encryption data")
        try {

            encryptionService.clearPersistentIdentity()
            Log.d(TAG, "Cleared all encryption data")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing encryption data: ${e.message}")
        }
    }
}

interface BluetoothMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long)
    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean

    fun didReceiveEmergencyAlert(packet: BitchatPacket, fromPeerID: String) {}
}
