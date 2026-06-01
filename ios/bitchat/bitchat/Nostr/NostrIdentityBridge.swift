import BitFoundation
import Foundation
import CryptoKit

final class NostrIdentityBridge {
    private let keychainService = "com.cybersiren.ios.nostr"
    private let currentIdentityKey = "nostr-current-identity"
    private let deviceSeedKey = "nostr-device-seed"

    private var deviceSeedCache: Data?

    private var derivedIdentityCache: [String: NostrIdentity] = [:]
    private let cacheLock = NSLock()

    private let keychain: KeychainManagerProtocol

    init(keychain: KeychainManagerProtocol = KeychainManager()) {
        self.keychain = keychain
    }

    func getCurrentNostrIdentity() throws -> NostrIdentity? {

        if let existingData = keychain.load(key: currentIdentityKey, service: keychainService),
           let identity = try? JSONDecoder().decode(NostrIdentity.self, from: existingData) {
            return identity
        }

        let nostrIdentity = try NostrIdentity.generate()

        let data = try JSONEncoder().encode(nostrIdentity)
        keychain.save(key: currentIdentityKey, data: data, service: keychainService, accessible: nil)

        return nostrIdentity
    }

    func associateNostrIdentity(_ nostrPubkey: String, with noisePublicKey: Data) {
        let key = "nostr-noise-\(noisePublicKey.base64EncodedString())"
        if let data = nostrPubkey.data(using: .utf8) {
            keychain.save(key: key, data: data, service: keychainService, accessible: nil)
        }
    }

    func getNostrPublicKey(for noisePublicKey: Data) -> String? {
        let key = "nostr-noise-\(noisePublicKey.base64EncodedString())"
        guard let data = keychain.load(key: key, service: keychainService),
              let pubkey = String(data: data, encoding: .utf8) else {
            return nil
        }
        return pubkey
    }

    func clearAllAssociations() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let items = result as? [[String: Any]] {
            for item in items {
                var deleteQuery: [String: Any] = [
                    kSecClass as String: kSecClassGenericPassword,
                    kSecAttrService as String: keychainService
                ]
                if let account = item[kSecAttrAccount as String] as? String {
                    deleteQuery[kSecAttrAccount as String] = account
                }
                SecItemDelete(deleteQuery as CFDictionary)
            }
        } else if status == errSecItemNotFound {

        }

        deviceSeedCache = nil
    }

    private func getOrCreateDeviceSeed() -> Data {
        if let cached = deviceSeedCache { return cached }
        if let existing = keychain.load(key: deviceSeedKey, service: keychainService) {

            keychain.save(key: deviceSeedKey, data: existing, service: keychainService, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            deviceSeedCache = existing
            return existing
        }
        var seed = Data(count: 32)
        _ = seed.withUnsafeMutableBytes { ptr in
            SecRandomCopyBytes(kSecRandomDefault, 32, ptr.baseAddress!)
        }

        keychain.save(key: deviceSeedKey, data: seed, service: keychainService, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        deviceSeedCache = seed
        return seed
    }

    func deriveIdentity(forGeohash geohash: String) throws -> NostrIdentity {

        cacheLock.lock()
        if let cached = derivedIdentityCache[geohash] {
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()

        let seed = getOrCreateDeviceSeed()
        guard let msg = geohash.data(using: .utf8) else {
            throw NSError(domain: "NostrIdentity", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid geohash string"])
        }

        func candidateKey(iteration: UInt32) -> Data {
            var input = Data(msg)
            var iterBE = iteration.bigEndian
            withUnsafeBytes(of: &iterBE) { bytes in
                input.append(contentsOf: bytes)
            }
            let code = HMAC<SHA256>.authenticationCode(for: input, using: SymmetricKey(data: seed))
            return Data(code)
        }

        for i in 0..<10 {
            let keyData = candidateKey(iteration: UInt32(i))
            if let identity = try? NostrIdentity(privateKeyData: keyData) {

                cacheLock.lock()
                derivedIdentityCache[geohash] = identity
                cacheLock.unlock()
                return identity
            }
        }

        let fallback = (seed + msg).sha256Hash()
        let identity = try NostrIdentity(privateKeyData: fallback)

        cacheLock.lock()
        derivedIdentityCache[geohash] = identity
        cacheLock.unlock()

        return identity
    }
}
