import BitLogger
import BitFoundation
import Foundation
import Combine
import SwiftUI

@MainActor
final class UnifiedPeerService: ObservableObject, TransportPeerEventsDelegate {

    @Published private(set) var peers: [BitchatPeer] = []
    @Published private(set) var connectedPeerIDs: Set<PeerID> = []
    @Published private(set) var favorites: [BitchatPeer] = []
    @Published private(set) var mutualFavorites: [BitchatPeer] = []

    private var peerIndex: [PeerID: BitchatPeer] = [:]
    private var fingerprintCache: [PeerID: String] = [:]
    private let meshService: Transport
    private let idBridge: NostrIdentityBridge
    private let identityManager: SecureIdentityStateManagerProtocol
    weak var messageRouter: MessageRouter?
    private let favoritesService = FavoritesPersistenceService.shared
    private var cancellables = Set<AnyCancellable>()

    init(
        meshService: Transport,
        idBridge: NostrIdentityBridge,
        identityManager: SecureIdentityStateManagerProtocol
    ) {
        self.meshService = meshService
        self.idBridge = idBridge
        self.identityManager = identityManager

        setupSubscriptions()

        Task { @MainActor in
            updatePeers()
        }
    }

    private func setupSubscriptions() {

        meshService.peerEventsDelegate = self

        NotificationCenter.default.publisher(for: .favoriteStatusChanged)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.updatePeers()
            }
            .store(in: &cancellables)
    }

    func didUpdatePeerSnapshots(_ peers: [TransportPeerSnapshot]) {
        updatePeers()
    }

    private func updatePeers() {
        let meshPeers = meshService.currentPeerSnapshots()

        let hasAnyConnected = meshPeers.contains { $0.isConnected }
        let favorites = favoritesService.favorites

        var enrichedPeers: [BitchatPeer] = []
        var connected: Set<PeerID> = []
        var addedPeerIDs: Set<PeerID> = []

        for peerInfo in meshPeers {
            let peerID = peerInfo.peerID
            guard peerID != meshService.myPeerID else { continue }

            let peer = buildPeerFromMesh(
                peerInfo: peerInfo,
                favorites: favorites,
                meshAttached: hasAnyConnected
            )

            enrichedPeers.append(peer)
            if peer.isConnected { connected.insert(peerID) }
            addedPeerIDs.insert(peerID)

            if let publicKey = peerInfo.noisePublicKey {
                fingerprintCache[peerID] = publicKey.sha256Fingerprint()
            }
        }

        for (favoriteKey, favorite) in favorites where favorite.isFavorite {
            let peerID = PeerID(hexData: favoriteKey)

            if addedPeerIDs.contains(peerID) { continue }

            let isConnectedByNickname = enrichedPeers.contains {
                $0.nickname == favorite.peerNickname && $0.isConnected
            }
            if isConnectedByNickname { continue }

            let peer = buildPeerFromFavorite(favorite: favorite, peerID: peerID)
            enrichedPeers.append(peer)
            addedPeerIDs.insert(peerID)

            fingerprintCache[peerID] = favoriteKey.sha256Fingerprint()
        }

        enrichedPeers.sort { lhs, rhs in

            func rank(_ p: BitchatPeer) -> Int { p.isConnected ? 2 : (p.isReachable ? 1 : 0) }
            let lr = rank(lhs), rr = rank(rhs)
            if lr != rr { return lr > rr }

            if lhs.isFavorite != rhs.isFavorite { return lhs.isFavorite }

            return lhs.displayName < rhs.displayName
        }

        var favoritesList: [BitchatPeer] = []
        var mutualsList: [BitchatPeer] = []
        var newIndex: [PeerID: BitchatPeer] = [:]

        for peer in enrichedPeers {
            newIndex[peer.peerID] = peer

            if peer.isFavorite {
                favoritesList.append(peer)
            }
            if peer.isMutualFavorite {
                mutualsList.append(peer)
            }
        }

        let filtered = enrichedPeers.filter { p in
            p.isConnected || p.isReachable || p.isMutualFavorite
        }
        self.peers = filtered
        self.connectedPeerIDs = connected
        self.favorites = favoritesList
        self.mutualFavorites = mutualsList
        self.peerIndex = newIndex

    }

    private func buildPeerFromMesh(
        peerInfo: TransportPeerSnapshot,
        favorites: [Data: FavoritesPersistenceService.FavoriteRelationship],
        meshAttached: Bool
    ) -> BitchatPeer {

        let now = Date()
        let fingerprint = peerInfo.noisePublicKey?.sha256Fingerprint()
        let isVerified = fingerprint.map { identityManager.isVerified(fingerprint: $0) } ?? false
        let isFav = peerInfo.noisePublicKey.flatMap { favorites[$0]?.isFavorite } ?? false
        let retention: TimeInterval = (isVerified || isFav) ? TransportConfig.bleReachabilityRetentionVerifiedSeconds : TransportConfig.bleReachabilityRetentionUnverifiedSeconds

        let withinRetention = now.timeIntervalSince(peerInfo.lastSeen) <= retention
        let isReachable = peerInfo.isConnected ? true : (withinRetention && meshAttached)

        var peer = BitchatPeer(
            peerID: peerInfo.peerID,
            noisePublicKey: peerInfo.noisePublicKey ?? Data(),
            nickname: peerInfo.nickname,
            lastSeen: peerInfo.lastSeen,
            isConnected: peerInfo.isConnected,
            isReachable: isReachable
        )

        if let noiseKey = peerInfo.noisePublicKey,
           let favoriteStatus = favorites[noiseKey] {
            peer.favoriteStatus = favoriteStatus
            peer.nostrPublicKey = favoriteStatus.peerNostrPublicKey
        }

        return peer
    }

    private func buildPeerFromFavorite(
        favorite: FavoritesPersistenceService.FavoriteRelationship,
        peerID: PeerID
    ) -> BitchatPeer {
        var peer = BitchatPeer(
            peerID: peerID,
            noisePublicKey: favorite.peerNoisePublicKey,
            nickname: favorite.peerNickname,
            lastSeen: favorite.lastUpdated,
            isConnected: false,
            isReachable: false
        )

        peer.favoriteStatus = favorite
        peer.nostrPublicKey = favorite.peerNostrPublicKey

        return peer
    }

    func getPeer(by peerID: PeerID) -> BitchatPeer? {
        return peerIndex[peerID]
    }

    func getPeerID(for nickname: String) -> PeerID? {
        for peer in peers {
            if peer.displayName == nickname || peer.nickname == nickname {
                return peer.peerID
            }
        }
        return nil
    }

    func isBlocked(_ peerID: PeerID) -> Bool {

        guard let fingerprint = getFingerprint(for: peerID) else { return false }

        if let identity = identityManager.getSocialIdentity(for: fingerprint) {
            return identity.isBlocked
        }

        return false
    }

    func toggleFavorite(_ peerID: PeerID) {
        guard let peer = getPeer(by: peerID) else {
            SecureLogger.warning("Cannot toggle favorite - peer not found: \(peerID)", category: .session)
            return
        }

        let wasFavorite = peer.isFavorite

        var actualNickname = peer.nickname

        SecureLogger.debug("Toggle favorite - peer.nickname: '\(peer.nickname)', peer.displayName: '\(peer.displayName)', peerID: \(peerID)", category: .session)

        if actualNickname.isEmpty {

            if let meshPeerNickname = meshService.peerNickname(peerID: peerID) {
                actualNickname = meshPeerNickname
                SecureLogger.debug("Got nickname from mesh service: '\(actualNickname)'", category: .session)
            }
        }

        let finalNickname = actualNickname.isEmpty ? peer.displayName : actualNickname

        if wasFavorite {

            favoritesService.removeFavorite(peerNoisePublicKey: peer.noisePublicKey)
        } else {

            var peerNostrKey = peer.nostrPublicKey
            if peerNostrKey == nil {

                peerNostrKey = idBridge.getNostrPublicKey(for: peer.noisePublicKey)
            }

            favoritesService.addFavorite(
                peerNoisePublicKey: peer.noisePublicKey,
                peerNostrPublicKey: peerNostrKey,
                peerNickname: finalNickname
            )
        }

        SecureLogger.debug("⭐Toggled favorite for '\(finalNickname)' (peerID: \(peerID), was: \(wasFavorite), now: \(!wasFavorite))", category: .session)

        if let router = messageRouter {
            router.sendFavoriteNotification(to: peerID, isFavorite: !wasFavorite)
        } else {

            meshService.sendFavoriteNotification(to: peerID, isFavorite: !wasFavorite)
        }

        updatePeers()

        DispatchQueue.main.async { [weak self] in
            self?.objectWillChange.send()
        }
    }

    func getFingerprint(for peerID: PeerID) -> String? {

        if let cached = fingerprintCache[peerID] {
            return cached
        }

        if let fingerprint = meshService.getFingerprint(for: peerID) {
            fingerprintCache[peerID] = fingerprint
            return fingerprint
        }

        if let peer = getPeer(by: peerID) {
            let fingerprint = peer.noisePublicKey.sha256Fingerprint()
            fingerprintCache[peerID] = fingerprint
            return fingerprint
        }

        return nil
    }

    var allPeers: [BitchatPeer] { peers }
    var connectedPeers: Set<PeerID> { connectedPeerIDs }
    var favoritePeers: Set<String> {
        Set(favorites.compactMap { getFingerprint(for: $0.peerID) })
    }
    var blockedUsers: Set<String> {
        Set(peers.compactMap { peer in
            isBlocked(peer.peerID) ? getFingerprint(for: peer.peerID) : nil
        })
    }
}
