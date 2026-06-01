package com.cybersiren.android.v2v.model

import org.json.JSONObject
import java.util.UUID

data class EmergencyAlert(
    val messageId: String = UUID.randomUUID().toString().uppercase(),
    val vehicleType: VehicleType,
    val alertType: AlertType = AlertType.APPROACHING,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val heading: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val senderPeerId: String,
    val signature: ByteArray? = null
) {

    val speedKmh: Float
        get() = speed * 3.6f

    val headingDirection: String
        get() = when {
            heading >= 337.5 || heading < 22.5 -> "N"
            heading >= 22.5 && heading < 67.5 -> "NE"
            heading >= 67.5 && heading < 112.5 -> "E"
            heading >= 112.5 && heading < 157.5 -> "SE"
            heading >= 157.5 && heading < 202.5 -> "S"
            heading >= 202.5 && heading < 247.5 -> "SW"
            heading >= 247.5 && heading < 292.5 -> "W"
            heading >= 292.5 && heading < 337.5 -> "NW"
            else -> "?"
        }

    fun toJson(): String {
        return JSONObject().apply {
            put("id", messageId)
            put("vt", vehicleType.code)
            put("at", alertType.code)
            put("lat", latitude)
            put("lon", longitude)
            put("spd", speed)
            put("hdg", heading)
            put("ts", timestamp)
            put("pid", senderPeerId)
        }.toString()
    }

    fun toPayload(): ByteArray {
        return toJson().toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmergencyAlert

        if (messageId != other.messageId) return false
        if (vehicleType != other.vehicleType) return false
        if (alertType != other.alertType) return false
        if (latitude != other.latitude) return false
        if (longitude != other.longitude) return false
        if (speed != other.speed) return false
        if (heading != other.heading) return false
        if (timestamp != other.timestamp) return false
        if (senderPeerId != other.senderPeerId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + vehicleType.hashCode()
        result = 31 * result + alertType.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + heading.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + senderPeerId.hashCode()
        return result
    }

    companion object {

        fun fromJson(json: String): EmergencyAlert? {
            return try {
                val obj = JSONObject(json)
                EmergencyAlert(
                    messageId = obj.getString("id"),
                    vehicleType = VehicleType.fromCode(obj.getInt("vt")) ?: VehicleType.EMERGENCY,
                    alertType = AlertType.fromCode(obj.getInt("at")) ?: AlertType.APPROACHING,
                    latitude = obj.getDouble("lat"),
                    longitude = obj.getDouble("lon"),
                    speed = obj.getDouble("spd").toFloat(),
                    heading = obj.getDouble("hdg").toFloat(),
                    timestamp = obj.getLong("ts"),
                    senderPeerId = obj.getString("pid")
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromPayload(payload: ByteArray): EmergencyAlert? {
            return try {
                fromJson(String(payload, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class ReceivedAlert(
    val alert: EmergencyAlert,
    val distanceMeters: Float,
    val receivedAt: Long = System.currentTimeMillis(),
    val relativeDirection: String = ""
) {

    val ageSeconds: Long
        get() = (System.currentTimeMillis() - receivedAt) / 1000

    val distanceDisplay: String
        get() = when {

            !distanceMeters.isFinite() || distanceMeters >= 1_000_000f -> "—"
            distanceMeters < 100 -> "${distanceMeters.toInt()}m"
            distanceMeters < 1000 -> "${(distanceMeters / 10).toInt() * 10}m"
            else -> String.format("%.1fkm", distanceMeters / 1000)
        }

    val urgencyLevel: UrgencyLevel
        get() = when {
            distanceMeters < 200 -> UrgencyLevel.CRITICAL
            distanceMeters < 500 -> UrgencyLevel.HIGH
            distanceMeters < 1000 -> UrgencyLevel.MEDIUM
            else -> UrgencyLevel.LOW
        }

    val isValid: Boolean
        get() = ageSeconds < ALERT_EXPIRY_SECONDS

    companion object {
        const val ALERT_EXPIRY_SECONDS = 60L
    }
}

enum class UrgencyLevel {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
