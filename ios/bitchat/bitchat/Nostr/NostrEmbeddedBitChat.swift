import Foundation
import BitFoundation

struct NostrEmbeddedBitChat {

    static func encodePMForNostr(content: String, messageID: String, recipientPeerID: PeerID, senderPeerID: PeerID) -> String? {

        let pm = PrivateMessagePacket(messageID: messageID, content: content)
        guard let tlv = pm.encode() else { return nil }

        var payload = Data([NoisePayloadType.privateMessage.rawValue])
        payload.append(tlv)

        let recipientID = normalizeRecipientPeerID(recipientPeerID)

        let packet = BitchatPacket(
            type: MessageType.noiseEncrypted.rawValue,
            senderID: Data(hexString: senderPeerID.id) ?? Data(),
            recipientID: Data(hexString: recipientID.id),
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            payload: payload,
            signature: nil,
            ttl: 7
        )

        guard let data = packet.toBinaryData() else { return nil }
        return "bitchat1:" + base64URLEncode(data)
    }

    static func encodeAckForNostr(type: NoisePayloadType, messageID: String, recipientPeerID: PeerID, senderPeerID: PeerID) -> String? {
        guard type == .delivered || type == .readReceipt else { return nil }

        var payload = Data([type.rawValue])
        payload.append(Data(messageID.utf8))

        let recipientID = normalizeRecipientPeerID(recipientPeerID)

        let packet = BitchatPacket(
            type: MessageType.noiseEncrypted.rawValue,
            senderID: Data(hexString: senderPeerID.id) ?? Data(),
            recipientID: Data(hexString: recipientID.id),
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            payload: payload,
            signature: nil,
            ttl: 7
        )

        guard let data = packet.toBinaryData() else { return nil }
        return "bitchat1:" + base64URLEncode(data)
    }

    static func encodeAckForNostrNoRecipient(type: NoisePayloadType, messageID: String, senderPeerID: PeerID) -> String? {
        guard type == .delivered || type == .readReceipt else { return nil }

        var payload = Data([type.rawValue])
        payload.append(Data(messageID.utf8))

        let packet = BitchatPacket(
            type: MessageType.noiseEncrypted.rawValue,
            senderID: Data(hexString: senderPeerID.id) ?? Data(),
            recipientID: nil,
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            payload: payload,
            signature: nil,
            ttl: 7
        )

        guard let data = packet.toBinaryData() else { return nil }
        return "bitchat1:" + base64URLEncode(data)
    }

    static func encodePMForNostrNoRecipient(content: String, messageID: String, senderPeerID: PeerID) -> String? {
        let pm = PrivateMessagePacket(messageID: messageID, content: content)
        guard let tlv = pm.encode() else { return nil }

        var payload = Data([NoisePayloadType.privateMessage.rawValue])
        payload.append(tlv)

        let packet = BitchatPacket(
            type: MessageType.noiseEncrypted.rawValue,
            senderID: Data(hexString: senderPeerID.id) ?? Data(),
            recipientID: nil,
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            payload: payload,
            signature: nil,
            ttl: 7
        )

        guard let data = packet.toBinaryData() else { return nil }
        return "bitchat1:" + base64URLEncode(data)
    }

    private static func normalizeRecipientPeerID(_ recipientPeerID: PeerID) -> PeerID {
        if let maybeData = Data(hexString: recipientPeerID.id) {
            if maybeData.count == 32 {

                return PeerID(publicKey: maybeData)
            } else if maybeData.count == 8 {

                return recipientPeerID
            }
        }

        return recipientPeerID
    }

    private static func base64URLEncode(_ data: Data) -> String {
        let b64 = data.base64EncodedString()
        return b64
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
