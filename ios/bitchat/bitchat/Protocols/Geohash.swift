import Foundation

enum Geohash {
    private static let base32Chars = Array("0123456789bcdefghjkmnpqrstuvwxyz")
    private static let base32Map: [Character: Int] = {
        var map: [Character: Int] = [:]
        for (i, c) in base32Chars.enumerated() { map[c] = i }
        return map
    }()

    static func isValidBuildingGeohash(_ geohash: String) -> Bool {
        guard geohash.count == 8 else { return false }
        return geohash.lowercased().allSatisfy { base32Map[$0] != nil }
    }

    static func encode(latitude: Double, longitude: Double, precision: Int) -> String {
        guard precision > 0 else { return "" }

        var latInterval: (Double, Double) = (-90.0, 90.0)
        var lonInterval: (Double, Double) = (-180.0, 180.0)

        var isEven = true
        var bit = 0
        var ch = 0
        var geohash: [Character] = []

        let lat = max(-90.0, min(90.0, latitude))
        let lon = max(-180.0, min(180.0, longitude))

        while geohash.count < precision {
            if isEven {
                let mid = (lonInterval.0 + lonInterval.1) / 2
                if lon >= mid {
                    ch |= (1 << (4 - bit))
                    lonInterval.0 = mid
                } else {
                    lonInterval.1 = mid
                }
            } else {
                let mid = (latInterval.0 + latInterval.1) / 2
                if lat >= mid {
                    ch |= (1 << (4 - bit))
                    latInterval.0 = mid
                } else {
                    latInterval.1 = mid
                }
            }

            isEven.toggle()
            if bit < 4 {
                bit += 1
            } else {
                geohash.append(base32Chars[ch])
                bit = 0
                ch = 0
            }
        }

        return String(geohash)
    }

    static func decodeCenter(_ geohash: String) -> (lat: Double, lon: Double) {
        var latInterval: (Double, Double) = (-90.0, 90.0)
        var lonInterval: (Double, Double) = (-180.0, 180.0)

        var isEven = true
        for ch in geohash.lowercased() {
            guard let cd = base32Map[ch] else { continue }
            for mask in [16, 8, 4, 2, 1] {
                if isEven {
                    let mid = (lonInterval.0 + lonInterval.1) / 2
                    if (cd & mask) != 0 { lonInterval.0 = mid } else { lonInterval.1 = mid }
                } else {
                    let mid = (latInterval.0 + latInterval.1) / 2
                    if (cd & mask) != 0 { latInterval.0 = mid } else { latInterval.1 = mid }
                }
                isEven.toggle()
            }
        }
        let lat = (latInterval.0 + latInterval.1) / 2
        let lon = (lonInterval.0 + lonInterval.1) / 2
        return (lat, lon)
    }

    static func decodeBounds(_ geohash: String) -> (latMin: Double, latMax: Double, lonMin: Double, lonMax: Double) {
        var latInterval: (Double, Double) = (-90.0, 90.0)
        var lonInterval: (Double, Double) = (-180.0, 180.0)

        var isEven = true
        for ch in geohash.lowercased() {
            guard let cd = base32Map[ch] else { continue }
            for mask in [16, 8, 4, 2, 1] {
                if isEven {
                    let mid = (lonInterval.0 + lonInterval.1) / 2
                    if (cd & mask) != 0 { lonInterval.0 = mid } else { lonInterval.1 = mid }
                } else {
                    let mid = (latInterval.0 + latInterval.1) / 2
                    if (cd & mask) != 0 { latInterval.0 = mid } else { latInterval.1 = mid }
                }
                isEven.toggle()
            }
        }
        return (latInterval.0, latInterval.1, lonInterval.0, lonInterval.1)
    }

    static func neighbors(of geohash: String) -> [String] {
        guard !geohash.isEmpty else { return [] }

        let precision = geohash.count
        let bounds = decodeBounds(geohash)
        let center = decodeCenter(geohash)

        let latHeight = bounds.latMax - bounds.latMin
        let lonWidth = bounds.lonMax - bounds.lonMin

        func wrapLongitude(_ lon: Double) -> Double {
            var wrapped = lon
            while wrapped > 180.0 { wrapped -= 360.0 }
            while wrapped < -180.0 { wrapped += 360.0 }
            return wrapped
        }

        func clampLatitude(_ lat: Double) -> Double {
            return max(-90.0, min(90.0, lat))
        }

        let neighbors: [(lat: Double, lon: Double)] = [
            (center.lat + latHeight, center.lon),
            (center.lat + latHeight, center.lon + lonWidth),
            (center.lat, center.lon + lonWidth),
            (center.lat - latHeight, center.lon + lonWidth),
            (center.lat - latHeight, center.lon),
            (center.lat - latHeight, center.lon - lonWidth),
            (center.lat, center.lon - lonWidth),
            (center.lat + latHeight, center.lon - lonWidth)
        ]

        return neighbors.compactMap { neighbor in
            let lat = clampLatitude(neighbor.lat)
            let lon = wrapLongitude(neighbor.lon)

            if (neighbor.lat > 90.0 || neighbor.lat < -90.0) {
                return nil
            }

            return encode(latitude: lat, longitude: lon, precision: precision)
        }
    }
}
