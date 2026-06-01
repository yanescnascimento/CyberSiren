import BitLogger
import Foundation
import CryptoKit
import BitFoundation

class NoiseSession {
    let peerID: PeerID
    let role: NoiseRole
    private let keychain: KeychainManagerProtocol
    private var state: NoiseSessionState = .uninitialized
    private var handshakeState: NoiseHandshakeState?
    private var sendCipher: NoiseCipherState?
    private var receiveCipher: NoiseCipherState?

    private let localStaticKey: Curve25519.KeyAgreement.PrivateKey
    private var remoteStaticPublicKey: Curve25519.KeyAgreement.PublicKey?

    private var sentHandshakeMessages: [Data] = []
    private var handshakeHash: Data?

    private let sessionQueue = DispatchQueue(label: "com.cybersiren.ios.noise.session", attributes: .concurrent)

    init(
        peerID: PeerID,
        role: NoiseRole,
        keychain: KeychainManagerProtocol,
        localStaticKey: Curve25519.KeyAgreement.PrivateKey,
        remoteStaticKey: Curve25519.KeyAgreement.PublicKey? = nil
    ) {
        self.peerID = peerID
        self.role = role
        self.keychain = keychain
        self.localStaticKey = localStaticKey
        self.remoteStaticPublicKey = remoteStaticKey
    }

    func startHandshake() throws -> Data {
        return try sessionQueue.sync(flags: .barrier) {
            guard case .uninitialized = state else {
                throw NoiseSessionError.invalidState
            }

            handshakeState = NoiseHandshakeState(
                role: role,
                pattern: .XX,
                keychain: keychain,
                localStaticKey: localStaticKey,
                remoteStaticKey: nil
            )

            state = .handshaking

            if role == .initiator {
                let message = try handshakeState!.writeMessage()
                sentHandshakeMessages.append(message)
                return message
            } else {

                return Data()
            }
        }
    }

    func processHandshakeMessage(_ message: Data) throws -> Data? {
        return try sessionQueue.sync(flags: .barrier) {
            SecureLogger.debug("NoiseSession[\(peerID)]: Processing handshake message, current state: \(state), role: \(role)")

            if state == .uninitialized && role == .responder {
                handshakeState = NoiseHandshakeState(
                    role: role,
                    pattern: .XX,
                    keychain: keychain,
                    localStaticKey: localStaticKey,
                    remoteStaticKey: nil
                )
                state = .handshaking
                SecureLogger.debug("NoiseSession[\(peerID)]: Initialized handshake state for responder")
            }

            guard case .handshaking = state, let handshake = handshakeState else {
                throw NoiseSessionError.invalidState
            }

            _ = try handshake.readMessage(message)
            SecureLogger.debug("NoiseSession[\(peerID)]: Read handshake message, checking if complete")

            if handshake.isHandshakeComplete() {

                let (send, receive, hash) = try handshake.getTransportCiphers(useExtractedNonce: true)
                sendCipher = send
                receiveCipher = receive

                remoteStaticPublicKey = handshake.getRemoteStaticPublicKey()

                handshakeHash = hash

                state = .established
                handshakeState = nil

                SecureLogger.debug("NoiseSession[\(peerID)]: Handshake complete (no response needed), transitioning to established")
                SecureLogger.info(.handshakeCompleted(peerID: peerID.id))

                return nil
            } else {

                let response = try handshake.writeMessage()
                sentHandshakeMessages.append(response)
                SecureLogger.debug("NoiseSession[\(peerID)]: Generated handshake response of size \(response.count)")

                if handshake.isHandshakeComplete() {

                    let (send, receive, hash) = try handshake.getTransportCiphers(useExtractedNonce: true)
                    sendCipher = send
                    receiveCipher = receive

                    remoteStaticPublicKey = handshake.getRemoteStaticPublicKey()

                    handshakeHash = hash

                    state = .established
                    handshakeState = nil

                    SecureLogger.debug("NoiseSession[\(peerID)]: Handshake complete after writing response, transitioning to established")
                    SecureLogger.info(.handshakeCompleted(peerID: peerID.id))
                }

                return response
            }
        }
    }

    func encrypt(_ plaintext: Data) throws -> Data {
        return try sessionQueue.sync(flags: .barrier) {
            guard case .established = state, let cipher = sendCipher else {
                throw NoiseSessionError.notEstablished
            }

            return try cipher.encrypt(plaintext: plaintext)
        }
    }

    func decrypt(_ ciphertext: Data) throws -> Data {
        return try sessionQueue.sync(flags: .barrier) {
            guard case .established = state, let cipher = receiveCipher else {
                throw NoiseSessionError.notEstablished
            }

            return try cipher.decrypt(ciphertext: ciphertext)
        }
    }

    func getState() -> NoiseSessionState {
        return sessionQueue.sync {
            return state
        }
    }

    func isEstablished() -> Bool {
        return sessionQueue.sync {
            if case .established = state {
                return true
            }
            return false
        }
    }

    func getRemoteStaticPublicKey() -> Curve25519.KeyAgreement.PublicKey? {
        return sessionQueue.sync {
            return remoteStaticPublicKey
        }
    }

    func reset() {
        sessionQueue.sync(flags: .barrier) {
            let wasEstablished = state == .established
            state = .uninitialized
            handshakeState = nil

            sendCipher?.clearSensitiveData()
            receiveCipher?.clearSensitiveData()
            sendCipher = nil
            receiveCipher = nil

            for i in 0..<sentHandshakeMessages.count {
                var message = sentHandshakeMessages[i]
                keychain.secureClear(&message)
            }
            sentHandshakeMessages.removeAll()

            if var hash = handshakeHash {
                keychain.secureClear(&hash)
            }
            handshakeHash = nil

            if wasEstablished {
                SecureLogger.info(.sessionExpired(peerID: peerID.id))
            }
        }
    }
}
