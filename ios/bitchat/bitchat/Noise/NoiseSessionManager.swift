import BitLogger
import CryptoKit
import Foundation
import BitFoundation

final class NoiseSessionManager {
    private var sessions: [PeerID: NoiseSession] = [:]
    private let localStaticKey: Curve25519.KeyAgreement.PrivateKey
    private let keychain: KeychainManagerProtocol
    private let sessionFactory: (PeerID, NoiseRole) -> NoiseSession
    private let managerQueue = DispatchQueue(label: "com.cybersiren.ios.noise.manager", attributes: .concurrent)

    var onSessionEstablished: ((PeerID, Curve25519.KeyAgreement.PublicKey) -> Void)?
    var onSessionFailed: ((PeerID, Error) -> Void)?

    init(localStaticKey: Curve25519.KeyAgreement.PrivateKey, keychain: KeychainManagerProtocol) {
        self.localStaticKey = localStaticKey
        self.keychain = keychain
        self.sessionFactory = { peerID, role in
            SecureNoiseSession(
                peerID: peerID,
                role: role,
                keychain: keychain,
                localStaticKey: localStaticKey
            )
        }
    }

    #if DEBUG
    init(
        localStaticKey: Curve25519.KeyAgreement.PrivateKey,
        keychain: KeychainManagerProtocol,
        sessionFactory: @escaping (PeerID, NoiseRole) -> NoiseSession
    ) {
        self.localStaticKey = localStaticKey
        self.keychain = keychain
        self.sessionFactory = sessionFactory
    }
    #endif

    func getSession(for peerID: PeerID) -> NoiseSession? {
        return managerQueue.sync {
            return sessions[peerID]
        }
    }

    func removeSession(for peerID: PeerID) {
        managerQueue.sync(flags: .barrier) {
            if let session = sessions.removeValue(forKey: peerID) {
                session.reset()
            }
        }
    }

    func removeAllSessions() {
        managerQueue.sync(flags: .barrier) {
            for (_, session) in sessions {
                session.reset()
            }
            sessions.removeAll()
        }
    }

    func initiateHandshake(with peerID: PeerID) throws -> Data {
        return try managerQueue.sync(flags: .barrier) {

            if let existingSession = sessions[peerID], existingSession.isEstablished() {

                throw NoiseSessionError.alreadyEstablished
            }

            if let existingSession = sessions[peerID], !existingSession.isEstablished() {
                _ = sessions.removeValue(forKey: peerID)
            }

            let session = sessionFactory(peerID, .initiator)
            sessions[peerID] = session

            do {
                let handshakeData = try session.startHandshake()
                return handshakeData
            } catch {

                _ = sessions.removeValue(forKey: peerID)
                SecureLogger.error(.handshakeFailed(peerID: peerID.id, error: error.localizedDescription))
                throw error
            }
        }
    }

    func handleIncomingHandshake(from peerID: PeerID, message: Data) throws -> Data? {

        return try managerQueue.sync(flags: .barrier) {
            var shouldCreateNew = false
            var existingSession: NoiseSession? = nil

            if let existing = sessions[peerID] {

                if existing.isEstablished() {
                    SecureLogger.info("Accepting handshake from \(peerID) despite existing session - peer likely cleared their session", category: .session)
                    _ = sessions.removeValue(forKey: peerID)
                    shouldCreateNew = true
                } else {

                    if existing.getState() == .handshaking && message.count == 32 {
                        _ = sessions.removeValue(forKey: peerID)
                        shouldCreateNew = true
                    } else {
                        existingSession = existing
                    }
                }
            } else {
                shouldCreateNew = true
            }

            let session: NoiseSession
            if shouldCreateNew {
                let newSession = sessionFactory(peerID, .responder)
                sessions[peerID] = newSession
                session = newSession
            } else {
                session = existingSession!
            }

            do {
                let response = try session.processHandshakeMessage(message)

                if session.isEstablished() {
                    if let remoteKey = session.getRemoteStaticPublicKey() {

                        DispatchQueue.global().async { [weak self] in
                            self?.onSessionEstablished?(peerID, remoteKey)
                        }
                    }
                }

                return response
            } catch {

                _ = sessions.removeValue(forKey: peerID)

                DispatchQueue.global().async { [weak self] in
                    self?.onSessionFailed?(peerID, error)
                }

                SecureLogger.error(.handshakeFailed(peerID: peerID.id, error: error.localizedDescription))
                throw error
            }
        }
    }

    func encrypt(_ plaintext: Data, for peerID: PeerID) throws -> Data {
        guard let session = getSession(for: peerID) else {
            throw NoiseSessionError.sessionNotFound
        }

        return try session.encrypt(plaintext)
    }

    func decrypt(_ ciphertext: Data, from peerID: PeerID) throws -> Data {
        guard let session = getSession(for: peerID) else {
            throw NoiseSessionError.sessionNotFound
        }

        return try session.decrypt(ciphertext)
    }

    func getRemoteStaticKey(for peerID: PeerID) -> Curve25519.KeyAgreement.PublicKey? {
        return getSession(for: peerID)?.getRemoteStaticPublicKey()
    }

    func getSessionsNeedingRekey() -> [(peerID: PeerID, needsRekey: Bool)] {
        return managerQueue.sync {
            var needingRekey: [(peerID: PeerID, needsRekey: Bool)] = []

            for (peerID, session) in sessions {
                if let secureSession = session as? SecureNoiseSession,
                   secureSession.isEstablished(),
                   secureSession.needsRenegotiation() {
                    needingRekey.append((peerID: peerID, needsRekey: true))
                }
            }

            return needingRekey
        }
    }

    func initiateRekey(for peerID: PeerID) throws {

        removeSession(for: peerID)

        _ = try initiateHandshake(with: peerID)
    }
}
