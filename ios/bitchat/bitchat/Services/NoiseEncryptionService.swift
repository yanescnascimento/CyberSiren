import BitLogger
import BitFoundation
import Foundation
import CryptoKit

enum EncryptionStatus: Equatable {
    case none
    case noHandshake
    case noiseHandshaking
    case noiseSecured
    case noiseVerified

    var icon: String? {
        switch self {
        case .none:
            return "lock.slash"
        case .noHandshake:
            return nil
        case .noiseHandshaking:
            return "lock.rotation"
        case .noiseSecured:
            return "lock.fill"
        case .noiseVerified:
            return "checkmark.seal.fill"
        }
    }

    var description: String {
        switch self {
        case .none:
            return String(localized: "encryption.status.failed", comment: "Status text when encryption failed")
        case .noHandshake:
            return String(localized: "encryption.status.not_encrypted", comment: "Status text when no encryption handshake happened")
        case .noiseHandshaking:
            return String(localized: "encryption.status.establishing", comment: "Status text when encryption is being established")
        case .noiseSecured:
            return String(localized: "encryption.status.secured", comment: "Status text when encryption is secured but not verified")
        case .noiseVerified:
            return String(localized: "encryption.status.verified", comment: "Status text when encryption is verified")
        }
    }

    var accessibilityDescription: String {
        switch self {
        case .none:
            return String(localized: "encryption.accessibility.failed", comment: "Accessibility text when encryption failed")
        case .noHandshake:
            return String(localized: "encryption.accessibility.not_encrypted", comment: "Accessibility text when encryption is not established")
        case .noiseHandshaking:
            return String(localized: "encryption.accessibility.establishing", comment: "Accessibility text when encryption is being established")
        case .noiseSecured:
            return String(localized: "encryption.accessibility.secured", comment: "Accessibility text when encryption is secured")
        case .noiseVerified:
            return String(localized: "encryption.accessibility.verified", comment: "Accessibility text when encryption is verified")
        }
    }
}

final class NoiseEncryptionService {

    private let staticIdentityKey: Curve25519.KeyAgreement.PrivateKey
    public let staticIdentityPublicKey: Curve25519.KeyAgreement.PublicKey

    private let signingKey: Curve25519.Signing.PrivateKey
    public let signingPublicKey: Curve25519.Signing.PublicKey

    private let sessionManager: NoiseSessionManager

    private var peerFingerprints: [PeerID: String] = [:]
    private var fingerprintToPeerID: [String: PeerID] = [:]

    private let serviceQueue = DispatchQueue(label: "com.cybersiren.ios.noise.service", attributes: .concurrent)

    private let rateLimiter = NoiseRateLimiter()
    private let keychain: KeychainManagerProtocol

    private var rekeyTimer: Timer?
    private let rekeyCheckInterval: TimeInterval = 60.0

    private var onPeerAuthenticatedHandlers: [((PeerID, String) -> Void)] = []
    var onHandshakeRequired: ((PeerID) -> Void)?

    func addOnPeerAuthenticatedHandler(_ handler: @escaping (PeerID, String) -> Void) {
        serviceQueue.async(flags: .barrier) { [weak self] in
            self?.onPeerAuthenticatedHandlers.append(handler)
        }
    }

    var onPeerAuthenticated: ((PeerID, String) -> Void)? {
        get { nil }
        set {
            if let handler = newValue {
                addOnPeerAuthenticatedHandler(handler)
            }
        }
    }

    init(keychain: KeychainManagerProtocol) {
        self.keychain = keychain

        let loadedKey: Curve25519.KeyAgreement.PrivateKey

        let noiseKeyResult = keychain.getIdentityKeyWithResult(forKey: "noiseStaticKey")

        switch noiseKeyResult {
        case .success(let identityData):
            if let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: identityData) {
                loadedKey = key
                SecureLogger.logKeyOperation(.load, keyType: "noiseStaticKey", success: true)
            } else {

                SecureLogger.warning("Noise static key data corrupted, regenerating", category: .keychain)
                loadedKey = Self.generateAndSaveNoiseKey(keychain: keychain)
            }

        case .itemNotFound:

            loadedKey = Self.generateAndSaveNoiseKey(keychain: keychain)

        case .accessDenied:

            SecureLogger.error(NSError(domain: "Keychain", code: -1),
                               context: "Keychain access denied - using ephemeral identity", category: .keychain)
            loadedKey = Curve25519.KeyAgreement.PrivateKey()

        case .deviceLocked, .authenticationFailed:

            SecureLogger.warning("Device locked or auth failed - using ephemeral identity until unlocked", category: .keychain)
            loadedKey = Curve25519.KeyAgreement.PrivateKey()

        case .otherError(let status):

            SecureLogger.error(NSError(domain: "Keychain", code: Int(status)),
                               context: "Unexpected keychain error - using ephemeral identity", category: .keychain)
            loadedKey = Curve25519.KeyAgreement.PrivateKey()
        }

        self.staticIdentityKey = loadedKey
        self.staticIdentityPublicKey = staticIdentityKey.publicKey

        let loadedSigningKey: Curve25519.Signing.PrivateKey

        let signingKeyResult = keychain.getIdentityKeyWithResult(forKey: "ed25519SigningKey")

        switch signingKeyResult {
        case .success(let signingData):
            if let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: signingData) {
                loadedSigningKey = key
                SecureLogger.logKeyOperation(.load, keyType: "ed25519SigningKey", success: true)
            } else {

                SecureLogger.warning("Ed25519 signing key data corrupted, regenerating", category: .keychain)
                loadedSigningKey = Self.generateAndSaveSigningKey(keychain: keychain)
            }

        case .itemNotFound:

            loadedSigningKey = Self.generateAndSaveSigningKey(keychain: keychain)

        case .accessDenied:

            SecureLogger.error(NSError(domain: "Keychain", code: -1),
                               context: "Keychain access denied - using ephemeral signing key", category: .keychain)
            loadedSigningKey = Curve25519.Signing.PrivateKey()

        case .deviceLocked, .authenticationFailed:

            SecureLogger.warning("Device locked or auth failed - using ephemeral signing key until unlocked", category: .keychain)
            loadedSigningKey = Curve25519.Signing.PrivateKey()

        case .otherError(let status):

            SecureLogger.error(NSError(domain: "Keychain", code: Int(status)),
                               context: "Unexpected keychain error - using ephemeral signing key", category: .keychain)
            loadedSigningKey = Curve25519.Signing.PrivateKey()
        }

        self.signingKey = loadedSigningKey
        self.signingPublicKey = signingKey.publicKey

        self.sessionManager = NoiseSessionManager(localStaticKey: staticIdentityKey, keychain: keychain)

        sessionManager.onSessionEstablished = { [weak self] peerID, remoteStaticKey in
            self?.handleSessionEstablished(peerID: peerID, remoteStaticKey: remoteStaticKey)
        }

        startRekeyTimer()
    }

    private static func generateAndSaveNoiseKey(keychain: KeychainManagerProtocol) -> Curve25519.KeyAgreement.PrivateKey {
        let newKey = Curve25519.KeyAgreement.PrivateKey()
        let keyData = newKey.rawRepresentation

        let saveResult = keychain.saveIdentityKeyWithResult(keyData, forKey: "noiseStaticKey")

        switch saveResult {
        case .success:
            SecureLogger.logKeyOperation(.create, keyType: "noiseStaticKey", success: true)
        case .duplicateItem:

            SecureLogger.warning("Noise key already exists (race condition?)", category: .keychain)
        default:

            SecureLogger.error(NSError(domain: "Keychain", code: -1),
                               context: "Failed to persist noise static key - identity will be lost on restart",
                               category: .keychain)
        }

        return newKey
    }

    private static func generateAndSaveSigningKey(keychain: KeychainManagerProtocol) -> Curve25519.Signing.PrivateKey {
        let newKey = Curve25519.Signing.PrivateKey()
        let keyData = newKey.rawRepresentation

        let saveResult = keychain.saveIdentityKeyWithResult(keyData, forKey: "ed25519SigningKey")

        switch saveResult {
        case .success:
            SecureLogger.logKeyOperation(.create, keyType: "ed25519SigningKey", success: true)
        case .duplicateItem:

            SecureLogger.warning("Signing key already exists (race condition?)", category: .keychain)
        default:

            SecureLogger.error(NSError(domain: "Keychain", code: -1),
                               context: "Failed to persist signing key - identity will be lost on restart",
                               category: .keychain)
        }

        return newKey
    }

    func getStaticPublicKeyData() -> Data {
        return staticIdentityPublicKey.rawRepresentation
    }

    func getSigningPublicKeyData() -> Data {
        return signingPublicKey.rawRepresentation
    }

    func getIdentityFingerprint() -> String {
        staticIdentityPublicKey.rawRepresentation.sha256Fingerprint()
    }

    func getPeerPublicKeyData(_ peerID: PeerID) -> Data? {
        return sessionManager.getRemoteStaticKey(for: peerID)?.rawRepresentation
    }

    func clearPersistentIdentity() {

        let deletedStatic = keychain.deleteIdentityKey(forKey: "noiseStaticKey")
        let deletedSigning = keychain.deleteIdentityKey(forKey: "ed25519SigningKey")
        SecureLogger.logKeyOperation(.delete, keyType: "identity keys", success: deletedStatic && deletedSigning)
        SecureLogger.warning("Panic mode activated - identity cleared", category: .security)

        stopRekeyTimer()
    }

    func signData(_ data: Data) -> Data? {
        do {
            let signature = try signingKey.signature(for: data)
            return signature
        } catch {
            SecureLogger.error(error, context: "Failed to sign data")
            return nil
        }
    }

    func verifySignature(_ signature: Data, for data: Data, publicKey: Data) -> Bool {
        do {
            let signingPublicKey = try Curve25519.Signing.PublicKey(rawRepresentation: publicKey)
            return signingPublicKey.isValidSignature(signature, for: data)
        } catch {
            SecureLogger.error(error, context: "Failed to verify signature")
            return false
        }
    }

    func buildAnnounceSignature(peerID: Data, noiseKey: Data, ed25519Key: Data, nickname: String, timestampMs: UInt64) -> Data? {
        let message = canonicalAnnounceBytes(peerID: peerID, noiseKey: noiseKey, ed25519Key: ed25519Key, nickname: nickname, timestampMs: timestampMs)
        return signData(message)
    }

    func verifyAnnounceSignature(signature: Data, peerID: Data, noiseKey: Data, ed25519Key: Data, nickname: String, timestampMs: UInt64, publicKey: Data) -> Bool {
        let message = canonicalAnnounceBytes(peerID: peerID, noiseKey: noiseKey, ed25519Key: ed25519Key, nickname: nickname, timestampMs: timestampMs)
        return verifySignature(signature, for: message, publicKey: publicKey)
    }

    private func canonicalAnnounceBytes(peerID: Data, noiseKey: Data, ed25519Key: Data, nickname: String, timestampMs: UInt64) -> Data {
        var out = Data()

        let context = "bitchat-announce-v1".data(using: .utf8) ?? Data()
        out.append(UInt8(min(context.count, 255)))
        out.append(context.prefix(255))

        let peerID8 = peerID.prefix(8)
        out.append(peerID8)
        if peerID8.count < 8 { out.append(Data(repeating: 0, count: 8 - peerID8.count)) }

        let noise32 = noiseKey.prefix(32)
        out.append(noise32)
        if noise32.count < 32 { out.append(Data(repeating: 0, count: 32 - noise32.count)) }

        let ed32 = ed25519Key.prefix(32)
        out.append(ed32)
        if ed32.count < 32 { out.append(Data(repeating: 0, count: 32 - ed32.count)) }

        let nickData = nickname.data(using: .utf8) ?? Data()
        out.append(UInt8(min(nickData.count, 255)))
        out.append(nickData.prefix(255))

        var ts = timestampMs.bigEndian
        withUnsafeBytes(of: &ts) { raw in out.append(contentsOf: raw) }
        return out
    }

    func signPacket(_ packet: BitchatPacket) -> BitchatPacket? {

        guard let packetData = packet.toBinaryDataForSigning() else {
            return nil
        }

        guard let signature = signData(packetData) else {
            return nil
        }

        var signedPacket = packet
        signedPacket.signature = signature
        return signedPacket
    }

    func verifyPacketSignature(_ packet: BitchatPacket, publicKey: Data) -> Bool {
        guard let signature = packet.signature else {
            return false
        }

        guard let packetData = packet.toBinaryDataForSigning() else {
            return false
        }

        return verifySignature(signature, for: packetData, publicKey: publicKey)
    }

    func initiateHandshake(with peerID: PeerID) throws -> Data {

        guard peerID.isValid else {
            SecureLogger.warning(.authenticationFailed(peerID: peerID.id))
            throw NoiseSecurityError.invalidPeerID
        }

        guard rateLimiter.allowHandshake(from: peerID) else {
            SecureLogger.warning(.authenticationFailed(peerID: "Rate limited: \(peerID)"))
            throw NoiseSecurityError.rateLimitExceeded
        }

        SecureLogger.info(.handshakeStarted(peerID: peerID.id))

        let handshakeData = try sessionManager.initiateHandshake(with: peerID)
        return handshakeData
    }

    func processHandshakeMessage(from peerID: PeerID, message: Data) throws -> Data? {

        guard peerID.isValid else {
            SecureLogger.warning(.authenticationFailed(peerID: peerID.id))
            throw NoiseSecurityError.invalidPeerID
        }

        guard NoiseSecurityValidator.validateHandshakeMessageSize(message) else {
            SecureLogger.warning(.handshakeFailed(peerID: peerID.id, error: "Message too large"))
            throw NoiseSecurityError.messageTooLarge
        }

        guard rateLimiter.allowHandshake(from: peerID) else {
            SecureLogger.warning(.authenticationFailed(peerID: "Rate limited: \(peerID)"))
            throw NoiseSecurityError.rateLimitExceeded
        }

        let responsePayload = try sessionManager.handleIncomingHandshake(from: peerID, message: message)

        return responsePayload
    }

    func hasEstablishedSession(with peerID: PeerID) -> Bool {
        return sessionManager.getSession(for: peerID)?.isEstablished() ?? false
    }

    func hasSession(with peerID: PeerID) -> Bool {
        return sessionManager.getSession(for: peerID) != nil
    }

    func encrypt(_ data: Data, for peerID: PeerID) throws -> Data {

        guard NoiseSecurityValidator.validateMessageSize(data) else {
            throw NoiseSecurityError.messageTooLarge
        }

        guard rateLimiter.allowMessage(from: peerID) else {
            throw NoiseSecurityError.rateLimitExceeded
        }

        guard hasEstablishedSession(with: peerID) else {

            onHandshakeRequired?(peerID)
            throw NoiseEncryptionError.handshakeRequired
        }

        return try sessionManager.encrypt(data, for: peerID)
    }

    func decrypt(_ data: Data, from peerID: PeerID) throws -> Data {

        guard NoiseSecurityValidator.validateMessageSize(data) else {
            throw NoiseSecurityError.messageTooLarge
        }

        guard rateLimiter.allowMessage(from: peerID) else {
            throw NoiseSecurityError.rateLimitExceeded
        }

        guard hasEstablishedSession(with: peerID) else {
            throw NoiseEncryptionError.sessionNotEstablished
        }

        return try sessionManager.decrypt(data, from: peerID)
    }

    func getPeerFingerprint(_ peerID: PeerID) -> String? {
        return serviceQueue.sync {
            return peerFingerprints[peerID]
        }
    }

    func clearEphemeralStateForPanic() {
        sessionManager.removeAllSessions()
        serviceQueue.sync(flags: .barrier) {
            peerFingerprints.removeAll()
            fingerprintToPeerID.removeAll()
        }
        rateLimiter.resetAll()
    }

    func clearSession(for peerID: PeerID) {
        sessionManager.removeSession(for: peerID)
        serviceQueue.sync(flags: .barrier) {
            if let fingerprint = peerFingerprints.removeValue(forKey: peerID) {
                fingerprintToPeerID.removeValue(forKey: fingerprint)
            }
        }
        SecureLogger.debug("Cleared Noise session for \(peerID)", category: .session)
    }

    private func handleSessionEstablished(peerID: PeerID, remoteStaticKey: Curve25519.KeyAgreement.PublicKey) {

        let fingerprint = remoteStaticKey.rawRepresentation.sha256Fingerprint()

        serviceQueue.sync(flags: .barrier) {
            peerFingerprints[peerID] = fingerprint
            fingerprintToPeerID[fingerprint] = peerID
        }

        SecureLogger.info(.handshakeCompleted(peerID: peerID.id))

        serviceQueue.async { [weak self] in
            self?.onPeerAuthenticatedHandlers.forEach { handler in
                handler(peerID, fingerprint)
            }
        }
    }

    private func startRekeyTimer() {
        rekeyTimer = Timer.scheduledTimer(withTimeInterval: rekeyCheckInterval, repeats: true) { [weak self] _ in
            self?.checkSessionsForRekey()
        }
    }

    private func stopRekeyTimer() {
        rekeyTimer?.invalidate()
        rekeyTimer = nil
    }

    private func checkSessionsForRekey() {
        let sessionsNeedingRekey = sessionManager.getSessionsNeedingRekey()

        for (peerID, needsRekey) in sessionsNeedingRekey where needsRekey {

            do {
                try sessionManager.initiateRekey(for: peerID)
                SecureLogger.debug("Key rotation initiated for peer: \(peerID)", category: .security)

                onHandshakeRequired?(peerID)
            } catch {
                SecureLogger.error(error, context: "Failed to initiate rekey for peer: \(peerID)", category: .session)
            }
        }
    }

    deinit {
        stopRekeyTimer()
    }
}

enum NoiseMessageType: UInt8 {
    case handshakeInitiation = 0x10
    case handshakeResponse = 0x11
    case handshakeFinal = 0x12
    case encryptedMessage = 0x13
    case sessionRenegotiation = 0x14
}

struct NoiseMessage: Codable {
    let type: UInt8
    let sessionID: String
    let payload: Data

    init(type: NoiseMessageType, sessionID: String, payload: Data) {
        self.type = type.rawValue
        self.sessionID = sessionID
        self.payload = payload
    }

    func encode() -> Data? {
        do {
            let encoded = try JSONEncoder().encode(self)
            return encoded
        } catch {
            return nil
        }
    }

    static func decode(from data: Data) -> NoiseMessage? {
        return try? JSONDecoder().decode(NoiseMessage.self, from: data)
    }

    static func decodeWithError(from data: Data) -> NoiseMessage? {
        do {
            let decoded = try JSONDecoder().decode(NoiseMessage.self, from: data)
            return decoded
        } catch {
            return nil
        }
    }

    func toBinaryData() -> Data {
        var data = Data()
        data.appendUInt8(type)
        data.appendUUID(sessionID)
        data.appendData(payload)
        return data
    }

    static func fromBinaryData(_ data: Data) -> NoiseMessage? {

        let dataCopy = Data(data)

        var offset = 0

        guard let type = dataCopy.readUInt8(at: &offset),
              let sessionID = dataCopy.readUUID(at: &offset),
              let payload = dataCopy.readData(at: &offset) else { return nil }

        guard let messageType = NoiseMessageType(rawValue: type) else { return nil }

        return NoiseMessage(type: messageType, sessionID: sessionID, payload: payload)
    }
}

enum NoiseEncryptionError: Error {
    case handshakeRequired
    case sessionNotEstablished
}
