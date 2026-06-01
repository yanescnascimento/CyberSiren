import Foundation

enum NoiseSecurityConstants {

    static let maxMessageSize = 65535

    static let maxHandshakeMessageSize = 2048

    static let sessionTimeout: TimeInterval = 86400

    static let maxMessagesPerSession: UInt64 = 1_000_000_000

    static let handshakeTimeout: TimeInterval = 60

    static let maxSessionsPerPeer = 3

    static let maxHandshakesPerMinute = 10
    static let maxMessagesPerSecond = 100

    static let maxGlobalHandshakesPerMinute = 30
    static let maxGlobalMessagesPerSecond = 500
}
