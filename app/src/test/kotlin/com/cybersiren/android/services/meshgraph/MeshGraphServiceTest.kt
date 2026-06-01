package com.cybersiren.android.services.meshgraph

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

class MeshGraphServiceTest {

    private lateinit var service: MeshGraphService

    @Before
    fun setUp() {

        MeshGraphService.resetForTesting()
        service = MeshGraphService.getInstance()
    }

    @Test
    fun testUpdateFromAnnouncement_AddsNeighbors() {
        val origin = "PeerA"
        val neighbors = listOf("PeerB", "PeerC")
        val timestamp = 100UL

        service.updateFromAnnouncement(origin, "Alice", neighbors, timestamp)

        val snapshot = service.graphState.value

        assertTrue(snapshot.nodes.any { it.peerID == "PeerA" })
        assertTrue(snapshot.nodes.any { it.peerID == "PeerB" })
        assertTrue(snapshot.nodes.any { it.peerID == "PeerC" })

        val edgeAB = snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") }
        assertNotNull(edgeAB)
        assertFalse(edgeAB!!.isConfirmed)
        assertEquals("PeerA", edgeAB.confirmedBy)

        val edgeAC = snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") }
        assertNotNull(edgeAC)
        assertFalse(edgeAC!!.isConfirmed)
        assertEquals("PeerA", edgeAC.confirmedBy)
    }

    @Test
    fun testUpdateFromAnnouncement_NewerTimestampReplacesNeighbors() {
        val origin = "PeerA"

        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerC"), 100UL)

        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerD"), 200UL)

        val snapshot = service.graphState.value

        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })

        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerD") || (it.a == "PeerD" && it.b == "PeerA") })

        assertNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") })
    }

    @Test
    fun testUpdateFromAnnouncement_OlderTimestampIsIgnored() {
        val origin = "PeerA"

        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerC"), 200UL)

        service.updateFromAnnouncement(origin, "Alice", listOf("PeerD"), 100UL)

        val snapshot = service.graphState.value

        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") })
        assertNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerD") || (it.a == "PeerD" && it.b == "PeerA") })
    }

    @Test
    fun testUpdateFromAnnouncement_NullNeighborsClearsList_TheFix() {
        val origin = "PeerA"

        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerC"), 100UL)

        service.updateFromAnnouncement(origin, "Alice", null, 200UL)

        val snapshot = service.graphState.value

        val edgesFromA = snapshot.edges.filter { it.a == "PeerA" || it.b == "PeerA" }
        assertTrue("Edges from PeerA should be empty after null update", edgesFromA.isEmpty())

    }

    @Test
    fun testUpdateFromAnnouncement_NullNeighborsWithOlderTimestampIsIgnored() {
        val origin = "PeerA"

        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerC"), 200UL)

        service.updateFromAnnouncement(origin, "Alice", null, 100UL)

        val snapshot = service.graphState.value

        assertFalse(snapshot.edges.isEmpty())
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
    }
}
