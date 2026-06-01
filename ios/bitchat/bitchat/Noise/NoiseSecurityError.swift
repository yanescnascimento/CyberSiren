import Foundation

enum NoiseSecurityError: Error {
    case sessionExpired
    case sessionExhausted
    case messageTooLarge
    case invalidPeerID
    case rateLimitExceeded
    case handshakeTimeout
}
