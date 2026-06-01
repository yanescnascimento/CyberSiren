package com.cybersiren.wear.firebase

import android.content.Context
import android.util.Base64
import android.util.Log
import com.cybersiren.wear.data.WearAlert
import com.cybersiren.wear.data.WearAlertRepository
import com.cybersiren.wear.data.WearUrgency
import com.cybersiren.wear.data.WearVehicleType
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

object FirebaseEmergencyReceiver {

    private const val TAG = "WearFirebaseReceiver"
    private const val CHANNEL = "emergency"
    private const val MAX_DISPLAYED = 8
    private const val FIREBASE_APP_NAME = "wear-v2v"
    private const val FRESHNESS_WINDOW_MS = 60_000L

    private const val API_KEY = "AIzaSyBGNh9BfawX6hNnCeAggNnOSt3zOosyPPI"
    private const val APP_ID = "1:1072053933926:android:9a53b65821635705719aa7"
    private const val PROJECT_ID = "humedu"
    private const val DATABASE_URL = "https://humedu-default-rtdb.firebaseio.com"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val recent = LinkedHashMap<String, WearAlert>()
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        scope.launch { runCatching { startBlocking(context.applicationContext) } }
    }

    private suspend fun startBlocking(appContext: Context) {
        val app = FirebaseApp.getApps(appContext).firstOrNull { it.name == FIREBASE_APP_NAME }
            ?: run {
                val opts = FirebaseOptions.Builder()
                    .setApiKey(API_KEY)
                    .setApplicationId(APP_ID)
                    .setProjectId(PROJECT_ID)
                    .setDatabaseUrl(DATABASE_URL)
                    .build()
                FirebaseApp.initializeApp(appContext, opts, FIREBASE_APP_NAME)
            }

        val auth = FirebaseAuth.getInstance(app)
        if (auth.currentUser == null) {
            Log.i(TAG, "Anonymous sign-in…")
            auth.signInAnonymously().await()
            Log.i(TAG, "Signed in as ${auth.currentUser?.uid}")
        } else {
            Log.i(TAG, "Already signed in as ${auth.currentUser?.uid}")
        }

        val ref = FirebaseDatabase.getInstance(app).getReference("relay/$CHANNEL")
        ref.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, prev: String?) = onMessage(snap)
            override fun onChildChanged(snap: DataSnapshot, prev: String?) = onMessage(snap)
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listener cancelled: ${error.message}")
            }
        })
        Log.i(TAG, "Listening to relay/$CHANNEL")
    }

    private fun onMessage(snap: DataSnapshot) {
        runCatching {
            val messageId = snap.key ?: return

            val ts = snap.child("ts").getValue(Long::class.java) ?: System.currentTimeMillis()
            if (System.currentTimeMillis() - ts > FRESHNESS_WINDOW_MS) {
                Log.d(TAG, "Skipping stale alert: $messageId")
                return
            }
            val base64 = snap.child("data").getValue(String::class.java) ?: return
            val payload = Base64.decode(base64, Base64.NO_WRAP)
            val json = JSONObject(String(payload, Charsets.UTF_8))

            val vehicle = mapVehicle(json.optString("vehicle"))
            val speedKmh = json.optInt("speed", 0)
            val heading = json.optInt("heading", 0)

            val urgency = urgencyFromSpeed(speedKmh)
            val alert = WearAlert(
                id = messageId,
                vehicleType = vehicle,
                distanceMeters = 0f,
                direction = headingToCardinal(heading),
                urgency = urgency
            )

            synchronized(recent) {
                recent.remove(messageId)
                recent[messageId] = alert
                while (recent.size > MAX_DISPLAYED) {
                    val oldest = recent.keys.firstOrNull() ?: break
                    recent.remove(oldest)
                }
                WearAlertRepository.update(recent.values.toList().reversed())
            }
            Log.i(TAG, "Alert ${vehicle.displayName} ${speedKmh}km/h heading=${heading}°")
        }.onFailure { Log.w(TAG, "Failed to parse alert: ${it.message}") }
    }

    private fun mapVehicle(raw: String): WearVehicleType = when (raw.lowercase()) {
        "ambulance" -> WearVehicleType.AMBULANCE
        "police" -> WearVehicleType.POLICE
        "fire_truck", "firetruck", "fire" -> WearVehicleType.FIRE_TRUCK
        else -> WearVehicleType.EMERGENCY
    }

    private fun urgencyFromSpeed(speedKmh: Int): WearUrgency = when {
        speedKmh >= 60 -> WearUrgency.CRITICAL
        speedKmh >= 30 -> WearUrgency.HIGH
        speedKmh >= 5 -> WearUrgency.MEDIUM
        else -> WearUrgency.LOW
    }

    private fun headingToCardinal(deg: Int): String {
        val n = ((deg % 360) + 360) % 360
        return when {
            n < 22 || n >= 338 -> "N"
            n < 67 -> "NE"
            n < 112 -> "E"
            n < 157 -> "SE"
            n < 202 -> "S"
            n < 247 -> "SW"
            n < 292 -> "W"
            else -> "NW"
        }
    }
}
