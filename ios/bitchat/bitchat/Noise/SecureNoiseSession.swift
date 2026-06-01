import Foundation

final class SecureNoiseSession: NoiseSession {
    private(set) var messageCount: UInt64 = 0
    private var sessionStartTime = Date()
    private(set) var lastActivityTime = Date()

    override func encrypt(_ plaintext: Data) throws -> Data {

        if Date().timeIntervalSince(sessionStartTime) > NoiseSecurityConstants.sessionTimeout {
            throw NoiseSecurityError.sessionExpired
        }

        if messageCount >= NoiseSecurityConstants.maxMessagesPerSession {
            throw NoiseSecurityError.sessionExhausted
        }

        guard NoiseSecurityValidator.validateMessageSize(plaintext) else {
            throw NoiseSecurityError.messageTooLarge
        }

        let encrypted = try super.encrypt(plaintext)
        messageCount += 1
        lastActivityTime = Date()

        return encrypted
    }

    override func decrypt(_ ciphertext: Data) throws -> Data {

        if Date().timeIntervalSince(sessionStartTime) > NoiseSecurityConstants.sessionTimeout {
            throw NoiseSecurityError.sessionExpired
        }

        guard NoiseSecurityValidator.validateMessageSize(ciphertext) else {
            throw NoiseSecurityError.messageTooLarge
        }

        let decrypted = try super.decrypt(ciphertext)
        lastActivityTime = Date()

        return decrypted
    }

    func needsRenegotiation() -> Bool {

        let messageThreshold = UInt64(Double(NoiseSecurityConstants.maxMessagesPerSession) * 0.9)
        if messageCount >= messageThreshold {
            return true
        }

        if Date().timeIntervalSince(lastActivityTime) > NoiseSecurityConstants.sessionTimeout {
            return true
        }

        return false
    }

    #if DEBUG
    func setLastActivityTimeForTesting(_ date: Date) {
        lastActivityTime = date
    }

    func setMessageCountForTesting(_ count: UInt64) {
        messageCount = count
    }

    func setSessionStartTimeForTesting(_ date: Date) {
        sessionStartTime = date
    }
    #endif
}
