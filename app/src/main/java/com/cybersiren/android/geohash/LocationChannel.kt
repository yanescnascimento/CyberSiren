package com.cybersiren.android.geohash

enum class GeohashChannelLevel(val precision: Int, val displayName: String) {
    BUILDING(8, "Building"),
    BLOCK(7, "Block"),
    NEIGHBORHOOD(6, "Neighborhood"),
    CITY(5, "City"),
    PROVINCE(4, "Province"),
    REGION(2, "REGION");

    companion object {
        fun allCases(): List<GeohashChannelLevel> = values().toList()
    }
}

data class GeohashChannel(
    val level: GeohashChannelLevel,
    val geohash: String
) {
    val id: String get() = "${level.name}-$geohash"

    val displayName: String get() = "${level.displayName} • $geohash"
}

sealed class ChannelID {
    object Mesh : ChannelID()
    data class Location(val channel: GeohashChannel) : ChannelID() {
        companion object {
            fun fromPersisted(levelName: String, geohash: String): Location? {
                return try {
                    val level = GeohashChannelLevel.valueOf(levelName)
                    Location(GeohashChannel(level, geohash))
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    val displayName: String
        get() = when (this) {
            is Mesh -> "Mesh"
            is Location -> channel.displayName
        }

    val nostrGeohashTag: String?
        get() = when (this) {
            is Mesh -> null
            is Location -> channel.geohash
        }

    override fun equals(other: Any?): Boolean {
        return when {
            this is Mesh && other is Mesh -> true
            this is Location && other is Location -> this.channel == other.channel
            else -> false
        }
    }

    override fun hashCode(): Int {
        return when (this) {
            is Mesh -> "mesh".hashCode()
            is Location -> channel.hashCode()
        }
    }
}
