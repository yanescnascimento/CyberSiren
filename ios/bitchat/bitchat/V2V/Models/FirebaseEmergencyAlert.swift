import Foundation

public struct FirebaseEmergencyAlert {
    public enum CloudAlertType: String {
        case emergency = "emergency"
        case approaching = "approaching"
        case passing = "passing"
        case clearing = "clearing"

        public var priority: Int {
            switch self {
            case .emergency: return 100
            case .approaching: return 80
            case .passing: return 50
            case .clearing: return 20
            }
        }
    }

    public enum CloudVehicleType: String {
        case ambulance = "ambulance"
        case police = "police"
        case fireTruck = "fire_truck"
        case rescue = "rescue"
        case other = "other"
    }

    public let messageId: String
    public let type: CloudAlertType
    public let vehicleType: CloudVehicleType
    public let latitude: Double
    public let longitude: Double

    public let speedKmh: Int

    public let heading: Int

    public let timestamp: String
    public let signature: String?
    public let geohash: String?
    public let ttl: Int

    public init(
        messageId: String = UUID().uuidString,
        type: CloudAlertType = .emergency,
        vehicleType: CloudVehicleType,
        latitude: Double,
        longitude: Double,
        speedKmh: Int,
        heading: Int = 0,
        timestamp: String = ISO8601DateFormatter().string(from: Date()),
        signature: String? = nil,
        geohash: String? = nil,
        ttl: Int = 7
    ) {
        self.messageId = messageId
        self.type = type
        self.vehicleType = vehicleType
        self.latitude = latitude
        self.longitude = longitude
        self.speedKmh = speedKmh
        self.heading = heading
        self.timestamp = timestamp
        self.signature = signature
        self.geohash = geohash
        self.ttl = ttl
    }

    public func toJSON() -> [String: Any] {
        var dict: [String: Any] = [
            "message_id": messageId,
            "type": type.rawValue,
            "vehicle": vehicleType.rawValue,
            "lat": latitude,
            "lon": longitude,
            "speed": speedKmh,
            "heading": heading,
            "timestamp": timestamp,
            "ttl": ttl
        ]
        if let geohash = geohash { dict["geohash"] = geohash }
        if let signature = signature { dict["signature"] = signature }
        return dict
    }

    public func toBytes() -> Data {
        return (try? JSONSerialization.data(withJSONObject: toJSON(), options: [])) ?? Data()
    }

    public static func fromJSON(_ obj: [String: Any]) -> FirebaseEmergencyAlert? {
        guard
            let id = obj["message_id"] as? String,
            let typeRaw = obj["type"] as? String,
            let type = CloudAlertType(rawValue: typeRaw),
            let vehicleRaw = obj["vehicle"] as? String,
            let vehicle = CloudVehicleType(rawValue: vehicleRaw),
            let lat = (obj["lat"] as? NSNumber)?.doubleValue,
            let lon = (obj["lon"] as? NSNumber)?.doubleValue,
            let speed = (obj["speed"] as? NSNumber)?.intValue,
            let timestamp = obj["timestamp"] as? String
        else { return nil }

        let heading = (obj["heading"] as? NSNumber)?.intValue ?? 0
        let ttl = (obj["ttl"] as? NSNumber)?.intValue ?? 7
        let signature = obj["signature"] as? String
        let geohash = obj["geohash"] as? String

        return FirebaseEmergencyAlert(
            messageId: id,
            type: type,
            vehicleType: vehicle,
            latitude: lat,
            longitude: lon,
            speedKmh: speed,
            heading: heading,
            timestamp: timestamp,
            signature: signature,
            geohash: geohash,
            ttl: ttl
        )
    }

    public static func fromBytes(_ data: Data) -> FirebaseEmergencyAlert? {
        guard let obj = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
            return nil
        }
        return fromJSON(obj)
    }
}

extension FirebaseEmergencyAlert {

    public init(from v2v: V2VEmergencyAlert) {
        let vehicle: CloudVehicleType = {
            switch v2v.vehicleType {
            case .ambulance:  return .ambulance
            case .policeCar:  return .police
            case .fireTruck:  return .fireTruck
            case .emergency:  return .other
            }
        }()
        self.init(
            messageId: v2v.messageId,
            type: .emergency,
            vehicleType: vehicle,
            latitude: v2v.latitude,
            longitude: v2v.longitude,
            speedKmh: Int(v2v.speed * 3.6),
            heading: Int(v2v.heading)
        )
    }
}
