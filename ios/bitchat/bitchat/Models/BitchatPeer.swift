import Foundation
import CoreBluetooth
import BitFoundation

struct BitchatPeer: Equatable {
    let peerID: PeerID
    let noisePublicKey: Data
    let nickname: String
    let lastSeen: Date
    let isConnected: Bool
    let isReachable: Bool

    var favoriteStatus: FavoritesPersistenceService.FavoriteRelationship?

    var nostrPublicKey: String?

    enum ConnectionState {
        case bluetoothConnected
        case meshReachable
        case nostrAvailable
        case offline
    }

    var connectionState: ConnectionState {
        if isConnected {
            return .bluetoothConnected
        } else if isReachable {
            return .meshReachable
        } else if favoriteStatus?.isMutual == true {

            return .nostrAvailable
        } else {
            return .offline
        }
    }

    var isFavorite: Bool {
        favoriteStatus?.isFavorite ?? false
    }

    var isMutualFavorite: Bool {
        favoriteStatus?.isMutual ?? false
    }

    var theyFavoritedUs: Bool {
        favoriteStatus?.theyFavoritedUs ?? false
    }

    var displayName: String {
        nickname.isEmpty ? String(peerID.id.prefix(8)) : nickname
    }

    var statusIcon: String {
        switch connectionState {
        case .bluetoothConnected:
            return ""
        case .meshReachable:
            return ""
        case .nostrAvailable:
            return ""
        case .offline:
            if theyFavoritedUs && !isFavorite {
                return ""
            } else {
                return ""
            }
        }
    }

    init(
        peerID: PeerID,
        noisePublicKey: Data,
        nickname: String,
        lastSeen: Date = Date(),
        isConnected: Bool = false,
        isReachable: Bool = false
    ) {
        self.peerID = peerID
        self.noisePublicKey = noisePublicKey
        self.nickname = nickname
        self.lastSeen = lastSeen
        self.isConnected = isConnected
        self.isReachable = isReachable

        self.favoriteStatus = nil
        self.nostrPublicKey = nil
    }

    static func == (lhs: BitchatPeer, rhs: BitchatPeer) -> Bool {
        lhs.peerID == rhs.peerID
    }
}
