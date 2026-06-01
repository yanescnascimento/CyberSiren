package com.cybersiren.android.nostr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NostrClient private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NostrClient"

        @Volatile
        private var INSTANCE: NostrClient? = null

        fun getInstance(context: Context): NostrClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NostrClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val relayManager = NostrRelayManager.shared
    private var currentIdentity: NostrIdentity? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _currentNpub = MutableStateFlow<String?>(null)
    val currentNpub: StateFlow<String?> = _currentNpub.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        Log.d(TAG, "Initializing Nostr client")
    }

    fun initialize() {
        scope.launch {
            try {

                currentIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)

                if (currentIdentity != null) {
                    _currentNpub.value = currentIdentity!!.npub
                    Log.i(TAG, "Nostr identity loaded: ${currentIdentity!!.getShortNpub()}")

                    relayManager.connect()

                    _isInitialized.value = true
                    Log.i(TAG, "Nostr client initialized successfully")
                } else {
                    Log.e(TAG, "Failed to load/create Nostr identity")
                    _isInitialized.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Nostr client: ${e.message}")
                _isInitialized.value = false
            }
        }
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down Nostr client")
        relayManager.disconnect()
        _isInitialized.value = false
    }

    fun sendPrivateMessage(
        content: String,
        recipientNpub: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val identity = currentIdentity
        if (identity == null) {
            onError?.invoke("Nostr client not initialized")
            return
        }

        scope.launch {
            try {

                val (hrp, pubkeyBytes) = Bech32.decode(recipientNpub)
                if (hrp != "npub") {
                    onError?.invoke("Invalid npub format")
                    return@launch
                }

                val recipientPubkeyHex = pubkeyBytes.toHexString()

                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = content,
                    recipientPubkey = recipientPubkeyHex,
                    senderIdentity = identity
                )

                giftWraps.forEach { wrap ->
                    NostrRelayManager.registerPendingGiftWrap(wrap.id)
                    relayManager.sendEvent(wrap)
                }

                Log.i(TAG, "Sent private message to ${recipientNpub.take(16)}...")
                onSuccess?.invoke()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send private message: ${e.message}")
                onError?.invoke("Failed to send message: ${e.message}")
            }
        }
    }

    fun subscribeToPrivateMessages(handler: (content: String, senderNpub: String, timestamp: Int) -> Unit) {
        val identity = currentIdentity
        if (identity == null) {
            Log.e(TAG, "Cannot subscribe to private messages: client not initialized")
            return
        }

        val filter = NostrFilter.giftWrapsFor(
            pubkey = identity.publicKeyHex,
            since = System.currentTimeMillis() - 172800000L
        )

        relayManager.subscribe(filter, "private-messages", { giftWrap ->
            scope.launch {
                handlePrivateMessage(giftWrap, handler)
            }
        })

        Log.i(TAG, "Subscribed to private messages for: ${identity.getShortNpub()}")
    }

    fun sendGeohashMessage(
        content: String,
        geohash: String,
        nickname: String? = null,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        scope.launch {
            try {

                val geohashIdentity = NostrIdentityBridge.deriveIdentity(geohash, context)

                val event = NostrProtocol.createEphemeralGeohashEvent(
                    content = content,
                    geohash = geohash,
                    senderIdentity = geohashIdentity,
                    nickname = nickname
                )

                relayManager.sendEvent(event)

                Log.i(TAG, "Sent geohash message to #$geohash")
                onSuccess?.invoke()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash message: ${e.message}")
                onError?.invoke("Failed to send message: ${e.message}")
            }
        }
    }

    fun subscribeToGeohash(
        geohash: String,
        handler: (content: String, senderPubkey: String, nickname: String?, timestamp: Int) -> Unit
    ) {
        val filter = NostrFilter.geohashEphemeral(
            geohash = geohash,
            since = System.currentTimeMillis() - 3600000L,
            limit = 200
        )

        relayManager.subscribe(filter, "geohash-$geohash", { event ->
            scope.launch {
                handleGeohashMessage(event, handler)
            }
        })

        Log.i(TAG, "Subscribed to geohash channel: #$geohash")
    }

    fun unsubscribeFromGeohash(geohash: String) {
        relayManager.unsubscribe("geohash-$geohash")
        Log.i(TAG, "Unsubscribed from geohash channel: #$geohash")
    }

    fun getCurrentIdentity(): NostrIdentity? = currentIdentity

    val relayConnectionStatus: StateFlow<Boolean> = relayManager.isConnected

    val relayInfo: StateFlow<List<NostrRelayManager.Relay>> = relayManager.relays

    private suspend fun handlePrivateMessage(
        giftWrap: NostrEvent,
        handler: (content: String, senderNpub: String, timestamp: Int) -> Unit
    ) {

        val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
        if (messageAge > 173700) {
            Log.v(TAG, "Ignoring old private message")
            return
        }

        val identity = currentIdentity ?: return

        try {
            val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
            if (decryptResult != null) {
                val (content, senderPubkey, timestamp) = decryptResult

                val senderNpub = try {
                    Bech32.encode("npub", senderPubkey.hexToByteArray())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to encode sender npub: ${e.message}")
                    "npub_decode_error"
                }

                Log.d(TAG, "Received private message from ${senderNpub.take(16)}...")

                withContext(Dispatchers.Main) {
                    handler(content, senderNpub, timestamp)
                }
            } else {
                Log.w(TAG, "Failed to decrypt private message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling private message: ${e.message}")
        }
    }

    private suspend fun handleGeohashMessage(
        event: NostrEvent,
        handler: (content: String, senderPubkey: String, nickname: String?, timestamp: Int) -> Unit
    ) {
        try {

            val powSettings = PoWPreferenceManager.getCurrentSettings()
            if (powSettings.enabled && powSettings.difficulty > 0) {
                if (!NostrProofOfWork.validateDifficulty(event, powSettings.difficulty)) {
                    Log.w(TAG, "Rejecting geohash event ${event.id.take(8)}... due to insufficient PoW (required: ${powSettings.difficulty})")
                    return
                }
                Log.v(TAG, "PoW validation passed for geohash event ${event.id.take(8)}...")
            }

            val nickname = event.tags.find { it.size >= 2 && it[0] == "n" }?.get(1)

            Log.v(TAG, "Received geohash message from ${event.pubkey.take(16)}...")

            withContext(Dispatchers.Main) {
                handler(event.content, event.pubkey, nickname, event.createdAt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling geohash message: ${e.message}")
        }
    }
}
