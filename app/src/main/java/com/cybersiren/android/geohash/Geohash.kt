package com.cybersiren.android.geohash

object Geohash {

    private val base32Chars = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray()
    private val charToValue: Map<Char, Int> = base32Chars.withIndex().associate { it.value to it.index }

    data class Bounds(val latMin: Double, val latMax: Double, val lonMin: Double, val lonMax: Double)

    fun encode(latitude: Double, longitude: Double, precision: Int): String {
        if (precision <= 0) return ""

        var latInterval = -90.0 to 90.0
        var lonInterval = -180.0 to 180.0

        var isEven = true
        var bit = 0
        var ch = 0
        val geohash = StringBuilder()

        val lat = latitude.coerceIn(-90.0, 90.0)
        val lon = longitude.coerceIn(-180.0, 180.0)

        while (geohash.length < precision) {
            if (isEven) {
                val mid = (lonInterval.first + lonInterval.second) / 2
                if (lon >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonInterval = mid to lonInterval.second
                } else {
                    lonInterval = lonInterval.first to mid
                }
            } else {
                val mid = (latInterval.first + latInterval.second) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latInterval = mid to latInterval.second
                } else {
                    latInterval = latInterval.first to mid
                }
            }

            isEven = !isEven
            if (bit < 4) {
                bit += 1
            } else {
                geohash.append(base32Chars[ch])
                bit = 0
                ch = 0
            }
        }

        return geohash.toString()
    }

    fun decodeToCenter(geohash: String): Pair<Double, Double> {
        val b = decodeToBounds(geohash)
        val latCenter = (b.latMin + b.latMax) / 2
        val lonCenter = (b.lonMin + b.lonMax) / 2
        return latCenter to lonCenter
    }

    fun decodeToBounds(geohash: String): Bounds {
        if (geohash.isEmpty()) return Bounds(0.0, 0.0, 0.0, 0.0)

        var latInterval = -90.0 to 90.0
        var lonInterval = -180.0 to 180.0
        var isEven = true

        geohash.lowercase().forEach { ch ->
            val cd = charToValue[ch] ?: return Bounds(0.0, 0.0, 0.0, 0.0)
            for (mask in intArrayOf(16, 8, 4, 2, 1)) {
                if (isEven) {
                    val mid = (lonInterval.first + lonInterval.second) / 2
                    if ((cd and mask) != 0) {
                        lonInterval = mid to lonInterval.second
                    } else {
                        lonInterval = lonInterval.first to mid
                    }
                } else {
                    val mid = (latInterval.first + latInterval.second) / 2
                    if ((cd and mask) != 0) {
                        latInterval = mid to latInterval.second
                    } else {
                        latInterval = latInterval.first to mid
                    }
                }
                isEven = !isEven
            }
        }
        return Bounds(
            latMin = minOf(latInterval.first, latInterval.second),
            latMax = maxOf(latInterval.first, latInterval.second),
            lonMin = minOf(lonInterval.first, lonInterval.second),
            lonMax = maxOf(lonInterval.first, lonInterval.second)
        )
    }

    fun neighborsSamePrecision(geohash: String): Set<String> {
        if (geohash.isEmpty()) return emptySet()
        val p = geohash.length
        val b = decodeToBounds(geohash)
        val dLat = b.latMax - b.latMin
        val dLon = b.lonMax - b.lonMin

        fun wrapLon(lon: Double): Double {
            var x = lon
            while (x > 180.0) x -= 360.0
            while (x < -180.0) x += 360.0
            return x
        }

        val neighbors = mutableSetOf<String>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val centerLat = (b.latMin + b.latMax) / 2 + dy * dLat
                val rawLonCenter = (b.lonMin + b.lonMax) / 2 + dx * dLon
                val centerLon = wrapLon(rawLonCenter)
                val enc = encode(centerLat.coerceIn(-90.0, 90.0), centerLon, p)
                if (enc.isNotEmpty() && enc != geohash) neighbors.add(enc)
            }
        }
        return neighbors
    }
}
