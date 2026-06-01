package com.cybersiren.android.v2v.ui.mock

import android.location.Location
import com.cybersiren.android.v2v.model.AlertType
import com.cybersiren.android.v2v.model.EmergencyAlert
import com.cybersiren.android.v2v.model.ReceivedAlert
import com.cybersiren.android.v2v.model.VehicleType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object V2VMockEngine {

    data class Snapshot(
        val location: Location,
        val speedMps: Float,
        val headingDeg: Float,
        val peers: Int,
        val alerts: List<ReceivedAlert>
    )

    private val directions = listOf("ahead", "behind", "left", "right")

    fun nextSnapshot(seedLocation: Location? = null, nowMs: Long = System.currentTimeMillis()): Snapshot {
        val base = seedLocation ?: Location("mock").apply {
            latitude = -12.2309
            longitude = -38.9260
            speed = 0f
            bearing = 0f
        }

        val heading = Random.nextInt(0, 360).toFloat()
        val speedKmh = Random.nextInt(30, 51)
        val speedMps = (speedKmh / 3.6f)
        val peers = Random.nextInt(0, 6)

        val loc = Location(base).apply {

            val meters = Random.nextInt(5, 21).toDouble()
            val rad = Math.toRadians(heading.toDouble())
            val dLat = (meters * cos(rad)) / 111_320.0
            val dLon = (meters * sin(rad)) / (111_320.0 * cos(Math.toRadians(latitude)))
            latitude = latitude + dLat
            longitude = longitude + dLon
            speed = speedMps
            bearing = heading
            time = nowMs
        }

        val alertCount = Random.nextInt(0, 5)
        val alerts = (0 until alertCount).map { idx ->
            val type = VehicleType.values()[idx % VehicleType.values().size]
            val dist = Random.nextInt(15, 2001).toFloat()
            val relDir = directions.random()
            val senderPeerId = "mock-${idx + 1}"

            val senderLoc = offset(loc, heading, dist.toDouble())

            val alert = EmergencyAlert(
                vehicleType = type,
                alertType = AlertType.APPROACHING,
                latitude = senderLoc.latitude,
                longitude = senderLoc.longitude,
                speed = speedMps,
                heading = heading,
                timestamp = nowMs,
                senderPeerId = senderPeerId
            )

            ReceivedAlert(
                alert = alert,
                distanceMeters = dist,
                receivedAt = nowMs,
                relativeDirection = relDir
            )
        }.sortedBy { it.distanceMeters }

        return Snapshot(
            location = loc,
            speedMps = speedMps,
            headingDeg = heading,
            peers = peers,
            alerts = alerts
        )
    }

    private fun offset(origin: Location, headingDeg: Float, meters: Double): Location {
        val rad = Math.toRadians(headingDeg.toDouble())
        val dLat = (meters * cos(rad)) / 111_320.0
        val dLon = (meters * sin(rad)) / (111_320.0 * cos(Math.toRadians(origin.latitude)))
        return Location(origin).apply {
            latitude = origin.latitude + dLat
            longitude = origin.longitude + dLon
        }
    }
}
