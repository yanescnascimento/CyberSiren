import BitLogger
import BitFoundation
import Foundation
import CryptoKit

enum NoisePattern {
    case XX
    case IK
    case NK
}

enum NoiseRole {
    case initiator
    case responder
}

enum NoiseMessagePattern {
    case e
    case s
    case ee
    case es
    case se
    case ss
}

struct NoiseProtocolName {
    let pattern: String
    let dh: String = "25519"
    let cipher: String = "ChaChaPoly"
    let hash: String = "SHA256"

    var fullName: String {
        "Noise_\(pattern)_\(dh)_\(cipher)_\(hash)"
    }
}

final class NoiseCipherState {

    private static let NONCE_SIZE_BYTES = 4
    private static let REPLAY_WINDOW_SIZE = 1024
    private static let REPLAY_WINDOW_BYTES = REPLAY_WINDOW_SIZE / 8
    private static let HIGH_NONCE_WARNING_THRESHOLD: UInt64 = 1_000_000_000

    private var key: SymmetricKey?
    private var nonce: UInt64 = 0
    private var useExtractedNonce: Bool = false

    private var highestReceivedNonce: UInt64 = 0
    private var replayWindow: [UInt8] = Array(repeating: 0, count: REPLAY_WINDOW_BYTES)

    init() {}

    init(key: SymmetricKey, useExtractedNonce: Bool = false) {
        self.key = key
        self.useExtractedNonce = useExtractedNonce
    }

    deinit {
        clearSensitiveData()
    }

    func initializeKey(_ key: SymmetricKey) {
        self.key = key
        self.nonce = 0
    }

    func hasKey() -> Bool {
        return key != nil
    }

    private func isValidNonce(_ receivedNonce: UInt64) -> Bool {

        let windowSize = UInt64(Self.REPLAY_WINDOW_SIZE)
        if highestReceivedNonce >= windowSize && receivedNonce <= highestReceivedNonce - windowSize {
            return false
        }

        if receivedNonce > highestReceivedNonce {
            return true
        }

        let offset = Int(highestReceivedNonce - receivedNonce)
        let byteIndex = offset / 8
        let bitIndex = offset % 8

        return (replayWindow[byteIndex] & (1 << bitIndex)) == 0
    }

    private func markNonceAsSeen(_ receivedNonce: UInt64) {
        if receivedNonce > highestReceivedNonce {
            let shift = Int(receivedNonce - highestReceivedNonce)

            if shift >= Self.REPLAY_WINDOW_SIZE {

                replayWindow = Array(repeating: 0, count: Self.REPLAY_WINDOW_BYTES)
            } else {

                for i in stride(from: Self.REPLAY_WINDOW_BYTES - 1, through: 0, by: -1) {
                    let sourceByteIndex = i - shift / 8
                    var newByte: UInt8 = 0

                    if sourceByteIndex >= 0 {
                        newByte = replayWindow[sourceByteIndex] >> (shift % 8)
                        if sourceByteIndex > 0 && shift % 8 != 0 {
                            newByte |= replayWindow[sourceByteIndex - 1] << (8 - shift % 8)
                        }
                    }

                    replayWindow[i] = newByte
                }
            }

            highestReceivedNonce = receivedNonce
            replayWindow[0] |= 1
        } else {
            let offset = Int(highestReceivedNonce - receivedNonce)
            let byteIndex = offset / 8
            let bitIndex = offset % 8
            replayWindow[byteIndex] |= (1 << bitIndex)
        }
    }

    private func extractNonceFromCiphertextPayload(_ combinedPayload: Data) throws -> (nonce: UInt64, ciphertext: Data)? {
        guard combinedPayload.count >= Self.NONCE_SIZE_BYTES else {
            return nil
        }

        let nonceData = combinedPayload.prefix(Self.NONCE_SIZE_BYTES)
        let extractedNonce = nonceData.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) -> UInt64 in
            let byteArray = bytes.bindMemory(to: UInt8.self)
            var result: UInt64 = 0
            for i in 0..<Self.NONCE_SIZE_BYTES {
                result = (result << 8) | UInt64(byteArray[i])
            }
            return result
        }

        let ciphertext = combinedPayload.dropFirst(Self.NONCE_SIZE_BYTES)

        return (nonce: extractedNonce, ciphertext: Data(ciphertext))
    }

    private func nonceToBytes(_ nonce: UInt64) -> Data {
        var bytes = Data(count: Self.NONCE_SIZE_BYTES)
        withUnsafeBytes(of: nonce.bigEndian) { ptr in

            let sourceBytes = ptr.bindMemory(to: UInt8.self)
            bytes.replaceSubrange(0..<Self.NONCE_SIZE_BYTES, with: sourceBytes.suffix(Self.NONCE_SIZE_BYTES))
        }
        return bytes
    }

    func encrypt(plaintext: Data, associatedData: Data = Data()) throws -> Data {
        guard let key = self.key else {
            throw NoiseError.uninitializedCipher
        }

        let currentNonce = nonce

        guard nonce <= UInt64(UInt32.max) - 1 else {
            throw NoiseError.nonceExceeded
        }

        var nonceData = Data(count: 12)
        withUnsafeBytes(of: currentNonce.littleEndian) { bytes in
            nonceData.replaceSubrange(4..<12, with: bytes)
        }

        let sealedBox = try ChaChaPoly.seal(plaintext, using: key, nonce: ChaChaPoly.Nonce(data: nonceData), authenticating: associatedData)

        nonce += 1

        let combinedPayload: Data
        if (useExtractedNonce) {
            let nonceBytes = nonceToBytes(currentNonce)
            combinedPayload = nonceBytes + sealedBox.ciphertext + sealedBox.tag
        } else {
            combinedPayload = sealedBox.ciphertext + sealedBox.tag
        }

        if currentNonce > Self.HIGH_NONCE_WARNING_THRESHOLD {
            SecureLogger.warning("High nonce value detected: \(currentNonce) - consider rekeying", category: .encryption)
        }

        return combinedPayload
    }

    func decrypt(ciphertext: Data, associatedData: Data = Data()) throws -> Data {
        guard let key = self.key else {
            throw NoiseError.uninitializedCipher
        }

        guard ciphertext.count >= 16 else {
            throw NoiseError.invalidCiphertext
        }

        let encryptedData: Data
        let tag: Data
        let decryptionNonce: UInt64

        if useExtractedNonce {

            guard let (extractedNonce, actualCiphertext) = try extractNonceFromCiphertextPayload(ciphertext) else {
                SecureLogger.debug("Decrypt failed: Could not extract nonce from payload")
                throw NoiseError.invalidCiphertext
            }

            guard isValidNonce(extractedNonce) else {
                SecureLogger.debug("Replay attack detected: nonce \(extractedNonce) rejected")
                throw NoiseError.replayDetected
            }

            encryptedData = actualCiphertext.prefix(actualCiphertext.count - 16)
            tag = actualCiphertext.suffix(16)
            decryptionNonce = extractedNonce
        } else {

            encryptedData = ciphertext.prefix(ciphertext.count - 16)
            tag = ciphertext.suffix(16)
            decryptionNonce = nonce
        }

        var nonceData = Data(count: 12)
        withUnsafeBytes(of: decryptionNonce.littleEndian) { bytes in
            nonceData.replaceSubrange(4..<12, with: bytes)
        }

        let sealedBox = try ChaChaPoly.SealedBox(
            nonce: ChaChaPoly.Nonce(data: nonceData),
            ciphertext: encryptedData,
            tag: tag
        )

        if decryptionNonce > Self.HIGH_NONCE_WARNING_THRESHOLD {
            SecureLogger.warning("High nonce value detected: \(decryptionNonce) - consider rekeying", category: .encryption)
        }

        do {
            let plaintext = try ChaChaPoly.open(sealedBox, using: key, authenticating: associatedData)

            if useExtractedNonce {
                markNonceAsSeen(decryptionNonce)
            }
            nonce += 1

            return plaintext
        } catch {

            SecureLogger.debug("Decrypt failed: \(error) for nonce \(decryptionNonce)")
            SecureLogger.error("Decryption failed at nonce \(decryptionNonce)", category: .encryption)
            throw error
        }
    }

    func clearSensitiveData() {

        key = nil

        nonce = 0
        highestReceivedNonce = 0

        for i in 0..<replayWindow.count {
            replayWindow[i] = 0
        }
    }

    #if DEBUG
    func setNonceForTesting(_ nonce: UInt64) {
        self.nonce = nonce
    }

    func extractNonceFromCiphertextPayloadForTesting(_ combinedPayload: Data) throws -> (nonce: UInt64, ciphertext: Data)? {
        try extractNonceFromCiphertextPayload(combinedPayload)
    }
    #endif
}

final class NoiseSymmetricState {
    private var cipherState: NoiseCipherState
    private var chainingKey: Data
    private var hash: Data

    init(protocolName: String) {
        self.cipherState = NoiseCipherState()

        let nameData = protocolName.data(using: .utf8)!
        if nameData.count <= 32 {
            self.hash = nameData + Data(repeating: 0, count: 32 - nameData.count)
        } else {
            self.hash = nameData.sha256Hash()
        }
        self.chainingKey = self.hash
    }

    func mixKey(_ inputKeyMaterial: Data) {
        let output = hkdf(chainingKey: chainingKey, inputKeyMaterial: inputKeyMaterial, numOutputs: 2)
        chainingKey = output[0]
        let tempKey = SymmetricKey(data: output[1])
        cipherState.initializeKey(tempKey)
    }

    func mixHash(_ data: Data) {
        hash = (hash + data).sha256Hash()
    }

    func mixKeyAndHash(_ inputKeyMaterial: Data) {
        let output = hkdf(chainingKey: chainingKey, inputKeyMaterial: inputKeyMaterial, numOutputs: 3)
        chainingKey = output[0]
        mixHash(output[1])
        let tempKey = SymmetricKey(data: output[2])
        cipherState.initializeKey(tempKey)
    }

    func getHandshakeHash() -> Data {
        return hash
    }

    func hasCipherKey() -> Bool {
        return cipherState.hasKey()
    }

    func encryptAndHash(_ plaintext: Data) throws -> Data {
        if cipherState.hasKey() {
            let ciphertext = try cipherState.encrypt(plaintext: plaintext, associatedData: hash)
            mixHash(ciphertext)
            return ciphertext
        } else {
            mixHash(plaintext)
            return plaintext
        }
    }

    func decryptAndHash(_ ciphertext: Data) throws -> Data {
        if cipherState.hasKey() {
            let plaintext = try cipherState.decrypt(ciphertext: ciphertext, associatedData: hash)
            mixHash(ciphertext)
            return plaintext
        } else {
            mixHash(ciphertext)
            return ciphertext
        }
    }

    func split(useExtractedNonce: Bool) -> (NoiseCipherState, NoiseCipherState) {
        let output = hkdf(chainingKey: chainingKey, inputKeyMaterial: Data(), numOutputs: 2)
        let tempKey1 = SymmetricKey(data: output[0])
        let tempKey2 = SymmetricKey(data: output[1])

        let c1 = NoiseCipherState(key: tempKey1, useExtractedNonce: useExtractedNonce)
        let c2 = NoiseCipherState(key: tempKey2, useExtractedNonce: useExtractedNonce)

        clearSensitiveData()

        return (c1, c2)
    }

    func clearSensitiveData() {

        let chainingKeyCount = chainingKey.count
        chainingKey = Data(repeating: 0, count: chainingKeyCount)

        let hashCount = hash.count
        hash = Data(repeating: 0, count: hashCount)

        cipherState.clearSensitiveData()
    }

    deinit {
        clearSensitiveData()
    }

    private func hkdf(chainingKey: Data, inputKeyMaterial: Data, numOutputs: Int) -> [Data] {
        let tempKey = HMAC<SHA256>.authenticationCode(for: inputKeyMaterial, using: SymmetricKey(data: chainingKey))
        let tempKeyData = Data(tempKey)

        var outputs: [Data] = []
        var currentOutput = Data()

        for i in 1...numOutputs {
            currentOutput = Data(HMAC<SHA256>.authenticationCode(
                for: currentOutput + Data([UInt8(i)]),
                using: SymmetricKey(data: tempKeyData)
            ))
            outputs.append(currentOutput)
        }

        return outputs
    }
}

final class NoiseHandshakeState {
    private let role: NoiseRole
    private let pattern: NoisePattern
    private let keychain: KeychainManagerProtocol
    private var symmetricState: NoiseSymmetricState

    private var localStaticPrivate: Curve25519.KeyAgreement.PrivateKey?
    private var localStaticPublic: Curve25519.KeyAgreement.PublicKey?
    private var localEphemeralPrivate: Curve25519.KeyAgreement.PrivateKey?
    private var localEphemeralPublic: Curve25519.KeyAgreement.PublicKey?

    private var remoteStaticPublic: Curve25519.KeyAgreement.PublicKey?
    private var remoteEphemeralPublic: Curve25519.KeyAgreement.PublicKey?

    private var messagePatterns: [[NoiseMessagePattern]] = []
    private var currentPattern = 0

    private var predeterminedEphemeralKey: Curve25519.KeyAgreement.PrivateKey?
    private var prologueData: Data

    init(
        role: NoiseRole,
        pattern: NoisePattern,
        keychain: KeychainManagerProtocol,
        localStaticKey: Curve25519.KeyAgreement.PrivateKey? = nil,
        remoteStaticKey: Curve25519.KeyAgreement.PublicKey? = nil,
        prologue: Data = Data(),
        predeterminedEphemeralKey: Curve25519.KeyAgreement.PrivateKey? = nil
    ) {
        self.role = role
        self.pattern = pattern
        self.keychain = keychain
        self.prologueData = prologue
        self.predeterminedEphemeralKey = predeterminedEphemeralKey

        if let localKey = localStaticKey {
            self.localStaticPrivate = localKey
            self.localStaticPublic = localKey.publicKey
        }
        self.remoteStaticPublic = remoteStaticKey

        let protocolName = NoiseProtocolName(pattern: pattern.patternName)
        self.symmetricState = NoiseSymmetricState(protocolName: protocolName.fullName)

        self.messagePatterns = pattern.messagePatterns

        mixPreMessageKeys()
    }

    private func mixPreMessageKeys() {

        symmetricState.mixHash(self.prologueData)

        switch pattern {
        case .XX:
            break
        case .IK, .NK:
            if role == .initiator, let remoteStatic = remoteStaticPublic {
                symmetricState.mixHash(remoteStatic.rawRepresentation)
            } else if role == .responder, let localStatic = localStaticPublic {
                symmetricState.mixHash(localStatic.rawRepresentation)
            }
        }
    }

    func writeMessage(payload: Data = Data()) throws -> Data {
        guard currentPattern < messagePatterns.count else {
            throw NoiseError.handshakeComplete
        }

        var messageBuffer = Data()
        let patterns = messagePatterns[currentPattern]

        for pattern in patterns {
            switch pattern {
            case .e:

                if let predetermined = predeterminedEphemeralKey {
                    localEphemeralPrivate = predetermined
                    predeterminedEphemeralKey = nil
                } else {
                    localEphemeralPrivate = Curve25519.KeyAgreement.PrivateKey()
                }
                localEphemeralPublic = localEphemeralPrivate!.publicKey
                messageBuffer.append(localEphemeralPublic!.rawRepresentation)
                symmetricState.mixHash(localEphemeralPublic!.rawRepresentation)

            case .s:

                guard let staticPublic = localStaticPublic else {
                    throw NoiseError.missingLocalStaticKey
                }
                let encrypted = try symmetricState.encryptAndHash(staticPublic.rawRepresentation)
                messageBuffer.append(encrypted)

            case .ee:

                guard let localEphemeral = localEphemeralPrivate,
                      let remoteEphemeral = remoteEphemeralPublic else {
                    throw NoiseError.missingKeys
                }
                let shared = try localEphemeral.sharedSecretFromKeyAgreement(with: remoteEphemeral)
                var sharedData = shared.withUnsafeBytes { Data($0) }
                symmetricState.mixKey(sharedData)

                keychain.secureClear(&sharedData)

            case .es:

                if role == .initiator {
                    guard let localEphemeral = localEphemeralPrivate,
                          let remoteStatic = remoteStaticPublic else {
                        throw NoiseError.missingKeys
                    }
                    let shared = try localEphemeral.sharedSecretFromKeyAgreement(with: remoteStatic)
                    var sharedData = shared.withUnsafeBytes { Data($0) }
                    symmetricState.mixKey(sharedData)

                    keychain.secureClear(&sharedData)
                } else {
                    guard let localStatic = localStaticPrivate,
                          let remoteEphemeral = remoteEphemeralPublic else {
                        throw NoiseError.missingKeys
                    }
                    let shared = try localStatic.sharedSecretFromKeyAgreement(with: remoteEphemeral)
                    var sharedData = shared.withUnsafeBytes { Data($0) }
                    symmetricState.mixKey(sharedData)

                    keychain.secureClear(&sharedData)
                }

            case .se:

                if role == .initiator {
                    guard let localStatic = localStaticPrivate,
                          let remoteEphemeral = remoteEphemeralPublic else {
                        throw NoiseError.missingKeys
                    }
                    let shared = try localStatic.sharedSecretFromKeyAgreement(with: remoteEphemeral)
                    var sharedData = shared.withUnsafeBytes { Data($0) }
                    symmetricState.mixKey(sharedData)

                    keychain.secureClear(&sharedData)
                } else {
                    guard let localEphemeral = localEphemeralPrivate,
                          let remoteStatic = remoteStaticPublic else {
                        throw NoiseError.missingKeys
                    }
                    let shared = try localEphemeral.sharedSecretFromKeyAgreement(with: remoteStatic)
                    var sharedData = shared.withUnsafeBytes { Data($0) }
                    symmetricState.mixKey(sharedData)

                    keychain.secureClear(&sharedData)
                }

            case .ss:

                guard let localStatic = localStaticPrivate,
                      let remoteStatic = remoteStaticPublic else {
                    throw NoiseError.missingKeys
                }
                let shared = try localStatic.sharedSecretFromKeyAgreement(with: remoteStatic)
                var sharedData = shared.withUnsafeBytes { Data($0) }
                symmetricState.mixKey(sharedData)

                keychain.secureClear(&sharedData)
            }
        }

        let encryptedPayload = try symmetricState.encryptAndHash(payload)
        messageBuffer.append(encryptedPayload)

        currentPattern += 1
        return messageBuffer
    }

    func readMessage(_ message: Data, expectedPayloadLength: Int = 0) throws -> Data {

        guard currentPattern < messagePatterns.count else {
            throw NoiseError.handshakeComplete
        }

        var buffer = message
        let patterns = messagePatterns[currentPattern]

        for pattern in patterns {
            switch pattern {
            case .e:

                guard buffer.count >= 32 else {
                    throw NoiseError.invalidMessage
                }
                let ephemeralData = buffer.prefix(32)
                buffer = buffer.dropFirst(32)

                do {
                    remoteEphemeralPublic = try NoiseHandshakeState.validatePublicKey(ephemeralData)
                } catch {
                    SecureLogger.warning("Invalid ephemeral public key received", category: .security)
                    throw NoiseError.invalidMessage
                }
                symmetricState.mixHash(ephemeralData)

            case .s:

                let keyLength = symmetricState.hasCipherKey() ? 48 : 32
                guard buffer.count >= keyLength else {
                    throw NoiseError.invalidMessage
                }
                let staticData = buffer.prefix(keyLength)
                buffer = buffer.dropFirst(keyLength)
                do {
                    let decrypted = try symmetricState.decryptAndHash(staticData)
                    remoteStaticPublic = try NoiseHandshakeState.validatePublicKey(decrypted)
                } catch {
                    SecureLogger.error(.authenticationFailed(peerID: "Unknown - handshake"))
                    throw NoiseError.authenticationFailure
                }

            case .ee, .es, .se, .ss:

                try performDHOperation(pattern)
            }
        }

        let payload = try symmetricState.decryptAndHash(buffer)
        currentPattern += 1

        return payload
    }

    private func performDHOperation(_ pattern: NoiseMessagePattern) throws {
        switch pattern {
        case .ee:
            guard let localEphemeral = localEphemeralPrivate,
                  let remoteEphemeral = remoteEphemeralPublic else {
                throw NoiseError.missingKeys
            }
            let shared = try localEphemeral.sharedSecretFromKeyAgreement(with: remoteEphemeral)
            var sharedData = shared.withUnsafeBytes { Data($0) }
            symmetricState.mixKey(sharedData)

            keychain.secureClear(&sharedData)

        case .es:
            if role == .initiator {
                guard let localEphemeral = localEphemeralPrivate,
                      let remoteStatic = remoteStaticPublic else {
                    throw NoiseError.missingKeys
                }
                let shared = try localEphemeral.sharedSecretFromKeyAgreement(with: remoteStatic)
                var sharedData = shared.withUnsafeBytes { Data($0) }
                symmetricState.mixKey(sharedData)

                keychain.secureClear(&sharedData)
            } else {
                guard let localStatic = localStaticPrivate,
                      let remoteEphemeral = remoteEphemeralPublic else {
                    throw NoiseError.missingKeys
                }
                let shared = try localStatic.sharedSecretFromKeyAgreement(with: remoteEphemeral)
                var sharedData = shared.withUnsafeBytes { Data($0) }
                symmetricState.mixKey(sharedData)

                keychain.secureClear(&sharedData)
            }

        case .se:
            if role == .initiator {
                guard let localStatic = localStaticPrivate,
                      let remoteEphemeral = remoteEphemeralPublic else {
                    throw NoiseError.missingKeys
                }
                let shared = try localStatic.sharedSecretFromKeyAgreement(with: remoteEphemeral)
                var sharedData = shared.withUnsafeBytes { Data($0) }
                symmetricState.mixKey(sharedData)

                keychain.secureClear(&sharedData)
            } else {
                guard let localEphemeral = localEphemeralPrivate,
                      let remoteStatic = remoteStaticPublic else {
                    throw NoiseError.missingKeys
                }
                let shared = try localEphemeral.sharedSecretFromKeyAgreement(with: remoteStatic)
                var sharedData = shared.withUnsafeBytes { Data($0) }
                symmetricState.mixKey(sharedData)

                keychain.secureClear(&sharedData)
            }

        case .ss:
            guard let localStatic = localStaticPrivate,
                  let remoteStatic = remoteStaticPublic else {
                throw NoiseError.missingKeys
            }
            let shared = try localStatic.sharedSecretFromKeyAgreement(with: remoteStatic)
            var sharedData = shared.withUnsafeBytes { Data($0) }
            symmetricState.mixKey(sharedData)

            keychain.secureClear(&sharedData)

        case .e, .s:
            break
        }
    }

    func isHandshakeComplete() -> Bool {
        return currentPattern >= messagePatterns.count
    }

    func getTransportCiphers(useExtractedNonce: Bool) throws -> (send: NoiseCipherState, receive: NoiseCipherState, handshakeHash: Data) {
        guard isHandshakeComplete() else {
            throw NoiseError.handshakeNotComplete
        }

        let finalHandshakeHash = symmetricState.getHandshakeHash()

        let (c1, c2) = symmetricState.split(useExtractedNonce: useExtractedNonce)

        let ciphers = role == .initiator ? (c1, c2) : (c2, c1)
        return (send: ciphers.0, receive: ciphers.1, handshakeHash: finalHandshakeHash)
    }

    func getRemoteStaticPublicKey() -> Curve25519.KeyAgreement.PublicKey? {
        return remoteStaticPublic
    }

    func getHandshakeHash() -> Data {
        return symmetricState.getHandshakeHash()
    }

    #if DEBUG
    func performDHOperationForTesting(_ pattern: NoiseMessagePattern) throws {
        try performDHOperation(pattern)
    }

    func setCurrentPatternForTesting(_ currentPattern: Int) {
        self.currentPattern = currentPattern
    }

    func setRemoteEphemeralPublicKeyForTesting(_ key: Curve25519.KeyAgreement.PublicKey?) {
        self.remoteEphemeralPublic = key
    }
    #endif
}

extension NoisePattern {
    var patternName: String {
        switch self {
        case .XX: return "XX"
        case .IK: return "IK"
        case .NK: return "NK"
        }
    }

    var messagePatterns: [[NoiseMessagePattern]] {
        switch self {
        case .XX:
            return [
                [.e],
                [.e, .ee, .s, .es],
                [.s, .se]
            ]
        case .IK:
            return [
                [.e, .es, .s, .ss],
                [.e, .ee, .se]
            ]
        case .NK:
            return [
                [.e, .es],
                [.e, .ee]
            ]
        }
    }
}

enum NoiseError: Error {
    case uninitializedCipher
    case invalidCiphertext
    case handshakeComplete
    case handshakeNotComplete
    case missingLocalStaticKey
    case missingKeys
    case invalidMessage
    case authenticationFailure
    case invalidPublicKey
    case replayDetected
    case nonceExceeded
}

private func constantTimeCompare(_ a: Data, _ b: Data) -> Bool {
    guard a.count == b.count else { return false }

    var result: UInt8 = 0
    for i in 0..<a.count {
        result |= a[a.startIndex.advanced(by: i)] ^ b[b.startIndex.advanced(by: i)]
    }
    return result == 0
}

private func constantTimeIsZero(_ data: Data) -> Bool {
    var result: UInt8 = 0
    for byte in data {
        result |= byte
    }
    return result == 0
}

extension NoiseHandshakeState {

    static func validatePublicKey(_ keyData: Data) throws -> Curve25519.KeyAgreement.PublicKey {

        guard keyData.count == 32 else {
            throw NoiseError.invalidPublicKey
        }

        if constantTimeIsZero(keyData) {
            throw NoiseError.invalidPublicKey
        }

        let lowOrderPoints: [Data] = [
            Data(repeating: 0x00, count: 32),
            Data([0x01] + Data(repeating: 0x00, count: 31)),
            Data([0x00] + Data(repeating: 0x00, count: 30) + [0x01]),
            Data([0xe0, 0xeb, 0x7a, 0x7c, 0x3b, 0x41, 0xb8, 0xae, 0x16, 0x56, 0xe3,
                  0xfa, 0xf1, 0x9f, 0xc4, 0x6a, 0xda, 0x09, 0x8d, 0xeb, 0x9c, 0x32,
                  0xb1, 0xfd, 0x86, 0x62, 0x05, 0x16, 0x5f, 0x49, 0xb8, 0x00]),
            Data([0x5f, 0x9c, 0x95, 0xbc, 0xa3, 0x50, 0x8c, 0x24, 0xb1, 0xd0, 0xb1,
                  0x55, 0x9c, 0x83, 0xef, 0x5b, 0x04, 0x44, 0x5c, 0xc4, 0x58, 0x1c,
                  0x8e, 0x86, 0xd8, 0x22, 0x4e, 0xdd, 0xd0, 0x9f, 0x11, 0x57]),
            Data(repeating: 0xFF, count: 32),
            Data([0xda, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
                  0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
                  0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]),
            Data([0xdb, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
                  0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
                  0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff])
        ]

        var foundBadPoint = false
        for badPoint in lowOrderPoints {
            if constantTimeCompare(keyData, badPoint) {
                foundBadPoint = true
            }
        }

        if foundBadPoint {
            SecureLogger.warning("Low-order point detected", category: .security)
            throw NoiseError.invalidPublicKey
        }

        do {
            let publicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: keyData)
            return publicKey
        } catch {

            SecureLogger.warning("CryptoKit validation failed", category: .security)
            throw NoiseError.invalidPublicKey
        }
    }
}
