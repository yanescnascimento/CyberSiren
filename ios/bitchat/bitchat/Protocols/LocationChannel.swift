import Foundation

enum GeohashChannelLevel: CaseIterable, Codable, Equatable {
    case building
    case block
    case neighborhood
    case city
    case province
    case region

    var precision: Int {
        switch self {
        case .building: return 8
        case .block: return 7
        case .neighborhood: return 6
        case .city: return 5
        case .province: return 4
        case .region: return 2
    }
    }

    var displayName: String {
        switch self {
        case .building:
            return String(localized: "location_levels.building", comment: "Name for building-level location channel")
        case .block:
            return String(localized: "location_levels.block", comment: "Name for block-level location channel")
        case .neighborhood:
            return String(localized: "location_levels.neighborhood", comment: "Name for neighborhood-level location channel")
        case .city:
            return String(localized: "location_levels.city", comment: "Name for city-level location channel")
        case .province:
            return String(localized: "location_levels.province", comment: "Name for province-level location channel")
        case .region:
            return String(localized: "location_levels.region", comment: "Name for region-level location channel")
        }
    }
}

extension GeohashChannelLevel {
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let raw = try? container.decode(String.self) {
            switch raw {
            case "building": self = .building
            case "block": self = .block
            case "neighborhood": self = .neighborhood
            case "city": self = .city
            case "region": self = .province
            case "country": self = .region
            case "province": self = .province
            default:
                self = .block
            }
        } else if let precision = try? container.decode(Int.self) {
            switch precision {
            case 8: self = .building
            case 7: self = .block
            case 6: self = .neighborhood
            case 5: self = .city
            case 4: self = .province
            case 0...3: self = .region
            default: self = .block
            }
        } else {
            self = .block
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .building: try container.encode("building")
        case .block: try container.encode("block")
        case .neighborhood: try container.encode("neighborhood")
        case .city: try container.encode("city")
        case .province: try container.encode("province")
        case .region: try container.encode("region")
        }
    }
}

struct GeohashChannel: Codable, Equatable, Hashable, Identifiable {
    let level: GeohashChannelLevel
    let geohash: String

    var id: String { "\(level)-\(geohash)" }

    var displayName: String {
        "\(level.displayName) • \(geohash)"
    }
}

enum ChannelID: Equatable, Codable {
    case mesh
    case location(GeohashChannel)

    var displayName: String {
        switch self {
        case .mesh:
            return "Mesh"
        case .location(let ch):
            return ch.displayName
        }
    }

    var nostrGeohashTag: String? {
        switch self {
        case .mesh: return nil
        case .location(let ch): return ch.geohash
        }
    }

    var isMesh: Bool {
        switch self {
        case .mesh:     true
        case .location: false
        }
    }

    var isLocation: Bool {
        switch self {
        case .mesh:     false
        case .location: true
        }
    }
}
