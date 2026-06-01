import Foundation
import CoreBluetooth
@testable import BitFoundation
@testable import bitchat

final class MockBLEService: NSObject {
    private let bus: MockBLEBus

    weak var delegate: BitchatDelegate?
    var myPeerID = PeerID(str: "MOCK1234")
    var myNickname: String = "MockUser"

    private let mockKeychain = MockKeychain()

    var sentMessages: [(message: BitchatMessage, packet: BitchatPacket)] = []
    var sentPackets: [BitchatPacket] = []
    var connectedPeers: Set<PeerID> = []
    var messageDeliveryHandler: ((BitchatMessage) -> Void)?
    var packetDeliveryHandler: ((BitchatPacket) -> Void)?

    var mockNickname: String {
        get { return myNickname }
        set { myNickname = newValue }
    }

    var nickname: String {
        return myNickname
    }

    var peerID: PeerID {
        return myPeerID
    }

    init(bus: MockBLEBus) {
        self.bus = bus
    }

    func setNickname(_ nickname: String) {
        self.myNickname = nickname
    }

    private func registerIfNeeded() {
        bus.register(self, for: myPeerID)
    }

    private func neighbors() -> [MockBLEService] {
        bus.neighbors(of: myPeerID)
    }

    func startServices() {

    }

    func stopServices() {

    }

    func isPeerConnected(_ peerID: PeerID) -> Bool {
        return connectedPeers.contains(peerID)
    }

    func peerNickname(peerID: String) -> String? {
        "MockPeer_\(peerID)"
    }

    func getPeerNicknames() -> [PeerID: String] {
        var nicknames: [PeerID: String] = [:]
        for peer in connectedPeers {
            nicknames[peer] = "MockPeer_\(peer)"
        }
        return nicknames
    }

    func getPeers() -> [PeerID: String] {
        return getPeerNicknames()
    }

    private func deliverLocalEcho(_ message: BitchatMessage) {
        delegate?.didReceiveMessage(message)
    }

    func sendMessage(_ content: String, mentions: [String] = [], to recipientID: String? = nil, messageID: String? = nil, timestamp: Date? = nil) {
        let message = BitchatMessage(
            id: messageID ?? UUID().uuidString,
            sender: myNickname,
            content: content,
            timestamp: timestamp ?? Date(),
            isRelay: false,
            originalSender: nil,
            isPrivate: recipientID != nil,
            recipientNickname: nil,
            senderPeerID: myPeerID,
            mentions: mentions.isEmpty ? nil : mentions
        )

        if let payload = message.toBinaryPayload() {
            let packet = BitchatPacket(
                type: 0x01,
                senderID: myPeerID.id.data(using: .utf8)!,
                recipientID: recipientID?.data(using: .utf8),
                timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                payload: payload,
                signature: nil,
                ttl: 3
            )

            sentMessages.append((message, packet))
            sentPackets.append(packet)

            deliverLocalEcho(message)

            packetDeliveryHandler?(packet)

            if recipientID == nil {
                for neighbor in neighbors() {
                    neighbor.simulateIncomingPacket(packet)
                }
            }
        }
    }

    func sendFileBroadcast(_ packet: BitchatFilePacket, transferId: String) {

    }

    func sendFilePrivate(_ packet: BitchatFilePacket, to peerID: PeerID, transferId: String) {

    }

    func sendPrivateMessage(_ content: String, to recipientPeerID: PeerID, recipientNickname: String, messageID: String) {
        let message = BitchatMessage(
            id: messageID,
            sender: myNickname,
            content: content,
            timestamp: Date(),
            isRelay: false,
            originalSender: nil,
            isPrivate: true,
            recipientNickname: recipientNickname,
            senderPeerID: myPeerID,
            mentions: nil
        )

        if let payload = message.toBinaryPayload() {
            let packet = BitchatPacket(
                type: 0x01,
                senderID: myPeerID.id.data(using: .utf8)!,
                recipientID: recipientPeerID.id.data(using: .utf8)!,
                timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                payload: payload,
                signature: nil,
                ttl: 3
            )

            sentMessages.append((message, packet))
            sentPackets.append(packet)

            deliverLocalEcho(message)

            packetDeliveryHandler?(packet)

            if bus.isDirectNeighbor(myPeerID, recipientPeerID),
               let target = bus.service(for: recipientPeerID) {
                target.simulateIncomingPacket(packet)
            } else {

                for neighbor in neighbors() where neighbor.peerID != recipientPeerID {
                    neighbor.simulateIncomingPacket(packet)
                }
            }
        }
    }

    func sendFavoriteNotification(to peerID: String, isFavorite: Bool) {

    }

    func sendReadReceipt(_ receipt: ReadReceipt, to peerID: String) {

    }

    func sendBroadcastAnnounce() {

    }

    func getPeerFingerprint(_ peerID: String) -> String? {
        return nil
    }

    func getNoiseSessionState(for peerID: String) -> LazyHandshakeState {
        return .none
    }

    func triggerHandshake(with peerID: String) {

    }

    func emergencyDisconnectAll() {
        connectedPeers.removeAll()
        delegate?.didUpdatePeerList([])
    }

    func getNoiseService() -> NoiseEncryptionService {
        return NoiseEncryptionService(keychain: mockKeychain)
    }

    func getFingerprint(for peerID: String) -> String? {
        return nil
    }

    func simulateConnectedPeer(_ peerID: PeerID) {
        registerIfNeeded()
        bus.connect(myPeerID, peerID)
        connectedPeers.insert(peerID)
        delegate?.didConnectToPeer(peerID)
        delegate?.didUpdatePeerList(Array(connectedPeers))
    }

    func simulateDisconnectedPeer(_ peerID: PeerID) {
        bus.disconnect(myPeerID, peerID)
        connectedPeers.remove(peerID)
        delegate?.didDisconnectFromPeer(peerID)
        delegate?.didUpdatePeerList(Array(connectedPeers))
    }

    func simulateIncomingMessage(_ message: BitchatMessage) {
        delegate?.didReceiveMessage(message)

        messageDeliveryHandler?(message)
    }

    private var seenMessageIDs: Set<String> = []
    private let seenLock = NSLock()

    func simulateIncomingPacket(_ packet: BitchatPacket) {

        if let message = BitchatMessage(packet.payload) {
            var shouldDeliver = false
            seenLock.lock()
            if !seenMessageIDs.contains(message.id) {
                seenMessageIDs.insert(message.id)
                shouldDeliver = true
            }
            seenLock.unlock()
            if shouldDeliver {
                delegate?.didReceiveMessage(message)

                messageDeliveryHandler?(message)

                if bus.autoFloodEnabled,
                   packet.recipientID == nil,
                   !message.isPrivate {
                    let nextTTL = packet.ttl > 0 ? packet.ttl - 1 : 0
                    for neighbor in neighbors() {

                        if let sender = message.senderPeerID, sender == neighbor.peerID { continue }
                        var relay = packet
                        relay.ttl = nextTTL
                        neighbor.simulateIncomingPacket(relay)
                    }
                }
            }
        }
        packetDeliveryHandler?(packet)
    }

    func getConnectedPeers() -> [PeerID] {
        return Array(connectedPeers)
    }

    func sendPrivateMessage(_ content: String, to recipientPeerID: PeerID, recipientNickname: String, messageID: String? = nil) {
        sendPrivateMessage(content, to: recipientPeerID, recipientNickname: recipientNickname, messageID: messageID ?? UUID().uuidString)
    }
}

typealias MockSimplifiedBluetoothService = MockBLEService

extension MockBLEService {
    convenience init(peerID: PeerID, nickname: String, bus: MockBLEBus) {
        self.init(bus: bus)
        myPeerID = peerID
        mockNickname = nickname
    }

    func simulateConnection(with otherPeer: MockBLEService) {
        simulateConnectedPeer(otherPeer.myPeerID)
        otherPeer.simulateConnectedPeer(myPeerID)
    }
}
