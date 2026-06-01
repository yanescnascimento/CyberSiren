package com.cybersiren.android.online

import org.json.JSONObject
import java.util.UUID

data class EmergencyAlert(

    val messageId: String = UUID.randomUUID().toString(),

    val type: AlertType = AlertType.EMERGENCY,

    val vehicleType: VehicleType,

    val latitude: Double,

    val longitude: Double,

    val speedKmh: Int,

    val heading: Int = 0,

    val timestamp: String = java.time.Instant.now().toString(),

    val signature: String? = null,

    val geohash: String? = null,

    val ttl: Int = 7
) {
    enum class AlertType {
        EMERGENCY,
        APPROACHING,
        PASSING,
        CLEARING
    }

    enum class VehicleType {
        AMBULANCE,
        POLICE,
        FIRE_TRUCK,
        RESCUE,
        OTHER
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("message_id", messageId)
            put("type", type.name.lowercase())
            put("vehicle", vehicleType.name.lowercase())
            put("lat", latitude)
            put("lon", longitude)
            put("speed", speedKmh)
            put("heading", heading)
            put("timestamp", timestamp)
            put("ttl", ttl)
            geohash?.let { put("geohash", it) }
            signature?.let { put("signature", it) }
        }
    }

    fun toBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {

        fun fromJson(json: JSONObject): EmergencyAlert? {
            return try {
                EmergencyAlert(
                    messageId = json.getString("message_id"),
                    type = AlertType.valueOf(json.getString("type").uppercase()),
                    vehicleType = VehicleType.valueOf(json.getString("vehicle").uppercase()),
                    latitude = json.getDouble("lat"),
                    longitude = json.getDouble("lon"),
                    speedKmh = json.getInt("speed"),
                    heading = json.optInt("heading", 0),
                    timestamp = json.getString("timestamp"),
                    ttl = json.optInt("ttl", 7),
                    geohash = json.optString("geohash", null),
                    signature = json.optString("signature", null)
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromBytes(data: ByteArray): EmergencyAlert? {
            return try {
                val jsonString = String(data, Charsets.UTF_8)
                fromJson(JSONObject(jsonString))
            } catch (e: Exception) {
                null
            }
        }
    }

    fun distanceTo(lat: Double, lon: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat - latitude)
        val dLon = Math.toRadians(lon - longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(lat)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    fun isApproaching(lat: Double, lon: Double): Boolean {

        val bearing = Math.toDegrees(
            Math.atan2(
                Math.sin(Math.toRadians(lon - longitude)) * Math.cos(Math.toRadians(lat)),
                Math.cos(Math.toRadians(latitude)) * Math.sin(Math.toRadians(lat)) -
                        Math.sin(Math.toRadians(latitude)) * Math.cos(Math.toRadians(lat)) *
                        Math.cos(Math.toRadians(lon - longitude))
            )
        ).let { if (it < 0) it + 360 else it }

        val headingDiff = Math.abs(bearing - heading)
        return headingDiff < 45 || headingDiff > 315
    }

    fun getDistanceString(lat: Double, lon: Double): String {
        val distance = distanceTo(lat, lon)
        return when {
            distance < 100 -> "${distance.toInt()}m"
            distance < 1000 -> "${(distance / 10).toInt() * 10}m"
            else -> String.format("%.1fkm", distance / 1000)
        }
    }

    fun getVehicleDisplayName(): String = when (vehicleType) {
        VehicleType.AMBULANCE -> "Ambulância"
        VehicleType.POLICE -> "Polícia"
        VehicleType.FIRE_TRUCK -> "Bombeiros"
        VehicleType.RESCUE -> "Resgate"
        VehicleType.OTHER -> "Emergência"
    }

    fun getPriority(): Int = when (type) {
        AlertType.EMERGENCY -> 100
        AlertType.APPROACHING -> 80
        AlertType.PASSING -> 50
        AlertType.CLEARING -> 20
    }
}
