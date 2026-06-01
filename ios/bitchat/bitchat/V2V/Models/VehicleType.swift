import Foundation

public enum VehicleType: Int, CaseIterable, Codable {
    case ambulance = 1
    case fireTruck = 2
    case policeCar = 3
    case emergency = 4

    public var displayName: String {
        switch self {
        case .ambulance: return "Ambulância"
        case .fireTruck: return "Bombeiros"
        case .policeCar: return "Polícia"
        case .emergency: return "Emergência"
        }
    }

    public var emoji: String {
        switch self {
        case .ambulance: return "\u{1F691}"
        case .fireTruck: return "\u{1F692}"
        case .policeCar: return "\u{1F693}"
        case .emergency: return "\u{26A0}\u{FE0F}"
        }
    }

    public static func fromCode(_ code: Int) -> VehicleType? {
        return VehicleType(rawValue: code)
    }
}

public enum AlertType: Int, Codable {
    case approaching = 1
    case passing = 2
    case stationary = 3
    case leaving = 4

    public static func fromCode(_ code: Int) -> AlertType? {
        return AlertType(rawValue: code)
    }
}

public enum AlertMode: Codable {
    case sender
    case receiver
}
