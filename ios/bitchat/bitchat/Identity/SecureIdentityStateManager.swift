import BitLogger
import BitFoundation
import Foundation
import CryptoKit

protocol SecureIdentityStateManagerProtocol {

    func forceSave()

    func getSocialIdentity(for fingerprint: String) -> SocialIdentity?

    func upsertCryptographicIdentity(fingerprint: String, noisePublicKey: Data, signingPublicKey: Data?, claimedNickname: String?)
    func getCryptoIdentitiesByPeerIDPrefix(_ peerID: PeerID) -> [CryptographicIdentity]
    func updateSocialIdentity(_ identity: SocialIdentity)

    func getFavorites() -> Set<String>
    func setFavorite(_ fingerprint: String, isFavorite: Bool)
    func isFavorite(fingerprint: String) -> Bool

    func isBlocked(fingerprint: String) -> Bool
    func setBlocked(_ fingerprint: String, isBlocked: Bool)

    func isNostrBlocked(pubkeyHexLowercased: String) -> Bool
    func setNostrBlocked(_ pubkeyHexLowercased: String, isBlocked: Bool)
    func getBlockedNostrPubkeys() -> Set<String>

    func registerEphemeralSession(peerID: PeerID, handshakeState: HandshakeState)
    func updateHandshakeState(peerID: PeerID, state: HandshakeState)

    func clearAllIdentityData()
    func removeEphemeralSession(peerID: PeerID)

    func setVerified(fingerprint: String, verified: Bool)
    func isVerified(fingerprint: String) -> Bool
    func getVerifiedFingerprints() -> Set<String>
}

final class SecureIdentityStateManager: SecureIdentityStateManagerProtocol {
    private let keychain: KeychainManagerProtocol
    private let cacheKey = "bitchat.identityCache.v2"
    private let encryptionKeyName = "identityCacheEncryptionKey"

    private var ephemeralSessions: [PeerID: EphemeralIdentity] = [:]
    private var cryptographicIdentities: [String: CryptographicIdentity] = [:]
    private var cache: IdentityCache = IdentityCache()

    private let queue = DispatchQueue(label: "bitchat.identity.state", attributes: .concurrent)

    private var saveTimer: Timer?
    private let saveDebounceInterval: TimeInterval = 2.0
    private var pendingSave = false

    private let encryptionKey: SymmetricKey

    init(_ keychain: KeychainManagerProtocol) {
        self.keychain = keychain

        let loadedKey: SymmetricKey

        if let keyData = keychain.getIdentityKey(forKey: encryptionKeyName) {
            loadedKey = SymmetricKey(data: keyData)
            SecureLogger.logKeyOperation(.load, keyType: "identity cache encryption key", success: true)
        }

        else {
            loadedKey = SymmetricKey(size: .bits256)
            let keyData = loadedKey.withUnsafeBytes { Data($0) }

            let saved = keychain.saveIdentityKey(keyData, forKey: encryptionKeyName)
            SecureLogger.logKeyOperation(.generate, keyType: "identity cache encryption key", success: saved)
        }

        self.encryptionKey = loadedKey

        loadIdentityCache()
    }

    deinit {
        forceSave()
    }

    private func loadIdentityCache() {
        guard let encryptedData = keychain.getIdentityKey(forKey: cacheKey) else {

            return
        }

        do {
            let sealedBox = try AES.GCM.SealedBox(combined: encryptedData)
            let decryptedData = try AES.GCM.open(sealedBox, using: encryptionKey)
            cache = try JSONDecoder().decode(IdentityCache.self, from: decryptedData)
        } catch {

            SecureLogger.error(error, context: "Failed to load identity cache", category: .security)
        }
    }

    private func saveIdentityCache() {

        pendingSave = true

        saveTimer?.invalidate()

        saveTimer = Timer.scheduledTimer(withTimeInterval: saveDebounceInterval, repeats: false) { [weak self] _ in
            self?.performSave()
        }
    }

    private func performSave() {
        guard pendingSave else { return }
        pendingSave = false

        do {
            let data = try JSONEncoder().encode(cache)
            let sealedBox = try AES.GCM.seal(data, using: encryptionKey)
            let saved = keychain.saveIdentityKey(sealedBox.combined!, forKey: cacheKey)
            if saved {
                SecureLogger.debug("Identity cache saved to keychain", category: .security)
            }
        } catch {
            SecureLogger.error(error, context: "Failed to save identity cache", category: .security)
        }
    }

    func forceSave() {
        saveTimer?.invalidate()
        performSave()
    }

    func getSocialIdentity(for fingerprint: String) -> SocialIdentity? {
        queue.sync {
            return cache.socialIdentities[fingerprint]
        }
    }

    func upsertCryptographicIdentity(fingerprint: String, noisePublicKey: Data, signingPublicKey: Data?, claimedNickname: String? = nil) {
        queue.async(flags: .barrier) {
            let now = Date()
            if var existing = self.cryptographicIdentities[fingerprint] {

                if existing.publicKey != noisePublicKey {
                    existing = CryptographicIdentity(
                        fingerprint: fingerprint,
                        publicKey: noisePublicKey,
                        signingPublicKey: signingPublicKey ?? existing.signingPublicKey,
                        firstSeen: existing.firstSeen,
                        lastHandshake: now
                    )
                    self.cryptographicIdentities[fingerprint] = existing
                } else {

                    existing.signingPublicKey = signingPublicKey ?? existing.signingPublicKey
                    let updated = CryptographicIdentity(
                        fingerprint: existing.fingerprint,
                        publicKey: existing.publicKey,
                        signingPublicKey: existing.signingPublicKey,
                        firstSeen: existing.firstSeen,
                        lastHandshake: now
                    )
                    self.cryptographicIdentities[fingerprint] = updated
                }

            } else {

                let entry = CryptographicIdentity(
                    fingerprint: fingerprint,
                    publicKey: noisePublicKey,
                    signingPublicKey: signingPublicKey,
                    firstSeen: now,
                    lastHandshake: now
                )
                self.cryptographicIdentities[fingerprint] = entry
            }

            if let claimed = claimedNickname {
                var identity = self.cache.socialIdentities[fingerprint] ?? SocialIdentity(
                    fingerprint: fingerprint,
                    localPetname: nil,
                    claimedNickname: claimed,
                    trustLevel: .unknown,
                    isFavorite: false,
                    isBlocked: false,
                    notes: nil
                )

                if identity.claimedNickname != claimed {
                    identity.claimedNickname = claimed
                    self.cache.socialIdentities[fingerprint] = identity
                } else if self.cache.socialIdentities[fingerprint] == nil {
                    self.cache.socialIdentities[fingerprint] = identity
                }
            }

            self.saveIdentityCache()
        }
    }

    func getCryptoIdentitiesByPeerIDPrefix(_ peerID: PeerID) -> [CryptographicIdentity] {
        queue.sync {

            guard peerID.isShort else { return [] }
            return cryptographicIdentities.values.filter { $0.fingerprint.hasPrefix(peerID.id) }
        }
    }

    func updateSocialIdentity(_ identity: SocialIdentity) {
        queue.async(flags: .barrier) {
            let previousClaimedNickname = self.cache.socialIdentities[identity.fingerprint]?.claimedNickname
            self.cache.socialIdentities[identity.fingerprint] = identity

            if let previousClaimedNickname,
               previousClaimedNickname != identity.claimedNickname {
                self.cache.nicknameIndex[previousClaimedNickname]?.remove(identity.fingerprint)
                if self.cache.nicknameIndex[previousClaimedNickname]?.isEmpty == true {
                    self.cache.nicknameIndex.removeValue(forKey: previousClaimedNickname)
                }
            }

            if self.cache.nicknameIndex[identity.claimedNickname] == nil {
                self.cache.nicknameIndex[identity.claimedNickname] = Set<String>()
            }
            self.cache.nicknameIndex[identity.claimedNickname]?.insert(identity.fingerprint)

            self.saveIdentityCache()
        }
    }

    func getFavorites() -> Set<String> {
        queue.sync {
            let favorites = cache.socialIdentities.values
                .filter { $0.isFavorite }
                .map { $0.fingerprint }
            return Set(favorites)
        }
    }

    func setFavorite(_ fingerprint: String, isFavorite: Bool) {
        queue.async(flags: .barrier) {
            if var identity = self.cache.socialIdentities[fingerprint] {
                identity.isFavorite = isFavorite
                self.cache.socialIdentities[fingerprint] = identity
            } else {

                let newIdentity = SocialIdentity(
                    fingerprint: fingerprint,
                    localPetname: nil,
                    claimedNickname: "Unknown",
                    trustLevel: .unknown,
                    isFavorite: isFavorite,
                    isBlocked: false,
                    notes: nil
                )
                self.cache.socialIdentities[fingerprint] = newIdentity
            }
            self.saveIdentityCache()
        }
    }

    func isFavorite(fingerprint: String) -> Bool {
        queue.sync {
            return cache.socialIdentities[fingerprint]?.isFavorite ?? false
        }
    }

    func isBlocked(fingerprint: String) -> Bool {
        queue.sync {
            return cache.socialIdentities[fingerprint]?.isBlocked ?? false
        }
    }

    func setBlocked(_ fingerprint: String, isBlocked: Bool) {
        SecureLogger.info("User \(isBlocked ? "blocked" : "unblocked"): \(fingerprint)", category: .security)

        queue.async(flags: .barrier) {
            if var identity = self.cache.socialIdentities[fingerprint] {
                identity.isBlocked = isBlocked
                if isBlocked {
                    identity.isFavorite = false
                }
                self.cache.socialIdentities[fingerprint] = identity
            } else {

                let newIdentity = SocialIdentity(
                    fingerprint: fingerprint,
                    localPetname: nil,
                    claimedNickname: "Unknown",
                    trustLevel: .unknown,
                    isFavorite: false,
                    isBlocked: isBlocked,
                    notes: nil
                )
                self.cache.socialIdentities[fingerprint] = newIdentity
            }
            self.saveIdentityCache()
        }
    }

    func isNostrBlocked(pubkeyHexLowercased: String) -> Bool {
        queue.sync {
            return cache.blockedNostrPubkeys.contains(pubkeyHexLowercased.lowercased())
        }
    }

    func setNostrBlocked(_ pubkeyHexLowercased: String, isBlocked: Bool) {
        let key = pubkeyHexLowercased.lowercased()
        queue.async(flags: .barrier) {
            if isBlocked {
                self.cache.blockedNostrPubkeys.insert(key)
            } else {
                self.cache.blockedNostrPubkeys.remove(key)
            }
            self.saveIdentityCache()
        }
    }

    func getBlockedNostrPubkeys() -> Set<String> {
        queue.sync { cache.blockedNostrPubkeys }
    }

    func registerEphemeralSession(peerID: PeerID, handshakeState: HandshakeState = .none) {
        queue.async(flags: .barrier) {
            self.ephemeralSessions[peerID] = EphemeralIdentity(
                peerID: peerID,
                sessionStart: Date(),
                handshakeState: handshakeState
            )
        }
    }

    func updateHandshakeState(peerID: PeerID, state: HandshakeState) {
        queue.async(flags: .barrier) {
            self.ephemeralSessions[peerID]?.handshakeState = state

            if case .completed(let fingerprint) = state {
                self.cache.lastInteractions[fingerprint] = Date()
                self.saveIdentityCache()
            }
        }
    }

    func clearAllIdentityData() {
        SecureLogger.warning("Clearing all identity data", category: .security)

        queue.async(flags: .barrier) {
            self.cache = IdentityCache()
            self.ephemeralSessions.removeAll()
            self.cryptographicIdentities.removeAll()

            let deleted = self.keychain.deleteIdentityKey(forKey: self.cacheKey)
            SecureLogger.logKeyOperation(.delete, keyType: "identity cache", success: deleted)
        }
    }

    func removeEphemeralSession(peerID: PeerID) {
        queue.async(flags: .barrier) {
            self.ephemeralSessions.removeValue(forKey: peerID)
        }
    }

    func setVerified(fingerprint: String, verified: Bool) {
        SecureLogger.info("Fingerprint \(verified ? "verified" : "unverified"): \(fingerprint)", category: .security)

        queue.async(flags: .barrier) {
            if verified {
                self.cache.verifiedFingerprints.insert(fingerprint)
            } else {
                self.cache.verifiedFingerprints.remove(fingerprint)
            }

            if var identity = self.cache.socialIdentities[fingerprint] {
                identity.trustLevel = verified ? .verified : .casual
                self.cache.socialIdentities[fingerprint] = identity
            }

            self.saveIdentityCache()
        }
    }

    func isVerified(fingerprint: String) -> Bool {
        queue.sync {
            return cache.verifiedFingerprints.contains(fingerprint)
        }
    }

    func getVerifiedFingerprints() -> Set<String> {
        queue.sync {
            return cache.verifiedFingerprints
        }
    }

    var debugNicknameIndex: [String: Set<String>] {
        queue.sync { cache.nicknameIndex }
    }

    func debugEphemeralSession(for peerID: PeerID) -> EphemeralIdentity? {
        queue.sync { ephemeralSessions[peerID] }
    }

    func debugLastInteraction(for fingerprint: String) -> Date? {
        queue.sync { cache.lastInteractions[fingerprint] }
    }
}
