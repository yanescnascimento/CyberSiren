package com.cybersiren.android.online

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ChildEventListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirebaseTransport private constructor(
    private val context: Context
) : MessageTransport {

    companion object {
        private const val TAG = "FirebaseTransport"
        private const val DEFAULT_TTL_SECONDS = 300

        private const val FRESHNESS_WINDOW_MS = 60_000L

        @Volatile
        private var INSTANCE: FirebaseTransport? = null

        fun getInstance(context: Context): FirebaseTransport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseTransport(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        fun configure(databaseUrl: String) {
            Log.i(TAG, "Firebase configured with URL: $databaseUrl")
        }
    }

    override val channel = TransportChannel.FIREBASE_CLOUD

    private val transportScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("FirebaseTransport")
    )

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _incomingPackets = MutableSharedFlow<IncomingPacket>()
    private val subscribedGeohashes = mutableSetOf<String>()
    private val activeListeners = mutableMapOf<String, ChildEventListener>()

    private var isAuthenticated = false
    private var isRunning = false

    override val isAvailable: Boolean
        get() = isAuthenticated

    override suspend fun start() {
        if (isRunning && isAuthenticated) {
            Log.w(TAG, "Transport already running and authenticated")
            return
        }

        isRunning = true
        Log.i(TAG, "Starting FirebaseTransport...")

        try {
            if (auth.currentUser == null) {
                Log.i(TAG, "Signing in anonymously...")
                auth.signInAnonymously().await()
                Log.i(TAG, "Anonymous auth successful! UID: ${auth.currentUser?.uid}")
            } else {
                Log.i(TAG, "Already authenticated. UID: ${auth.currentUser?.uid}")
            }
            isAuthenticated = true
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous auth FAILED: ${e.message}", e)
            isAuthenticated = false
            return
        }

        subscribedGeohashes.forEach { geohash ->
            startListeningToChannel(geohash)
        }
    }

    override suspend fun stop() {
        isRunning = false
        activeListeners.forEach { (_, listener) ->

        }
        activeListeners.clear()
        Log.i(TAG, "FirebaseTransport stopped")
    }

    override suspend fun send(packet: ByteArray, targetGeohash: String?) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send - not authenticated!")
            return
        }

        val geohash = targetGeohash ?: "emergency"
        val messageId = UUID.randomUUID().toString()
        val base64Data = Base64.encodeToString(packet, Base64.NO_WRAP)

        Log.i(TAG, "Sending message to channel '$geohash', messageId: ${messageId.take(8)}...")

        try {
            val messageRef = database.getReference("relay/$geohash/$messageId")

            val messageData = mapOf(
                "data" to base64Data,
                "ts" to System.currentTimeMillis(),
                "ttl" to DEFAULT_TTL_SECONDS,
                "sender" to (auth.currentUser?.uid ?: "unknown")
            )

            messageRef.setValue(messageData).await()
            Log.i(TAG, "Message sent successfully to Firebase! Channel: $geohash")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}")
            throw e
        }
    }

    suspend fun sendEmergencyAlert(alert: EmergencyAlert) {
        Log.i(TAG, "Sending emergency alert: ${alert.vehicleType} at ${alert.latitude}, ${alert.longitude}")
        send(alert.toBytes(), "emergency")
    }

    suspend fun sendTestMessage() {
        val testData = """
            {
                "message_id": "${UUID.randomUUID()}",
                "type": "test",
                "content": "Firebase V2N Transport Test",
                "timestamp": "${System.currentTimeMillis()}"
            }
        """.trimIndent()

        Log.i(TAG, "Sending test message...")
        send(testData.toByteArray(Charsets.UTF_8), "test")
    }

    override fun observeIncoming(): Flow<IncomingPacket> = _incomingPackets

    fun subscribeToGeohash(geohash: String) {
        subscribedGeohashes.add(geohash)
        Log.i(TAG, "Subscribed to geohash: $geohash")

        if (isRunning && isAuthenticated) {
            startListeningToChannel(geohash)
        }
    }

    fun unsubscribeFromGeohash(geohash: String) {
        subscribedGeohashes.remove(geohash)
        activeListeners[geohash]?.let { listener ->
            database.getReference("relay/$geohash").removeEventListener(listener)
        }
        activeListeners.remove(geohash)
        Log.i(TAG, "Unsubscribed from geohash: $geohash")
    }

    private fun startListeningToChannel(geohash: String) {
        if (activeListeners.containsKey(geohash)) {
            Log.d(TAG, "Already listening to channel: $geohash")
            return
        }

        val channelRef = database.getReference("relay/$geohash")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleIncomingMessage(snapshot, geohash)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {

            }

            override fun onChildRemoved(snapshot: DataSnapshot) {

            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {

            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listener cancelled for $geohash: ${error.message}")
            }
        }

        channelRef.addChildEventListener(listener)
        activeListeners[geohash] = listener
        Log.i(TAG, "Started listening to channel: $geohash")
    }

    private fun handleIncomingMessage(snapshot: DataSnapshot, channel: String) {
        try {
            val messageId = snapshot.key ?: return
            val data = snapshot.child("data").getValue(String::class.java) ?: return
            val timestamp = snapshot.child("ts").getValue(Long::class.java) ?: System.currentTimeMillis()
            val sender = snapshot.child("sender").getValue(String::class.java) ?: "unknown"

            if (sender == auth.currentUser?.uid) {
                Log.d(TAG, "Skipping own message: $messageId")
                return
            }

            val ageMs = System.currentTimeMillis() - timestamp
            if (ageMs > FRESHNESS_WINDOW_MS) {
                Log.d(TAG, "Skipping stale message (${ageMs / 1000}s old): ${messageId.take(8)}")
                return
            }

            Log.i(TAG, "Received message from channel '$channel': ${messageId.take(8)}...")

            val packetData = Base64.decode(data, Base64.NO_WRAP)

            val incomingPacket = IncomingPacket(
                data = packetData,
                channel = TransportChannel.FIREBASE_CLOUD,
                receivedAtMs = timestamp,
                metadata = mapOf(
                    "source" to channel,
                    "messageId" to messageId,
                    "sender" to sender
                )
            )

            transportScope.launch {
                _incomingPackets.emit(incomingPacket)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming message: ${e.message}")
        }
    }

    fun cleanup() {
        transportScope.cancel()
        activeListeners.clear()
        INSTANCE = null
    }
}
