import Testing
import CryptoKit
import Foundation
import BitFoundation
@testable import bitchat

struct NostrProtocolTests {

    @Test func nip17MessageRoundTrip() throws {

        let sender = try NostrIdentity.generate()
        let recipient = try NostrIdentity.generate()

        print("Sender pubkey: \(sender.publicKeyHex)")
        print("Recipient pubkey: \(recipient.publicKeyHex)")

        let originalContent = "Hello from NIP-17 test!"

        let giftWrap = try NostrProtocol.createPrivateMessage(
            content: originalContent,
            recipientPubkey: recipient.publicKeyHex,
            senderIdentity: sender
        )

        print("Gift wrap created with ID: \(giftWrap.id)")
        print("Gift wrap pubkey: \(giftWrap.pubkey)")

        let (decryptedContent, senderPubkey, timestamp) = try NostrProtocol.decryptPrivateMessage(
            giftWrap: giftWrap,
            recipientIdentity: recipient
        )

        #expect(decryptedContent == originalContent)
        #expect(senderPubkey == sender.publicKeyHex)

        let messageDate = Date(timeIntervalSince1970: TimeInterval(timestamp))
        let timeDiff = abs(messageDate.timeIntervalSinceNow)
        #expect(timeDiff < 60, "Message timestamp should be recent")

        print("Successfully decrypted message: '\(decryptedContent)' from \(senderPubkey) at \(messageDate)")
    }

    @Test func giftWrapUsesUniqueEphemeralKeys() throws {

        let sender = try NostrIdentity.generate()
        let recipient = try NostrIdentity.generate()

        let message1 = try NostrProtocol.createPrivateMessage(
            content: "Message 1",
            recipientPubkey: recipient.publicKeyHex,
            senderIdentity: sender
        )

        let message2 = try NostrProtocol.createPrivateMessage(
            content: "Message 2",
            recipientPubkey: recipient.publicKeyHex,
            senderIdentity: sender
        )

        #expect(message1.pubkey != message2.pubkey)

        print("Message 1 gift wrap pubkey: \(message1.pubkey)")
        print("Message 2 gift wrap pubkey: \(message2.pubkey)")

        let (content1, _, _) = try NostrProtocol.decryptPrivateMessage(
            giftWrap: message1,
            recipientIdentity: recipient
        )
        let (content2, _, _) = try NostrProtocol.decryptPrivateMessage(
            giftWrap: message2,
            recipientIdentity: recipient
        )

        #expect(content1 == "Message 1")
        #expect(content2 == "Message 2")
    }

    @Test func decryptionFailsWithWrongRecipient() throws {
        let sender = try NostrIdentity.generate()
        let recipient = try NostrIdentity.generate()
        let wrongRecipient = try NostrIdentity.generate()

        let giftWrap = try NostrProtocol.createPrivateMessage(
            content: "Secret message",
            recipientPubkey: recipient.publicKeyHex,
            senderIdentity: sender
        )

        if #available(macOS 14.4, iOS 17.4, *) {
            #expect(throws: CryptoKitError.authenticationFailure) {
                try NostrProtocol.decryptPrivateMessage(
                    giftWrap: giftWrap,
                    recipientIdentity: wrongRecipient
                )
            }
        } else {
            #expect(throws: (any Error).self) {
                try NostrProtocol.decryptPrivateMessage(
                    giftWrap: giftWrap,
                    recipientIdentity: wrongRecipient
                )
            }
        }
    }

    func testAckRoundTripNIP44V2_Delivered() throws {

        let sender = try NostrIdentity.generate()
        let recipient = try NostrIdentity.generate()

        let messageID = "TEST-MSG-DELIVERED-1"
        let senderPeerID = PeerID(str: "0123456789abcdef")

        let embedded = try #require(
            NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type: .delivered, messageID: messageID, senderPeerID: senderPeerID),
            "Failed to embed delivered ack"
        )

        let giftWrap = try NostrProtocol.createPrivateMessage(
            content: embedded,
            recipientPubkey: recipient.publicKeyHex,
            senderIdentity: sender
        )

        #expect(giftWrap.content.hasPrefix("v2:"))

        let (content, senderPubkey, _) = try NostrProtocol.decryptPrivateMessage(
            giftWrap: giftWrap,
            recipientIdentity: recipient
        )

        #expect(senderPubkey == sender.publicKeyHex)

        #expect(content.hasPrefix("bitchat1:"))
        let base64url = String(content.dropFirst("bitchat1:".count))
        let packetData = try #require(Self.base64URLDecode(base64url))
        let packet = try #require(BitchatPacket.from(packetData), "Failed to decode bitchat packet")

        #expect(packet.type == MessageType.noiseEncrypted.rawValue)
        let payload = try #require(NoisePayload.decode(packet.payload), "Failed to decode NoisePayload")

        switch payload.type {
        case .delivered:
            let mid = String(data: payload.data, encoding: .utf8)
            #expect(mid == messageID)
        default:
            Issue.record("Unexpected payload type: \(payload.type)")
        }
    }

    @Test func ackRoundTripNIP44V2_ReadReceipt() throws {

        let sender = try NostrIdentity.generate()
        let recipient = try NostrIdentity.generate()

        let messageID = "TEST-MSG-READ-1"
        let senderPeerID = PeerID(str: "fedcba9876543210")
        let embedded = try #require(
            NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type: .readReceipt, messageID: messageID, senderPeerID: senderPeerID),
            "Failed to embed read ack"
        )

        let giftWrap = try NostrProtocol.createPrivateMessage(
            content: embedded,
            recipientPubkey: recipient.publicKeyHex,
            senderIdentity: sender
        )

        #expect(giftWrap.content.hasPrefix("v2:"))

        let (content, senderPubkey, _) = try NostrProtocol.decryptPrivateMessage(
            giftWrap: giftWrap,
            recipientIdentity: recipient
        )
        #expect(senderPubkey == sender.publicKeyHex)

        #expect(content.hasPrefix("bitchat1:"))
        let base64url = String(content.dropFirst("bitchat1:".count))
        let packetData = try #require(Self.base64URLDecode(base64url))
        let packet = try #require(BitchatPacket.from(packetData), "Failed to decode bitchat packet")

        #expect(packet.type == MessageType.noiseEncrypted.rawValue)
        let payload = try #require(NoisePayload.decode(packet.payload), "Failed to decode NoisePayload")

        switch payload.type {
        case .readReceipt:
            let mid = String(data: payload.data, encoding: .utf8)
            #expect(mid == messageID)
        default:
            Issue.record("Unexpected payload type: \(payload.type)")
        }
    }

    @Test func nostrEventSignatureVerification_roundTrip() throws {
        let identity = try NostrIdentity.generate()
        let event = NostrEvent(
            pubkey: identity.publicKeyHex,
            createdAt: Date(),
            kind: .ephemeralEvent,
            tags: [],
            content: "Signed event"
        )
        let signed = try event.sign(with: identity.schnorrSigningKey())
        #expect(signed.isValidSignature())
    }

    @Test func nostrEventSignatureVerification_detectsTamper() throws {
        let identity = try NostrIdentity.generate()
        let event = NostrEvent(
            pubkey: identity.publicKeyHex,
            createdAt: Date(),
            kind: .ephemeralEvent,
            tags: [],
            content: "Original"
        )
        var signed = try event.sign(with: identity.schnorrSigningKey())
        signed.id = "deadbeef"
        #expect(!signed.isValidSignature())
    }

    @Test func geohashNotesSingleFilter_encodesExpectedTagShape() throws {
        let since = Date(timeIntervalSince1970: 1_234_567)
        let filter = NostrFilter.geohashNotes("u4pruyd", since: since, limit: 42)
        let data = try JSONEncoder().encode(filter)
        let object = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])

        #expect(object["kinds"] as? [Int] == [1])
        #expect(object["#g"] as? [String] == ["u4pruyd"])
        #expect(object["since"] as? Int == 1_234_567)
        #expect(object["limit"] as? Int == 42)
    }

    private static func base64URLDecode(_ s: String) -> Data? {
        var str = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let rem = str.count % 4
        if rem > 0 { str.append(String(repeating: "=", count: 4 - rem)) }
        return Data(base64Encoded: str)
    }
}
