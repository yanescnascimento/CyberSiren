import Foundation
import BitFoundation

struct PeerDisplayNameResolver {

    static func resolve(_ peers: [(peerID: PeerID, nickname: String, isConnected: Bool)], selfNickname: String) -> [PeerID: String] {

        var counts: [String: Int] = [:]
        for p in peers where p.isConnected {
            counts[p.nickname, default: 0] += 1
        }
        counts[selfNickname, default: 0] += 1

        var result: [PeerID: String] = [:]
        for p in peers {
            var name = p.nickname
            if p.isConnected, (counts[p.nickname] ?? 0) > 1 {
                name += "#" + String(p.peerID.id.prefix(4))
            }
            result[p.peerID] = name
        }
        return result
    }
}
