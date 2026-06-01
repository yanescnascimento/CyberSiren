package com.cybersiren.android.nostr

import android.app.Application
import android.util.Log
import com.cybersiren.android.ui.ChatState
import com.cybersiren.android.ui.GeoPerson
import java.util.Date

class GeohashRepository(
    private val application: Application,
    private val state: ChatState,
    private val dataManager: com.cybersiren.android.ui.DataManager
) {
    companion object { private const val TAG = "GeohashRepository" }

    private val geohashParticipants: MutableMap<String, MutableMap<String, Date>> = mutableMapOf()

    private val geoNicknames: MutableMap<String, String> = mutableMapOf()

    private val conversationGeohash: MutableMap<String, String> = mutableMapOf()

    fun setConversationGeohash(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) {
            conversationGeohash[convKey] = geohash
        }
    }

    fun getConversationGeohash(convKey: String): String? = conversationGeohash[convKey]

    fun findPubkeyByNickname(targetNickname: String): String? {
        return geoNicknames.entries.firstOrNull { (_, nickname) ->
            val base = nickname.split("#").firstOrNull() ?: nickname
            base == targetNickname
        }?.key
    }

    private val nostrKeyMapping: MutableMap<String, String> = mutableMapOf()

    private var currentGeohash: String? = null

    fun setCurrentGeohash(geo: String?) { currentGeohash = geo }
    fun getCurrentGeohash(): String? = currentGeohash

    fun clearAll() {
        geohashParticipants.clear()
        geoNicknames.clear()
        nostrKeyMapping.clear()
        state.setGeohashPeople(emptyList())
        state.setTeleportedGeo(emptySet())
        state.setGeohashParticipantCounts(emptyMap())
        currentGeohash = null
    }

    fun cacheNickname(pubkeyHex: String, nickname: String) {
        val lower = pubkeyHex.lowercase()
        val previous = geoNicknames[lower]
        geoNicknames[lower] = nickname
        if (previous != nickname && currentGeohash != null) {
            refreshGeohashPeople()
        }
    }

    fun getCachedNickname(pubkeyHex: String): String? = geoNicknames[pubkeyHex.lowercase()]

    fun markTeleported(pubkeyHex: String) {
        val set = state.getTeleportedGeoValue().toMutableSet()
        val key = pubkeyHex.lowercase()
        if (!set.contains(key)) {
            set.add(key)

            state.postTeleportedGeo(set)
        }
    }

    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return state.getTeleportedGeoValue().contains(pubkeyHex.lowercase())
    }

    fun updateParticipant(geohash: String, participantId: String, lastSeen: Date) {
        val participants = geohashParticipants.getOrPut(geohash) { mutableMapOf() }
        participants[participantId] = lastSeen
        if (currentGeohash == geohash) refreshGeohashPeople()
        updateReactiveParticipantCounts()
    }

    fun geohashParticipantCount(geohash: String): Int {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val participants = geohashParticipants[geohash] ?: return 0

        val it = participants.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.before(cutoff)) it.remove()
        }

        return participants.keys.count { !dataManager.isGeohashUserBlocked(it) }
    }

    fun refreshGeohashPeople() {
        val geohash = currentGeohash
        if (geohash == null) {

            state.setGeohashPeople(emptyList())
            return
        }
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val participants = geohashParticipants[geohash] ?: mutableMapOf()

        val it = participants.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.before(cutoff)) it.remove()
        }
        geohashParticipants[geohash] = participants

        val people = participants.filterKeys { !dataManager.isGeohashUserBlocked(it) }
            .map { (pubkeyHex, lastSeen) ->

            val base = try {
                val myHex = currentGeohash?.let { NostrIdentityBridge.deriveIdentity(it, application).publicKeyHex }
                if (myHex != null && myHex.equals(pubkeyHex, true)) {
                    state.getNicknameValue() ?: "anon"
                } else {
                    getCachedNickname(pubkeyHex) ?: "anon"
                }
            } catch (_: Exception) { getCachedNickname(pubkeyHex) ?: "anon" }
            GeoPerson(
                id = pubkeyHex.lowercase(),
                displayName = base,
                lastSeen = lastSeen
            )
        }.sortedByDescending { it.lastSeen }

        state.setGeohashPeople(people)
    }

    fun updateReactiveParticipantCounts() {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val counts = mutableMapOf<String, Int>()
        for ((gh, participants) in geohashParticipants) {
            val active = participants.filterKeys { !dataManager.isGeohashUserBlocked(it) }
                .values.count { !it.before(cutoff) }
            counts[gh] = active
        }

        state.setGeohashParticipantCounts(counts)
    }

    fun putNostrKeyMapping(tempKeyOrPeer: String, pubkeyHex: String) {
        nostrKeyMapping[tempKeyOrPeer] = pubkeyHex
    }

    fun getNostrKeyMapping(): Map<String, String> = nostrKeyMapping.toMap()

    fun displayNameForNostrPubkey(pubkeyHex: String): String {
        val suffix = pubkeyHex.takeLast(4)
        val lower = pubkeyHex.lowercase()

        val current = currentGeohash
        if (current != null) {
            try {
                val my = NostrIdentityBridge.deriveIdentity(current, application)
                if (my.publicKeyHex.equals(lower, true)) {
                    return "${state.getNicknameValue()}#$suffix"
                }
            } catch (_: Exception) {}
        }
        val nick = geoNicknames[lower] ?: "anon"
        return "$nick#$suffix"
    }

    fun displayNameForNostrPubkeyUI(pubkeyHex: String): String {
        val lower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val current = currentGeohash
        val base: String = try {
            if (current != null) {
                val my = NostrIdentityBridge.deriveIdentity(current, application)
                if (my.publicKeyHex.equals(lower, true)) {
                    state.getNicknameValue() ?: "anon"
                } else geoNicknames[lower] ?: "anon"
            } else geoNicknames[lower] ?: "anon"
        } catch (_: Exception) { geoNicknames[lower] ?: "anon" }
        if (current == null) return base
        return try {
            val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
            val participants = geohashParticipants[current] ?: emptyMap()
            var count = 0
            for ((k, t) in participants) {
                if (dataManager.isGeohashUserBlocked(k)) continue
                if (t.before(cutoff)) continue
                val name = if (k.equals(lower, true)) base else (geoNicknames[k.lowercase()] ?: "anon")
                if (name.equals(base, true)) { count++; if (count > 1) break }
            }
            if (!participants.containsKey(lower)) count += 1
            if (count > 1) "$base#$suffix" else base
        } catch (_: Exception) { base }
    }

    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String {
        val lower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val base = geoNicknames[lower] ?: "anon"
        return try {
            val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
            val participants = geohashParticipants[sourceGeohash] ?: emptyMap()
            var count = 0
            for ((k, t) in participants) {
                if (dataManager.isGeohashUserBlocked(k)) continue
                if (t.before(cutoff)) continue
                val name = if (k.equals(lower, true)) base else (geoNicknames[k.lowercase()] ?: "anon")
                if (name.equals(base, true)) { count++; if (count > 1) break }
            }
            if (!participants.containsKey(lower)) count += 1
            if (count > 1) "$base#$suffix" else base
        } catch (_: Exception) { base }
    }
}
