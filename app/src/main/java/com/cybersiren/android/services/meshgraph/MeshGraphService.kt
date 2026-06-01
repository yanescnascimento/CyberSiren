package com.cybersiren.android.services.meshgraph

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class MeshGraphService private constructor() {
    data class GraphNode(val peerID: String, val nickname: String?)
    data class GraphEdge(val a: String, val b: String, val isConfirmed: Boolean, val confirmedBy: String? = null)
    data class GraphSnapshot(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

    private val nicknames = ConcurrentHashMap<String, String?>()

    private val announcements = ConcurrentHashMap<String, Set<String>>()

    private val lastUpdate = ConcurrentHashMap<String, ULong>()

    private val _graphState = MutableStateFlow(GraphSnapshot(emptyList(), emptyList()))
    val graphState: StateFlow<GraphSnapshot> = _graphState.asStateFlow()

    fun updateFromAnnouncement(originPeerID: String, originNickname: String?, neighborsOrNull: List<String>?, timestamp: ULong) {
        synchronized(this) {

            if (originNickname != null) nicknames[originPeerID] = originNickname

            val prevTs = lastUpdate[originPeerID]
            if (prevTs != null && prevTs >= timestamp) {

                return
            }
            lastUpdate[originPeerID] = timestamp

            val neighbors = neighborsOrNull ?: emptyList()

            val newSet = neighbors.distinct().take(10).filter { it != originPeerID }.toSet()
            announcements[originPeerID] = newSet

            publishSnapshot()
        }
    }

    fun updateNickname(peerID: String, nickname: String?) {
        if (nickname == null) return
        nicknames[peerID] = nickname
        publishSnapshot()
    }

    fun removePeer(peerID: String) {
        synchronized(this) {
            nicknames.remove(peerID)
            announcements.remove(peerID)
            lastUpdate.remove(peerID)
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {

        val allNodes = mutableSetOf<String>()
        allNodes.addAll(nicknames.keys)
        announcements.forEach { (origin, neighbors) ->
            allNodes.add(origin)
            allNodes.addAll(neighbors)
        }

        val nodeList = allNodes.map { GraphNode(it, nicknames[it]) }.sortedBy { it.peerID }

        val edges = mutableListOf<GraphEdge>()
        val processedPairs = mutableSetOf<Pair<String, String>>()

        announcements.forEach { (source, targets) ->
            targets.forEach { target ->
                val pair = if (source <= target) source to target else target to source
                if (processedPairs.add(pair)) {

                    val (a, b) = pair
                    val aAnnouncesB = announcements[a]?.contains(b) == true
                    val bAnnouncesA = announcements[b]?.contains(a) == true

                    if (aAnnouncesB && bAnnouncesA) {
                        edges.add(GraphEdge(a, b, isConfirmed = true))
                    } else if (aAnnouncesB) {
                        edges.add(GraphEdge(a, b, isConfirmed = false, confirmedBy = a))
                    } else if (bAnnouncesA) {
                        edges.add(GraphEdge(a, b, isConfirmed = false, confirmedBy = b))
                    }
                }
            }
        }

        val sortedEdges = edges.sortedWith(compareBy({ it.a }, { it.b }))
        _graphState.value = GraphSnapshot(nodeList, sortedEdges)
    }

    companion object {
        @Volatile private var INSTANCE: MeshGraphService? = null
        fun getInstance(): MeshGraphService = INSTANCE ?: synchronized(this) {
            INSTANCE ?: MeshGraphService().also { INSTANCE = it }
        }

        @org.jetbrains.annotations.TestOnly
        fun resetForTesting() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}
