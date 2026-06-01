package com.cybersiren.android.nostr

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

object GeohashConversationRegistry {
    private val map = ConcurrentHashMap<String, String>()
    private const val PREFS_NAME = "geohash_conversation_registry"
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        prefs?.let { p ->
            val allEntries = p.all
            for ((key, value) in allEntries) {
                if (key is String && value is String) {
                    map[key] = value
                }
            }
        }
    }

    fun set(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) {
            map[convKey] = geohash
            prefs?.edit()?.putString(convKey, geohash)?.apply()
        }
    }

    fun get(convKey: String): String? = map[convKey]

    fun snapshot(): Map<String, String> = map.toMap()

    fun clear() {
        map.clear()
        prefs?.edit()?.clear()?.apply()
    }
}
