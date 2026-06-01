import Foundation
import BitFoundation

struct EphemeralIdentity {
    let peerID: PeerID
    let sessionStart: Date
    var handshakeState: HandshakeState
}

enum HandshakeState {
    case none
    case initiated
    case inProgress
    case completed(fingerprint: String)
    case failed(reason: String)
}

struct CryptographicIdentity: Codable {
    let fingerprint: String
    let publicKey: Data

    var signingPublicKey: Data? = nil
    let firstSeen: Date
    let lastHandshake: Date?
}

struct SocialIdentity: Codable {
    let fingerprint: String
    var localPetname: String?
    var claimedNickname: String
    var trustLevel: TrustLevel
    var isFavorite: Bool
    var isBlocked: Bool
    var notes: String?
}

enum TrustLevel: String, Codable {
    case unknown = "unknown"
    case casual = "casual"
    case trusted = "trusted"
    case verified = "verified"
}

struct IdentityCache: Codable {

    var socialIdentities: [String: SocialIdentity] = [:]

    var nicknameIndex: [String: Set<String>] = [:]

    var verifiedFingerprints: Set<String> = []

    var lastInteractions: [String: Date] = [:]

    var blockedNostrPubkeys: Set<String> = []

    var version: Int = 1
}
