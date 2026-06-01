package com.cybersiren.android.nostr

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

class NostrRelayManager private constructor() {

    companion object {
        @JvmStatic
        val shared = NostrRelayManager()

        private const val TAG = "NostrRelayManager"

        fun getInstance(context: android.content.Context): NostrRelayManager {
            return shared
        }

        private val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr21.com"
        )

        private const val INITIAL_BACKOFF_INTERVAL = com.cybersiren.android.util.AppConstants.Nostr.INITIAL_BACKOFF_INTERVAL_MS
        private const val MAX_BACKOFF_INTERVAL = com.cybersiren.android.util.AppConstants.Nostr.MAX_BACKOFF_INTERVAL_MS
        private const val BACKOFF_MULTIPLIER = com.cybersiren.android.util.AppConstants.Nostr.BACKOFF_MULTIPLIER
        private const val MAX_RECONNECT_ATTEMPTS = com.cybersiren.android.util.AppConstants.Nostr.MAX_RECONNECT_ATTEMPTS

        private val pendingGiftWrapIDs = ConcurrentHashMap.newKeySet<String>()

        fun registerPendingGiftWrap(id: String) {
            pendingGiftWrapIDs.add(id)
        }

        fun defaultRelays(): List<String> = DEFAULT_RELAYS
    }

    data class Relay(
        val url: String,
        var isConnected: Boolean = false,
        var lastError: Throwable? = null,
        var lastConnectedAt: Long? = null,
        var messagesSent: Int = 0,
        var messagesReceived: Int = 0,
        var reconnectAttempts: Int = 0,
        var lastDisconnectedAt: Long? = null,
        var nextReconnectTime: Long? = null
    )

    private val _relays = MutableStateFlow<List<Relay>>(emptyList())
    val relays: StateFlow<List<Relay>> = _relays.asStateFlow()

    private val _isConnected = MutableStateFlow<Boolean>(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val relaysList = mutableListOf<Relay>()
    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val subscriptions = ConcurrentHashMap<String, Set<String>>()
    private val messageHandlers = ConcurrentHashMap<String, (NostrEvent) -> Unit>()

    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionInfo>()

    data class SubscriptionInfo(
        val id: String,
        val filter: NostrFilter,
        val handler: (NostrEvent) -> Unit,
        val targetRelayUrls: Set<String>? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val originGeohash: String? = null
    )

    private val eventDeduplicator = NostrEventDeduplicator.getInstance()

    private val messageQueue = mutableListOf<Pair<NostrEvent, List<String>>>()
    private val messageQueueLock = Any()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var subscriptionValidationJob: Job? = null
    private val SUBSCRIPTION_VALIDATION_INTERVAL = com.cybersiren.android.util.AppConstants.Nostr.SUBSCRIPTION_VALIDATION_INTERVAL_MS

    private val httpClient: OkHttpClient
        get() = com.cybersiren.android.net.OkHttpProvider.webSocketClient()

    private val gson by lazy { NostrRequest.createGson() }

    private val geohashToRelays = ConcurrentHashMap<String, Set<String>>()

    fun ensureGeohashRelaysConnected(geohash: String, nRelays: Int = 5, includeDefaults: Boolean = false) {
        try {
            val nearest = RelayDirectory.closestRelaysForGeohash(geohash, nRelays)
            val selected = if (includeDefaults) {
                (nearest + Companion.defaultRelays()).toSet()
            } else nearest.toSet()
            if (selected.isEmpty()) {
                Log.w(TAG, "No relays selected for geohash=$geohash")
                return
            }
            geohashToRelays[geohash] = selected
            Log.i(TAG, "Geohash $geohash using ${selected.size} relays: ${selected.joinToString()}")
            ensureConnectionsFor(selected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure relays for $geohash: ${e.message}")
        }
    }

    fun getRelaysForGeohash(geohash: String): List<String> {
        return geohashToRelays[geohash]?.toList() ?: emptyList()
    }

    fun subscribeForGeohash(
        geohash: String,
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        includeDefaults: Boolean = false,
        nRelays: Int = 5
    ): String {
        ensureGeohashRelaysConnected(geohash, nRelays, includeDefaults)
        val relayUrls = getRelaysForGeohash(geohash)
        Log.d(TAG, "Subscribing id=$id for geohash=$geohash on ${relayUrls.size} relays")
        return subscribe(
            filter = filter,
            id = id,
            handler = handler,
            targetRelayUrls = relayUrls
        ).also {

            activeSubscriptions[it]?.let { sub ->
                activeSubscriptions[it] = sub.copy(originGeohash = geohash)
            }
        }
    }

    fun sendEventToGeohash(event: NostrEvent, geohash: String, includeDefaults: Boolean = false, nRelays: Int = 5) {
        ensureGeohashRelaysConnected(geohash, nRelays, includeDefaults)
        val relayUrls = getRelaysForGeohash(geohash)
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No target relays to send event for geohash=$geohash; falling back to defaults")
            sendEvent(event, Companion.defaultRelays())
            return
        }
        Log.v(TAG, "Sending event kind=${event.kind} to ${relayUrls.size} relays for geohash=$geohash")
        sendEvent(event, relayUrls)
    }

    private fun ensureConnectionsFor(relayUrls: Set<String>) {

        relayUrls.forEach { url ->
            if (relaysList.none { it.url == url }) {
                relaysList.add(Relay(url))
            }
        }
        updateRelaysList()

        scope.launch {
            relayUrls.forEach { relayUrl ->
                launch {
                    if (!connections.containsKey(relayUrl)) {
                        connectToRelay(relayUrl)
                    }
                }
            }
        }
    }

    init {

        try {
            val defaultRelayUrls = listOf(
                "wss://relay.damus.io",
                "wss://relay.primal.net",
                "wss://offchain.pub",
                "wss://nostr21.com"
            )
            relaysList.addAll(defaultRelayUrls.map { Relay(it) })
            _relays.value = relaysList.toList()
            updateConnectionStatus()
            Log.d(TAG, "NostrRelayManager initialized with ${relaysList.size} default relays")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NostrRelayManager: ${e.message}", e)

            _relays.value = emptyList()
            _isConnected.value = false
        }
    }

    fun connect() {
        Log.d(TAG, "Connecting to ${relaysList.size} Nostr relays")

        scope.launch {
            relaysList.forEach { relay ->
                launch {
                    connectToRelay(relay.url)
                }
            }
        }

        startSubscriptionValidation()
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from all relays")

        stopSubscriptionValidation()

        connections.values.forEach { webSocket ->
            webSocket.close(1000, "Manual disconnect")
        }
        connections.clear()

        subscriptions.clear()

        updateConnectionStatus()
    }

    fun sendEvent(event: NostrEvent, relayUrls: List<String>? = null) {
        val targetRelays = relayUrls ?: relaysList.map { it.url }

        synchronized(messageQueueLock) {
            messageQueue.add(Pair(event, targetRelays))
        }

        scope.launch {
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    sendToRelay(event, webSocket, relayUrl)
                }
            }
        }
    }

    fun subscribe(
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: List<String>? = null
    ): String {

        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet()
        )

        activeSubscriptions[id] = subscriptionInfo
        messageHandlers[id] = handler

        Log.d(TAG, "Subscribing to Nostr filter id=$id ${filter.getDebugDescription()}")

        sendSubscriptionToRelays(subscriptionInfo)

        return id
    }

    private fun sendSubscriptionToRelays(subscriptionInfo: SubscriptionInfo) {
        val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
        val message = gson.toJson(request, NostrRequest::class.java)

        Log.v(TAG, "DEBUG: Serialized subscription message: $message")

        scope.launch {
            val targetRelays = subscriptionInfo.targetRelayUrls?.toList() ?: connections.keys.toList()

            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    try {
                        val success = webSocket.send(message)
                        if (success) {

                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            subscriptions[relayUrl] = currentSubs + subscriptionInfo.id

                            Log.v(TAG, "Subscription '${subscriptionInfo.id}' sent to relay: $relayUrl")
                        } else {
                            Log.w(TAG, "Failed to send subscription to $relayUrl: WebSocket send failed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send subscription to $relayUrl: ${e.message}")
                    }
                } else {
                    Log.v(TAG, "⏳ Relay $relayUrl not connected, subscription will be sent on reconnection")
                }
            }

            if (connections.isEmpty()) {
                Log.w(TAG, "No relay connections available for subscription, will retry on reconnection")
            }
        }
    }

    fun unsubscribe(id: String) {

        val subscriptionInfo = activeSubscriptions.remove(id)
        messageHandlers.remove(id)

        if (subscriptionInfo == null) {
            Log.w(TAG, "Attempted to unsubscribe from unknown subscription: $id")
            return
        }

        Log.d(TAG, "Unsubscribing from subscription: $id")

        val request = NostrRequest.Close(id)
        val message = gson.toJson(request, NostrRequest::class.java)

        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        webSocket.send(message)
                        subscriptions[relayUrl] = currentSubs - id
                        Log.v(TAG, "Unsubscribed '$id' from relay: $relayUrl")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unsubscribe from $relayUrl: ${e.message}")
                    }
                }
            }
        }
    }

    fun retryConnection(relayUrl: String) {
        val relay = relaysList.find { it.url == relayUrl } ?: return

        relay.reconnectAttempts = 0
        relay.nextReconnectTime = null

        connections[relayUrl]?.close(1000, "Manual retry")
        connections.remove(relayUrl)

        scope.launch {
            connectToRelay(relayUrl)
        }
    }

    fun resetAllConnections() {
        disconnect()

        relaysList.forEach { relay ->
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
            relay.lastError = null
        }

        connect()
    }

    fun reestablishAllSubscriptions() {
        Log.d(TAG, "Force re-establishing all ${activeSubscriptions.size} active subscriptions")

        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                restoreSubscriptionsForRelay(relayUrl, webSocket)
            }
        }
    }

    fun clearAllSubscriptions() {
        try {

            activeSubscriptions.clear()
            messageHandlers.clear()
            subscriptions.clear()

            geohashToRelays.clear()

            synchronized(messageQueueLock) {
                messageQueue.clear()
            }

            Log.i(TAG, "Cleared all Nostr subscriptions and routing caches")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear subscriptions: ${e.message}")
        }
    }

    fun getRelayStatuses(): List<Relay> {
        return relaysList.toList()
    }

    fun getDeduplicationStats(): DeduplicationStats {
        return eventDeduplicator.getStats()
    }

    fun clearDeduplicationCache() {
        eventDeduplicator.clear()
        Log.i(TAG, "Cleared event deduplication cache")
    }

    fun getActiveSubscriptionCount(): Int {
        return activeSubscriptions.size
    }

    fun getActiveSubscriptions(): Map<String, SubscriptionInfo> {
        return activeSubscriptions.toMap()
    }

    fun validateSubscriptionConsistency(): SubscriptionConsistencyReport {
        val expectedSubs = activeSubscriptions.keys
        val actualSubsByRelay = subscriptions.toMap()
        val inconsistencies = mutableListOf<String>()

        connections.keys.forEach { relayUrl ->
            val actualSubs = actualSubsByRelay[relayUrl] ?: emptySet()
            val expectedForRelay = expectedSubs.filter { subId ->
                val subInfo = activeSubscriptions[subId]
                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
            }.toSet()

            val missing = expectedForRelay - actualSubs
            val extra = actualSubs - expectedForRelay

            if (missing.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl missing subscriptions: $missing")
            }
            if (extra.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl has extra subscriptions: $extra")
            }
        }

        return SubscriptionConsistencyReport(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            totalActiveSubscriptions = activeSubscriptions.size,
            connectedRelayCount = connections.size
        )
    }

    data class SubscriptionConsistencyReport(
        val isConsistent: Boolean,
        val inconsistencies: List<String>,
        val totalActiveSubscriptions: Int,
        val connectedRelayCount: Int
    )

    private fun startSubscriptionValidation() {
        stopSubscriptionValidation()

        subscriptionValidationJob = scope.launch {
            while (isActive) {
                delay(SUBSCRIPTION_VALIDATION_INTERVAL)

                try {
                    val report = validateSubscriptionConsistency()
                    if (!report.isConsistent && report.connectedRelayCount > 0) {
                        Log.w(TAG, "Subscription inconsistencies detected: ${report.inconsistencies}")

                        connections.forEach { (relayUrl, webSocket) ->
                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            val expectedSubs = activeSubscriptions.keys.filter { subId ->
                                val subInfo = activeSubscriptions[subId]
                                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
                            }.toSet()

                            val missingSubs = expectedSubs - currentSubs
                            if (missingSubs.isNotEmpty()) {
                                Log.i(TAG, "Auto-repairing ${missingSubs.size} missing subscriptions for $relayUrl")
                                restoreSubscriptionsForRelay(relayUrl, webSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during subscription validation: ${e.message}")
                }
            }
        }

        Log.d(TAG, "Started periodic subscription validation (${SUBSCRIPTION_VALIDATION_INTERVAL / 1000}s interval)")
    }

    private fun stopSubscriptionValidation() {
        subscriptionValidationJob?.cancel()
        subscriptionValidationJob = null
        Log.v(TAG, "⏹Stopped subscription validation")
    }

    private suspend fun connectToRelay(urlString: String) {

        if (connections.containsKey(urlString)) {
            return
        }

        Log.v(TAG, "Attempting to connect to Nostr relay: $urlString")

        try {
            val request = Request.Builder()
                .url(urlString)
                .build()

            val webSocket = httpClient.newWebSocket(request, RelayWebSocketListener(urlString))
            connections[urlString] = webSocket

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection to $urlString: ${e.message}")
            handleDisconnection(urlString, e)
        }
    }

    private fun sendToRelay(event: NostrEvent, webSocket: WebSocket, relayUrl: String) {
        try {
            val request = NostrRequest.Event(event)
            val message = gson.toJson(request, NostrRequest::class.java)

            Log.v(TAG, "Sending Nostr event (kind: ${event.kind}) to relay: $relayUrl")

            val success = webSocket.send(message)
            if (success) {

                val relay = relaysList.find { it.url == relayUrl }
                relay?.messagesSent = (relay?.messagesSent ?: 0) + 1
                updateRelaysList()
            } else {
                Log.e(TAG, "Failed to send event to $relayUrl: WebSocket send failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send event to $relayUrl: ${e.message}")
        }
    }

    private fun handleMessage(message: String, relayUrl: String) {
        try {
            val jsonElement = JsonParser.parseString(message)
            if (!jsonElement.isJsonArray) {
                Log.w(TAG, "Received non-array message from $relayUrl")
                return
            }

            val response = NostrResponse.fromJsonArray(jsonElement.asJsonArray)

            when (response) {
                is NostrResponse.Event -> {

                    val relay = relaysList.find { it.url == relayUrl }
                    relay?.messagesReceived = (relay?.messagesReceived ?: 0) + 1
                    updateRelaysList()

                    activeSubscriptions[response.subscriptionId]?.let { subInfo ->
                        val matches = try { subInfo.filter.matches(response.event) } catch (e: Exception) { true }
                        if (!matches) {
                            Log.v(TAG, "Dropping event ${response.event.id.take(16)}... not matching filter for sub=${response.subscriptionId}")

                            return
                        }
                    }

                    val wasProcessed = eventDeduplicator.processEvent(response.event) { event ->

                        if (event.kind != NostrKind.GIFT_WRAP) {
                            val originGeo = activeSubscriptions[response.subscriptionId]?.originGeohash
                            if (originGeo != null) {
                                Log.v(TAG, "Processing event (kind=${event.kind}) from relay=$relayUrl geo=$originGeo sub=${response.subscriptionId}")
                            } else {
                                Log.v(TAG, "Processing event (kind=${event.kind}) from relay=$relayUrl sub=${response.subscriptionId}")
                            }
                        }

                        val handler = messageHandlers[response.subscriptionId]
                        if (handler != null) {
                            scope.launch(Dispatchers.Main) {
                                handler(event)
                            }
                        } else {
                            Log.w(TAG, "No handler for subscription ${response.subscriptionId}")
                        }
                    }

                    if (!wasProcessed) {

                    }
                }

                is NostrResponse.EndOfStoredEvents -> {
                    Log.v(TAG, "End of stored events for subscription: ${response.subscriptionId}")
                }

                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    if (response.accepted) {
                        Log.d(TAG, "Event accepted id=${response.eventId.take(16)}... by relay: $relayUrl")
                    } else {
                        val level = if (wasGiftWrap) Log.WARN else Log.ERROR
                        Log.println(level, TAG, "Event ${response.eventId.take(16)}... rejected by relay: ${response.message ?: "no reason"}")
                    }
                }

                is NostrResponse.Notice -> {
                    Log.i(TAG, "Notice from $relayUrl: ${response.message}")
                }

                is NostrResponse.Unknown -> {
                    Log.v(TAG, "Unknown message type from $relayUrl: ${response.raw}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from $relayUrl: ${e.message}")
        }
    }

    private fun handleDisconnection(relayUrl: String, error: Throwable) {
        connections.remove(relayUrl)

        updateRelayStatus(relayUrl, false, error)

        val errorMessage = error.message?.lowercase() ?: ""
        if (errorMessage.contains("hostname could not be found") ||
            errorMessage.contains("dns") ||
            errorMessage.contains("unable to resolve host")) {

            val relay = relaysList.find { it.url == relayUrl }
            if (relay?.lastError == null) {
                Log.w(TAG, "Nostr relay DNS failure for $relayUrl - not retrying")
            }
            return
        }

        val relay = relaysList.find { it.url == relayUrl } ?: return
        relay.reconnectAttempts++

        if (relay.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached for $relayUrl")
            return
        }

        val backoffInterval = min(
            INITIAL_BACKOFF_INTERVAL * BACKOFF_MULTIPLIER.pow(relay.reconnectAttempts - 1.0),
            MAX_BACKOFF_INTERVAL.toDouble()
        ).toLong()

        relay.nextReconnectTime = System.currentTimeMillis() + backoffInterval

        Log.d(TAG, "Scheduling reconnection to $relayUrl in ${backoffInterval / 1000}s (attempt ${relay.reconnectAttempts})")

        scope.launch {
            delay(backoffInterval)
            connectToRelay(relayUrl)
        }
    }

    private fun updateRelayStatus(url: String, isConnected: Boolean, error: Throwable? = null) {
        val relay = relaysList.find { it.url == url } ?: return

        relay.isConnected = isConnected
        relay.lastError = error

        if (isConnected) {
            relay.lastConnectedAt = System.currentTimeMillis()
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
        } else {
            relay.lastDisconnectedAt = System.currentTimeMillis()
        }

        updateRelaysList()
        updateConnectionStatus()
    }

    private fun updateRelaysList() {
        _relays.value = relaysList.toList()
    }

    private fun updateConnectionStatus() {
        val connected = relaysList.any { it.isConnected }
        _isConnected.value = connected
    }

    private fun generateSubscriptionId(): String {
        return "sub-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}"
    }

    private fun restoreSubscriptionsForRelay(relayUrl: String, webSocket: WebSocket) {
        val subscriptionsToRestore = activeSubscriptions.values.filter { subscriptionInfo ->

            subscriptionInfo.targetRelayUrls == null || subscriptionInfo.targetRelayUrls.contains(relayUrl)
        }

        if (subscriptionsToRestore.isEmpty()) {
            Log.v(TAG, "No subscriptions to restore for relay: $relayUrl")
            return
        }

        Log.d(TAG, "Restoring ${subscriptionsToRestore.size} subscriptions for relay: $relayUrl")

        subscriptionsToRestore.forEach { subscriptionInfo ->
            try {
                val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
                val message = gson.toJson(request, NostrRequest::class.java)

                val success = webSocket.send(message)
                if (success) {

                    val currentSubs = subscriptions[relayUrl] ?: emptySet()
                    subscriptions[relayUrl] = currentSubs + subscriptionInfo.id

                    Log.v(TAG, "Restored subscription '${subscriptionInfo.id}' to relay: $relayUrl")
                } else {
                    Log.w(TAG, "Failed to restore subscription '${subscriptionInfo.id}' to $relayUrl: WebSocket send failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore subscription '${subscriptionInfo.id}' to $relayUrl: ${e.message}")
            }
        }
    }

    private inner class RelayWebSocketListener(private val relayUrl: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Connected to Nostr relay: $relayUrl")
            updateRelayStatus(relayUrl, true)

            restoreSubscriptionsForRelay(relayUrl, webSocket)

            synchronized(messageQueueLock) {
                val iterator = messageQueue.iterator()
                while (iterator.hasNext()) {
                    val (event, targetRelays) = iterator.next()
                    if (relayUrl in targetRelays) {
                        sendToRelay(event, webSocket, relayUrl)
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text, relayUrl)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing for $relayUrl: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed for $relayUrl: $code $reason")
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(relayUrl, error)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure for $relayUrl: ${t.message}")
            handleDisconnection(relayUrl, t)
        }
    }
}
