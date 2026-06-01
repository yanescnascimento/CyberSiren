import Testing
import CryptoKit
import struct Foundation.UUID
@testable import BitFoundation
@testable import bitchat

struct PrivateChatE2ETests {

    private let alice: MockBLEService
    private let bob: MockBLEService
    private let charlie: MockBLEService
    private let mockKeychain = MockKeychain()
    private let bus = MockBLEBus()

    init() {

        alice = MockBLEService(peerID: PeerID(str: UUID().uuidString), nickname: TestConstants.testNickname1, bus: bus)
        bob = MockBLEService(peerID: PeerID(str: UUID().uuidString), nickname: TestConstants.testNickname2, bus: bus)
        charlie = MockBLEService(peerID: PeerID(str: UUID().uuidString), nickname: TestConstants.testNickname3, bus: bus)
    }

    @Test func simplePrivateMessageShouldNotBeSentWithoutConnection() async {

        var bobReceivedMessage = false

        await confirmation("Bob should not receive a private message", expectedCount: 0) { bobReceivesMessage in
            bob.messageDeliveryHandler = { message in
                if message.content == TestConstants.testMessage1 &&
                   message.isPrivate &&
                   message.sender == TestConstants.testNickname1 {
                    bobReceivedMessage = true
                    bobReceivesMessage()
                }
            }

            alice.sendPrivateMessage(
                TestConstants.testMessage1,
                to: bob.peerID,
                recipientNickname: TestConstants.testNickname2
            )

            try? await sleep(0.1)
        }

        #expect(!bobReceivedMessage, "Bob should not have received the message")
    }

    @Test func simplePrivateMessage() async {
        alice.simulateConnection(with: bob)

        await confirmation("Bob receives private message") { bobReceivesMessage in
            bob.messageDeliveryHandler = { message in
                if message.content == TestConstants.testMessage1 &&
                   message.isPrivate &&
                   message.sender == TestConstants.testNickname1 {
                    bobReceivesMessage()
                }
            }

            alice.sendPrivateMessage(
                TestConstants.testMessage1,
                to: bob.peerID,
                recipientNickname: TestConstants.testNickname2
            )
        }
    }

    @Test func privateMessageNotReceivedByOthers() async {
        alice.simulateConnection(with: bob)
        alice.simulateConnection(with: charlie)

        await confirmation("Bob receives private message") { bobReceivesMessage in
            bob.messageDeliveryHandler = { message in
                if message.content == TestConstants.testMessage1 && message.isPrivate {
                    bobReceivesMessage()
                }
            }

            charlie.messageDeliveryHandler = { message in
                if message.content == TestConstants.testMessage1 {
                    Issue.record("Charlie should not receive")
                }
            }

            alice.sendPrivateMessage(
                TestConstants.testMessage1,
                to: bob.peerID,
                recipientNickname: TestConstants.testNickname2
            )
        }
    }

    @Test func privateMessageEncryption() async {
        alice.simulateConnection(with: bob)

        let aliceKey = Curve25519.KeyAgreement.PrivateKey()
        let bobKey = Curve25519.KeyAgreement.PrivateKey()

        let aliceManager = NoiseSessionManager(localStaticKey: aliceKey, keychain: mockKeychain)
        let bobManager = NoiseSessionManager(localStaticKey: bobKey, keychain: mockKeychain)

        do {
            let handshake1 = try aliceManager.initiateHandshake(with: bob.peerID)
            let handshake2 = try bobManager.handleIncomingHandshake(from: alice.peerID, message: handshake1)!
            let handshake3 = try aliceManager.handleIncomingHandshake(from: bob.peerID, message: handshake2)!
            _ = try bobManager.handleIncomingHandshake(from: alice.peerID, message: handshake3)
        } catch {
            Issue.record("Failed to establish Noise session: \(error)")
        }

        await confirmation("Encrypted message received") { receiveEncryptedMessage in

            alice.packetDeliveryHandler = { packet in

                if packet.type == 0x01,
                   let message = BitchatMessage(packet.payload),
                   message.isPrivate {
                    do {
                        let encrypted = try aliceManager.encrypt(packet.payload, for: bob.peerID)
                        let encryptedPacket = BitchatPacket(
                            type: 0x02,
                            senderID: packet.senderID,
                            recipientID: packet.recipientID,
                            timestamp: packet.timestamp,
                            payload: encrypted,
                            signature: packet.signature,
                            ttl: packet.ttl
                        )
                        self.bob.simulateIncomingPacket(encryptedPacket)
                    } catch {
                        Issue.record("Encryption failed: \(error)")
                    }
                }
            }

            bob.packetDeliveryHandler = { packet in

                if packet.type == 0x02 {
                    do {
                        let decrypted = try bobManager.decrypt(packet.payload, from: alice.peerID)
                        if let message = BitchatMessage(decrypted) {
                            #expect(message.content == TestConstants.testMessage1)
                            #expect(message.isPrivate)
                            receiveEncryptedMessage()
                        }
                    } catch {
                        Issue.record("Decryption failed: \(error)")
                    }
                }
            }

            alice.sendPrivateMessage(
                TestConstants.testMessage1,
                to: bob.peerID,
                recipientNickname: TestConstants.testNickname2
            )
        }
    }

    @Test func privateMessageRelay() async {

        alice.simulateConnection(with: bob)
        bob.simulateConnection(with: charlie)

        await confirmation("Private message relayed to Charlie") { charlieReceivesMessage in

            bob.packetDeliveryHandler = { packet in
                if let recipientID = packet.recipientID,
                   PeerID(data: recipientID) == charlie.peerID {

                    var relayPacket = packet
                    relayPacket.ttl = packet.ttl - 1
                    charlie.simulateIncomingPacket(relayPacket)
                }
            }

            charlie.messageDeliveryHandler = { message in
                if message.content == TestConstants.testMessage1 &&
                    message.isPrivate &&
                    message.recipientNickname == TestConstants.testNickname3 {
                    charlieReceivesMessage()
                }
            }

            alice.sendPrivateMessage(
                TestConstants.testMessage1,
                to: charlie.peerID,
                recipientNickname: TestConstants.testNickname3
            )
        }
    }

    @Test func privateMessageThroughput() async {
        alice.simulateConnection(with: bob)

        let messageCount = 100
        var receivedCount = 0

        await confirmation("All private messages received") { receivePrivateMessage in
            bob.messageDeliveryHandler = { message in
                if message.isPrivate && message.sender == TestConstants.testNickname1 {
                    receivedCount += 1
                    if receivedCount == messageCount {
                        receivePrivateMessage()
                    }
                }
            }

            for i in 0..<messageCount {
                alice.sendPrivateMessage(
                    "Private message \(i)",
                    to: bob.peerID,
                    recipientNickname: TestConstants.testNickname2
                )
            }
        }
    }

    @Test func largePrivateMessage() async {
        alice.simulateConnection(with: bob)

        await confirmation("Large private message received") { receiveLargeMessage in
            bob.messageDeliveryHandler = { message in
                if message.content == TestConstants.testLongMessage && message.isPrivate {
                    receiveLargeMessage()
                }
            }

            alice.sendPrivateMessage(
                TestConstants.testLongMessage,
                to: bob.peerID,
                recipientNickname: TestConstants.testNickname2
            )
        }
    }
}
