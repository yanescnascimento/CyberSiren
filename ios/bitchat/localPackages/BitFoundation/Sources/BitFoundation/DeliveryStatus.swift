import struct Foundation.Date

public enum DeliveryStatus: Codable, Equatable, Hashable {
    case sending
    case sent
    case delivered(to: String, at: Date)
    case read(by: String, at: Date)
    case failed(reason: String)
    case partiallyDelivered(reached: Int, total: Int)

    public var displayText: String {
        switch self {
        case .sending:
            return "Sending..."
        case .sent:
            return "Sent"
        case .delivered(let nickname, _):
            return "Delivered to \(nickname)"
        case .read(let nickname, _):
            return "Read by \(nickname)"
        case .failed(let reason):
            return "Failed: \(reason)"
        case .partiallyDelivered(let reached, let total):
            return "Delivered to \(reached)/\(total)"
        }
    }
}
