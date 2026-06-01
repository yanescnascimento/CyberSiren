import Foundation

struct NoiseSecurityValidator {

    static func validateMessageSize(_ data: Data) -> Bool {
        return data.count <= NoiseSecurityConstants.maxMessageSize
    }

    static func validateHandshakeMessageSize(_ data: Data) -> Bool {
        return data.count <= NoiseSecurityConstants.maxHandshakeMessageSize
    }
}
