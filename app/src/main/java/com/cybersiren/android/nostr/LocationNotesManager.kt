package com.cybersiren.android.nostr

import android.util.Log
import androidx.annotation.MainThread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@MainThread
class LocationNotesManager private constructor() {

    companion object {
        private const val TAG = "LocationNotesManager"
        private const val MAX_NOTES_IN_MEMORY = 500

        @Volatile
        private var INSTANCE: LocationNotesManager? = null

        fun getInstance(): LocationNotesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocationNotesManager().also { INSTANCE = it }
            }
        }
    }

    data class Note(
        val id: String,
        val pubkey: String,
        val content: String,
        val createdAt: Int,
        val nickname: String?
    ) {

        val displayName: String
            get() {
                val suffix = pubkey.takeLast(4)
                val nick = nickname?.trim()
                return if (!nick.isNullOrEmpty()) {
                    "$nick#$suffix"
                } else {
                    "anon#$suffix"
                }
            }
    }

    enum class State {
        IDLE,
        LOADING,
        READY,
        NO_RELAYS
    }

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _geohash = MutableStateFlow<String?>(null)
    val geohash: StateFlow<String?> = _geohash.asStateFlow()

    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var subscriptionIDs: MutableMap<String, String> = mutableMapOf()
    private val noteIDs = mutableSetOf<String>()
    private var subscribedGeohashes: Set<String> = emptySet()

    private var relayLookup: (() -> NostrRelayManager)? = null
    private var subscribeFunc: ((NostrFilter, String, (NostrEvent) -> Unit) -> String)? = null
    private var unsubscribeFunc: ((String) -> Unit)? = null
    private var sendEventFunc: ((NostrEvent, List<String>?) -> Unit)? = null
    private var deriveIdentityFunc: ((String) -> NostrIdentity)? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun initialize(
        relayManager: () -> NostrRelayManager,
        subscribe: (NostrFilter, String, (NostrEvent) -> Unit) -> String,
        unsubscribe: (String) -> Unit,
        sendEvent: (NostrEvent, List<String>?) -> Unit,
        deriveIdentity: (String) -> NostrIdentity
    ) {
        this.relayLookup = relayManager
        this.subscribeFunc = subscribe
        this.unsubscribeFunc = unsubscribe
        this.sendEventFunc = sendEvent
        this.deriveIdentityFunc = deriveIdentity
    }

    fun setGeohash(newGeohash: String) {
        val normalized = newGeohash.lowercase()

        if (_geohash.value == normalized) {
            Log.d(TAG, "Geohash unchanged, skipping: $normalized")
            return
        }

        if (!isValidBuildingGeohash(normalized)) {
            Log.w(TAG, "LocationNotesManager: rejecting invalid geohash '$normalized' (expected 8 valid base32 chars)")
            return
        }

        Log.d(TAG, "Setting geohash: $normalized")

        cancel()

        _state.value = State.LOADING
        _initialLoadComplete.value = false
        _errorMessage.value = null

        _notes.value = emptyList()
        noteIDs.clear()
        _geohash.value = normalized

        val neighbors = try {
            com.cybersiren.android.geohash.Geohash.neighborsSamePrecision(normalized)
        } catch (_: Exception) { emptySet() }
        subscribedGeohashes = (neighbors + normalized).toSet()

        subscribeAll()
    }

    private fun isValidBuildingGeohash(geohash: String): Boolean {
        if (geohash.length != 8) return false
        val base32Chars = "0123456789bcdefghjkmnpqrstuvwxyz"
        return geohash.all { it in base32Chars }
    }

    fun refresh() {
        val currentGeohash = _geohash.value
        if (currentGeohash == null) {
            Log.w(TAG, "Cannot refresh - no geohash set")
            return
        }

        Log.d(TAG, "Refreshing notes for geohash: $currentGeohash")

        cancel()
        _notes.value = emptyList()
        noteIDs.clear()
        _initialLoadComplete.value = false

        val neighbors = try {
            com.cybersiren.android.geohash.Geohash.neighborsSamePrecision(currentGeohash)
        } catch (_: Exception) { emptySet() }
        subscribedGeohashes = (neighbors + currentGeohash).toSet()
        subscribeAll()
    }

    fun send(content: String, nickname: String?) {
        val currentGeohash = _geohash.value
        if (currentGeohash == null) {
            Log.w(TAG, "Cannot send note - no geohash set")
            _errorMessage.value = "No location set"
            return
        }

        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return
        }

        val relays = try {
            com.cybersiren.android.nostr.RelayDirectory.closestRelaysForGeohash(currentGeohash, 5)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lookup relays for geohash $currentGeohash: ${e.message}")
            emptyList()
        }

        if (relays.isEmpty()) {
            Log.w(TAG, "Send blocked - no geo relays for geohash: $currentGeohash")
            _state.value = State.NO_RELAYS
            _errorMessage.value = "No relays available"
            return
        }

        val deriveIdentity = deriveIdentityFunc
        if (deriveIdentity == null) {
            Log.e(TAG, "Cannot send note - deriveIdentity not initialized")
            _errorMessage.value = "Not initialized"
            return
        }

        Log.d(TAG, "Sending note to geohash: $currentGeohash via ${relays.size} geo relays")

        scope.launch {
            try {
                val identity = withContext(Dispatchers.IO) {
                    deriveIdentity(currentGeohash)
                }

                val event = withContext(Dispatchers.IO) {
                    NostrProtocol.createGeohashTextNote(
                        content = trimmed,
                        geohash = currentGeohash,
                        senderIdentity = identity,
                        nickname = nickname
                    )
                }

                val localNote = Note(
                    id = event.id,
                    pubkey = event.pubkey,
                    content = trimmed,
                    createdAt = event.createdAt,
                    nickname = nickname
                )

                if (!noteIDs.contains(event.id)) {
                    noteIDs.add(event.id)
                    val currentNotes = _notes.value ?: emptyList()
                    _notes.value = (currentNotes + localNote).sortedByDescending { it.createdAt }

                    if (noteIDs.size > MAX_NOTES_IN_MEMORY) {
                        trimOldestNotes()
                    }
                }

                withContext(Dispatchers.IO) {
                    sendEventFunc?.invoke(event, relays)
                }

                Log.d(TAG, "Note sent successfully to ${relays.size} geo relays: ${event.id.take(16)}...")

                _errorMessage.value = null
                _state.value = State.READY

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send note: ${e.message}")
                _errorMessage.value = "Failed to send: ${e.message}"
            }
        }
    }

    private fun subscribeAll() {
        val currentGeohash = _geohash.value
        if (currentGeohash == null) {
            Log.w(TAG, "Cannot subscribe - no geohash set")
            _state.value = State.IDLE
            return
        }

        val subscribe = subscribeFunc
        if (subscribe == null) {
            Log.e(TAG, "Cannot subscribe - subscribe function not initialized; will retry shortly")
            _state.value = State.LOADING

            scope.launch {
                var attempts = 0
                while (attempts < 10 && subscribeFunc == null) {
                    delay(300)
                    attempts++
                }
                val subNow = subscribeFunc
                if (subNow != null) {

                    subscribeAll()
                } else {

                    if (!_initialLoadComplete.value!!) {
                        _initialLoadComplete.value = true
                        _state.value = State.READY
                    }
                }
            }
            return
        }

        _state.value = State.LOADING

        subscribedGeohashes.forEach { gh ->
            val filter = NostrFilter.geohashNotes(
                geohash = gh,
                since = null,
                limit = 200
            )
            val subId = "location-notes-$gh"
            Log.d(TAG, "Subscribing to location notes: $subId")
            try {
                val id = subscribe(filter, subId) { event -> handleEvent(event) }
                subscriptionIDs[gh] = id
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe for $gh: ${e.message}")
            }
        }

        scope.launch {
            delay(2000)
            if (!_initialLoadComplete.value!!) {
                _initialLoadComplete.value = true
                _state.value = State.READY
                Log.d(TAG, "Initial load complete for geohash: $currentGeohash (${noteIDs.size} notes)")
            }
        }
    }

    private fun handleEvent(event: NostrEvent) {

        if (event.kind != NostrKind.TEXT_NOTE) {
            Log.v(TAG, "Ignoring non-text-note event: kind=${event.kind}")
            return
        }

        val geohashTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }
        if (geohashTag == null) {
            Log.v(TAG, "Ignoring event without geohash tag: ${event.id.take(16)}...")
            return
        }

        val eventGeohash = geohashTag[1]
        if (!subscribedGeohashes.contains(eventGeohash)) {
            Log.v(TAG, "Ignoring event for non-subscribed geohash: $eventGeohash")
            return
        }

        if (noteIDs.contains(event.id)) {
            return
        }

        val nicknameTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "n" }
        val nickname = nicknameTag?.get(1)

        val note = Note(
            id = event.id,
            pubkey = event.pubkey,
            content = event.content,
            createdAt = event.createdAt,
            nickname = nickname
        )

        noteIDs.add(event.id)
        val currentNotes = _notes.value ?: emptyList()
        _notes.value = (currentNotes + note).sortedByDescending { it.createdAt }

        Log.d(TAG, "Added note: ${note.displayName} - ${note.content.take(50)}")

        if (noteIDs.size > MAX_NOTES_IN_MEMORY) {
            trimOldestNotes()
        }

        if (!_initialLoadComplete.value!!) {
            _initialLoadComplete.value = true
        }
        _state.value = State.READY
    }

    private fun trimOldestNotes() {
        val currentNotes = _notes.value ?: return
        if (currentNotes.size <= MAX_NOTES_IN_MEMORY) return

        val trimmed = currentNotes.sortedByDescending { it.createdAt }.take(MAX_NOTES_IN_MEMORY)
        _notes.value = trimmed

        noteIDs.clear()
        noteIDs.addAll(trimmed.map { it.id })

        Log.d(TAG, "Trimmed notes to $MAX_NOTES_IN_MEMORY (removed ${currentNotes.size - trimmed.size})")
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun cancel() {
        if (subscriptionIDs.isNotEmpty()) {
            subscriptionIDs.values.forEach { subId ->
                try {
                    Log.d(TAG, "Canceling subscription: $subId")
                    unsubscribeFunc?.invoke(subId)
                } catch (_: Exception) { }
            }
            subscriptionIDs.clear()
        }
        subscribedGeohashes = emptySet()
        _state.value = State.IDLE
    }

    fun cleanup() {
        cancel()
        scope.cancel()
        _notes.value = emptyList()
        noteIDs.clear()
        _geohash.value = null
        _initialLoadComplete.value = false
        _errorMessage.value = null
    }
}
