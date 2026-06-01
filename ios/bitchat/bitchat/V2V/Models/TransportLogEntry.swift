import Foundation

public enum TransportType: String {
    case ble = "BLE"
    case firebase = "FIREBASE"

    public var label: String {
        switch self {
        case .ble: return "BLE Mesh"
        case .firebase: return "Firebase Cloud"
        }
    }
}

public enum TransportDirection: String {
    case send = "SEND"
    case receive = "RECEIVE"

    public var symbol: String {
        switch self {
        case .send: return "↑"
        case .receive: return "↓"
        }
    }
}

public struct TransportLogEntry: Identifiable, Equatable {
    public let id: String
    public let timestamp: Date
    public let transport: TransportType
    public let direction: TransportDirection
    public let messageId: String
    public let latencyMs: Int64?
    public let success: Bool
    public let payloadBytes: Int
    public let details: String

    public init(
        id: String = UUID().uuidString,
        timestamp: Date = Date(),
        transport: TransportType,
        direction: TransportDirection,
        messageId: String,
        latencyMs: Int64? = nil,
        success: Bool = true,
        payloadBytes: Int = 0,
        details: String = ""
    ) {
        self.id = id
        self.timestamp = timestamp
        self.transport = transport
        self.direction = direction
        self.messageId = messageId
        self.latencyMs = latencyMs
        self.success = success
        self.payloadBytes = payloadBytes
        self.details = details
    }

    public func ageText(now: Date = Date()) -> String {
        let diff = Int(now.timeIntervalSince(timestamp))
        switch diff {
        case ..<5: return "agora"
        case ..<60: return "\(diff)s atrás"
        case ..<3600: return "\(diff / 60)m atrás"
        default: return "\(diff / 3600)h atrás"
        }
    }
}
