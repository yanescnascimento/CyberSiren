import struct BitFoundation.BitchatPacket
import Foundation
import CryptoKit

enum PacketIdUtil {
    static func computeId(_ packet: BitchatPacket) -> Data {
        var hasher = SHA256()
        hasher.update(data: Data([packet.type]))
        hasher.update(data: packet.senderID)
        var tsBE = packet.timestamp.bigEndian
        withUnsafeBytes(of: &tsBE) { raw in hasher.update(data: Data(raw)) }
        hasher.update(data: packet.payload)
        let digest = hasher.finalize()
        return Data(digest.prefix(16))
    }
}
