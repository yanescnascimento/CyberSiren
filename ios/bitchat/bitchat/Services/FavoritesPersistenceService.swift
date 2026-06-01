import BitLogger
import BitFoundation
import Foundation
import Combine

@MainActor
final class FavoritesPersistenceService: ObservableObject {

    struct FavoriteRelationship: Codable {
        let peerNoisePublicKey: Data
        let peerNostrPublicKey: String?
        let peerNickname: String
        let isFavorite: Bool
        let theyFavoritedUs: Bool
        let favoritedAt: Date
        let lastUpdated: Date

        var isMutual: Bool {
            isFavorite && theyFavoritedUs
        }
    }

    private static let storageKey = "com.cybersiren.ios.favorites"
    private static let keychainService = "com.cybersiren.ios.favorites"
    private let keychain: KeychainManagerProtocol

    @Published private(set) var favorites: [Data: FavoriteRelationship] = [:]
    @Published private(set) var mutualFavorites: Set<Data> = []

    static let shared = FavoritesPersistenceService()

    init(keychain: KeychainManagerProtocol = KeychainManager()) {
        self.keychain = keychain
        loadFavorites()

        $favorites
            .map { favorites in
                Set(favorites.compactMap { $0.value.isMutual ? $0.key : nil })
            }
            .assign(to: &$mutualFavorites)
    }

    func addFavorite(
        peerNoisePublicKey: Data,
        peerNostrPublicKey: String? = nil,
        peerNickname: String
    ) {
        SecureLogger.info("⭐Adding favorite: \(peerNickname) (\(peerNoisePublicKey.hexEncodedString()))", category: .session)

        let existing = favorites[peerNoisePublicKey]

        let relationship = FavoriteRelationship(
            peerNoisePublicKey: peerNoisePublicKey,
            peerNostrPublicKey: peerNostrPublicKey ?? existing?.peerNostrPublicKey,
            peerNickname: peerNickname,
            isFavorite: true,
            theyFavoritedUs: existing?.theyFavoritedUs ?? false,
            favoritedAt: existing?.favoritedAt ?? Date(),
            lastUpdated: Date()
        )

        if relationship.isMutual {
            SecureLogger.info("Mutual favorite relationship established with \(peerNickname)!", category: .session)
        }

        favorites[peerNoisePublicKey] = relationship
        saveFavorites()

        NotificationCenter.default.post(
            name: .favoriteStatusChanged,
            object: nil,
            userInfo: ["peerPublicKey": peerNoisePublicKey]
        )
    }

    func removeFavorite(peerNoisePublicKey: Data) {
        guard let existing = favorites[peerNoisePublicKey] else { return }

        SecureLogger.info("⭐Removing favorite: \(existing.peerNickname) (\(peerNoisePublicKey.hexEncodedString()))", category: .session)

        if existing.theyFavoritedUs {
            let updated = FavoriteRelationship(
                peerNoisePublicKey: existing.peerNoisePublicKey,
                peerNostrPublicKey: existing.peerNostrPublicKey,
                peerNickname: existing.peerNickname,
                isFavorite: false,
                theyFavoritedUs: true,
                favoritedAt: existing.favoritedAt,
                lastUpdated: Date()
            )
            favorites[peerNoisePublicKey] = updated

        } else {

            favorites.removeValue(forKey: peerNoisePublicKey)

        }

        saveFavorites()

        NotificationCenter.default.post(
            name: .favoriteStatusChanged,
            object: nil,
            userInfo: ["peerPublicKey": peerNoisePublicKey]
        )
    }

    func updatePeerFavoritedUs(
        peerNoisePublicKey: Data,
        favorited: Bool,
        peerNickname: String? = nil,
        peerNostrPublicKey: String? = nil
    ) {
        let existing = favorites[peerNoisePublicKey]
        let displayName = peerNickname ?? existing?.peerNickname ?? "Unknown"

        SecureLogger.info("Received favorite notification: \(displayName) \(favorited ? "favorited" : "unfavorited") us", category: .session)

        let relationship = FavoriteRelationship(
            peerNoisePublicKey: peerNoisePublicKey,
            peerNostrPublicKey: peerNostrPublicKey ?? existing?.peerNostrPublicKey,
            peerNickname: displayName,
            isFavorite: existing?.isFavorite ?? false,
            theyFavoritedUs: favorited,
            favoritedAt: existing?.favoritedAt ?? Date(),
            lastUpdated: Date()
        )

        if !relationship.isFavorite && !relationship.theyFavoritedUs {

            favorites.removeValue(forKey: peerNoisePublicKey)

        } else {
            favorites[peerNoisePublicKey] = relationship

            if relationship.isMutual {
                SecureLogger.info("Mutual favorite relationship established with \(displayName)!", category: .session)
            }
        }

        saveFavorites()

        NotificationCenter.default.post(
            name: .favoriteStatusChanged,
            object: nil,
            userInfo: ["peerPublicKey": peerNoisePublicKey]
        )
    }

    func isFavorite(_ peerNoisePublicKey: Data) -> Bool {
        favorites[peerNoisePublicKey]?.isFavorite ?? false
    }

    func isMutualFavorite(_ peerNoisePublicKey: Data) -> Bool {
        favorites[peerNoisePublicKey]?.isMutual ?? false
    }

    func getFavoriteStatus(for peerNoisePublicKey: Data) -> FavoriteRelationship? {
        favorites[peerNoisePublicKey]
    }

    func getFavoriteStatus(forPeerID peerID: PeerID) -> FavoriteRelationship? {

        guard peerID.isShort else { return nil }
        for (pubkey, rel) in favorites where PeerID(publicKey: pubkey) == peerID {
            return rel
        }
        return nil
    }

    func clearAllFavorites() {
        SecureLogger.warning("Clearing all favorites (panic mode)", category: .session)

        favorites.removeAll()
        saveFavorites()

        keychain.delete(
            key: Self.storageKey,
            service: Self.keychainService
        )

        NotificationCenter.default.post(name: .favoriteStatusChanged, object: nil)
    }

    private func saveFavorites() {
        let relationships = Array(favorites.values)

        do {
            let encoder = JSONEncoder()
            let data = try encoder.encode(relationships)

            keychain.save(
                key: Self.storageKey,
                data: data,
                service: Self.keychainService,
                accessible: nil
            )

        } catch {
            SecureLogger.error("Failed to save favorites: \(error)", category: .session)
        }
    }

    private func loadFavorites() {

        guard let data = keychain.load(
            key: Self.storageKey,
            service: Self.keychainService
        ) else {
            return
        }

        do {
            let decoder = JSONDecoder()
            let relationships = try decoder.decode([FavoriteRelationship].self, from: data)

            SecureLogger.info("Loaded \(relationships.count) favorite relationships", category: .session)

            for relationship in relationships {
                if relationship.peerNostrPublicKey == nil {
                    SecureLogger.warning("No Nostr public key stored for '\(relationship.peerNickname)'", category: .session)
                }
            }

            var seenPublicKeys: [Data: FavoriteRelationship] = [:]
            var cleanedRelationships: [FavoriteRelationship] = []

            for relationship in relationships {

                if let existing = seenPublicKeys[relationship.peerNoisePublicKey] {
                    SecureLogger.warning("Duplicate favorite found for public key \(relationship.peerNoisePublicKey.hexEncodedString()) - nicknames: '\(existing.peerNickname)' vs '\(relationship.peerNickname)'", category: .session)

                    if relationship.lastUpdated > existing.lastUpdated ||
                       (relationship.peerNostrPublicKey != nil && existing.peerNostrPublicKey == nil) {

                        seenPublicKeys[relationship.peerNoisePublicKey] = relationship
                        cleanedRelationships.removeAll { $0.peerNoisePublicKey == relationship.peerNoisePublicKey }
                        cleanedRelationships.append(relationship)
                    }
                } else {
                    seenPublicKeys[relationship.peerNoisePublicKey] = relationship
                    cleanedRelationships.append(relationship)
                }
            }

            if cleanedRelationships.count < relationships.count {

                favorites.removeAll()
                for relationship in cleanedRelationships {
                    favorites[relationship.peerNoisePublicKey] = relationship
                }

                saveFavorites()

                NotificationCenter.default.post(name: .favoriteStatusChanged, object: nil)
            } else {

                for relationship in cleanedRelationships {
                    favorites[relationship.peerNoisePublicKey] = relationship
                }
            }

        } catch {
            SecureLogger.error("Failed to load favorites: \(error)", category: .session)
        }
    }
}

extension Notification.Name {
    static let favoriteStatusChanged = Notification.Name("FavoriteStatusChanged")
}
