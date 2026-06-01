import Foundation
import BitLogger

struct InputValidator {

    struct Limits {
        static let maxNicknameLength = 50

        static let maxMessageLength = 60_000
    }

    static func validateUserString(_ string: String, maxLength: Int) -> String? {
        guard let trimmed = string.trimmedOrNilIfEmpty, trimmed.count <= maxLength else { return nil }

        let controlChars = CharacterSet.controlCharacters
        if !trimmed.unicodeScalars.allSatisfy({ !controlChars.contains($0) }) {

            let controlCharCount = trimmed.unicodeScalars.filter { controlChars.contains($0) }.count
            SecureLogger.debug(
                "Input validation rejected string (length: \(trimmed.count), control chars: \(controlCharCount))",
                category: .security
            )
            return nil
        }

        return trimmed
    }

    static func validateNickname(_ nickname: String) -> String? {
        return validateUserString(nickname, maxLength: Limits.maxNicknameLength)
    }

    static func validateTimestamp(_ timestamp: Date) -> Bool {
        let now = Date()

        let fiveMinutesAgo = now.addingTimeInterval(-300)
        let fiveMinutesFromNow = now.addingTimeInterval(300)
        return timestamp >= fiveMinutesAgo && timestamp <= fiveMinutesFromNow
    }

}
