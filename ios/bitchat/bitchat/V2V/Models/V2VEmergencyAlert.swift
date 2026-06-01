import Foundation
import CoreLocation

public struct V2VEmergencyAlert: Equatable {
    public let messageId: String
    public let vehicleType: VehicleType
    public let alertType: AlertType
    public let latitude: Double
    public let longitude: Double

    public let speed: Float

    public let heading: Float

    public let timestamp: Int64

    public let senderPeerId: String
    public let signature: Data?

    public init(
        messageId: String = UUID().uuidString.uppercased(),
        vehicleType: VehicleType,
        alertType: AlertType = .approaching,
        latitude: Double,
        longitude: Double,
        speed: Float,
        heading: Float,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        senderPeerId: String,
        signature: Data? = nil
    ) {
        self.messageId = messageId
        self.vehicleType = vehicleType
        self.alertType = alertType
        self.latitude = latitude
        self.longitude = longitude
        self.speed = speed
        self.heading = heading
        self.timestamp = timestamp
        self.senderPeerId = senderPeerId
        self.signature = signature
    }

    public var speedKmh: Float { speed * 3.6 }

    public var headingDirection: String {
        let h = Double(heading)
        switch h {
        case 337.5..., 0..<22.5: return "N"
        case 22.5..<67.5: return "NE"
        case 67.5..<112.5: return "E"
        case 112.5..<157.5: return "SE"
        case 157.5..<202.5: return "S"
        case 202.5..<247.5: return "SW"
        case 247.5..<292.5: return "W"
        case 292.5..<337.5: return "NW"
        default: return "?"
        }
    }

    public func toJSON() -> [String: Any] {
        return [
            "id": messageId,
            "vt": vehicleType.rawValue,
            "at": alertType.rawValue,
            "lat": latitude,
            "lon": longitude,
            "spd": speed,
            "hdg": heading,
            "ts": timestamp,
            "pid": senderPeerId
        ]
    }

    public func toPayload() -> Data {
        guard let data = try? JSONSerialization.data(withJSONObject: toJSON(), options: []) else {
            return Data()
        }
        return data
    }

    public static func fromPayload(_ payload: Data) -> V2VEmergencyAlert? {
        guard let obj = try? JSONSerialization.jsonObject(with: payload, options: []) as? [String: Any] else {
            return nil
        }
        return fromJSON(obj)
    }

    public static func fromJSON(_ obj: [String: Any]) -> V2VEmergencyAlert? {
        guard
            let id = obj["id"] as? String,
            let vtRaw = obj["vt"] as? Int,
            let vt = VehicleType.fromCode(vtRaw),
            let lat = (obj["lat"] as? Double) ?? (obj["lat"] as? NSNumber).map({ $0.doubleValue }),
            let lon = (obj["lon"] as? Double) ?? (obj["lon"] as? NSNumber).map({ $0.doubleValue }),
            let pid = obj["pid"] as? String
        else { return nil }

        let atRaw = (obj["at"] as? Int) ?? AlertType.approaching.rawValue
        let at = AlertType.fromCode(atRaw) ?? .approaching
        let spd = (obj["spd"] as? NSNumber)?.floatValue ?? 0
        let hdg = (obj["hdg"] as? NSNumber)?.floatValue ?? 0
        let ts = (obj["ts"] as? NSNumber)?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000)

        return V2VEmergencyAlert(
            messageId: id,
            vehicleType: vt,
            alertType: at,
            latitude: lat,
            longitude: lon,
            speed: spd,
            heading: hdg,
            timestamp: ts,
            senderPeerId: pid
        )
    }
}

public enum UrgencyLevel: Int, Comparable {
    case critical = 0
    case high = 1
    case medium = 2
    case low = 3

    public static func < (lhs: UrgencyLevel, rhs: UrgencyLevel) -> Bool {
        return lhs.rawValue < rhs.rawValue
    }
}

public struct ReceivedAlert: Equatable {
    public let alert: V2VEmergencyAlert
    public let distanceMeters: Float
    public let receivedAt: Date

    public let relativeDirection: String

    public init(
        alert: V2VEmergencyAlert,
        distanceMeters: Float,
        receivedAt: Date = Date(),
        relativeDirection: String = ""
    ) {
        self.alert = alert
        self.distanceMeters = distanceMeters
        self.receivedAt = receivedAt
        self.relativeDirection = relativeDirection
    }

    public static let expirySeconds: TimeInterval = 60

    public var ageSeconds: Int {
        Int(Date().timeIntervalSince(receivedAt))
    }

    public var isValid: Bool {
        return Date().timeIntervalSince(receivedAt) < ReceivedAlert.expirySeconds
    }

    public var distanceDisplay: String {

        guard distanceMeters.isFinite, distanceMeters < 1_000_000 else { return "—" }
        if distanceMeters < 100 {
            return "\(Int(distanceMeters))m"
        }
        if distanceMeters < 1000 {
            return "\(Int(distanceMeters / 10) * 10)m"
        }
        return String(format: "%.1fkm", distanceMeters / 1000)
    }

    public var urgencyLevel: UrgencyLevel {
        switch distanceMeters {
        case ..<200: return .critical
        case ..<500: return .high
        case ..<1000: return .medium
        default: return .low
        }
    }
}
