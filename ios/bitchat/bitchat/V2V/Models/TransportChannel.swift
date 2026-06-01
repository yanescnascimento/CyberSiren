import Foundation

public enum TransportChannel {
    case bleMesh
    case firebaseCloud
    case nostrRelay
}

public enum MessageProcessResult {
    case processed(messageId: String, channel: TransportChannel, latencyMs: Int64)
    case duplicate(messageId: String, originalChannel: TransportChannel, duplicateChannel: TransportChannel)
    case invalid(messageId: String?, reason: String)
}

public struct IncomingPacket: Equatable {
    public let data: Data
    public let channel: TransportChannel
    public let receivedAtMs: Int64
    public let metadata: [String: String]

    public init(
        data: Data,
        channel: TransportChannel,
        receivedAtMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        metadata: [String: String] = [:]
    ) {
        self.data = data
        self.channel = channel
        self.receivedAtMs = receivedAtMs
        self.metadata = metadata
    }

    public static func == (lhs: IncomingPacket, rhs: IncomingPacket) -> Bool {
        return lhs.data == rhs.data && lhs.channel == rhs.channel
    }
}

extension TransportChannel: Equatable {}

public protocol MessageTransport: AnyObject {
    var channel: TransportChannel { get }
    var isAvailable: Bool { get }
    func start() async
    func stop() async
    func send(packet: Data, targetGeohash: String?) async throws

    var onIncoming: ((IncomingPacket) -> Void)? { get set }
}
