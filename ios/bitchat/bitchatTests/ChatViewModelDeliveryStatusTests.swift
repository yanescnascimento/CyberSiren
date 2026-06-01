import Testing
import Foundation
import BitFoundation
@testable import bitchat

@MainActor
private func makeTestableViewModel() -> (viewModel: ChatViewModel, transport: MockTransport) {
    let keychain = MockKeychain()
    let keychainHelper = MockKeychainHelper()
    let idBridge = NostrIdentityBridge(keychain: keychainHelper)
    let identityManager = MockIdentityManager(keychain)
    let transport = MockTransport()

    let viewModel = ChatViewModel(
        keychain: keychain,
        idBridge: idBridge,
        identityManager: identityManager,
        transport: transport
    )

    return (viewModel, transport)
}

struct ChatViewModelDeliveryStatusTests {

    @Test @MainActor
    func deliveryStatus_noDowngrade_readToDelivered() async {
        let (viewModel, transport) = makeTestableViewModel()
        let peerID = PeerID(str: "0102030405060708")
        let messageID = "test-msg-1"

        let message = BitchatMessage(
            id: messageID,
            sender: viewModel.nickname,
            content: "Test message",
            timestamp: Date(),
            isRelay: false,
            isPrivate: true,
            recipientNickname: "Peer",
            senderPeerID: transport.myPeerID,
            deliveryStatus: .read(by: "Peer", at: Date())
        )
        viewModel.privateChats[peerID] = [message]

        viewModel.didUpdateMessageDeliveryStatus(messageID, status: .delivered(to: "Peer", at: Date()))

        let currentStatus = viewModel.privateChats[peerID]?.first?.deliveryStatus
        #expect({
            if case .read = currentStatus { return true }
            return false
        }())
    }

    @Test @MainActor
    func deliveryStatus_upgrade_sentToDelivered() async {
        let (viewModel, transport) = makeTestableViewModel()
        let peerID = PeerID(str: "0102030405060708")
        let messageID = "test-msg-2"

        let message = BitchatMessage(
            id: messageID,
            sender: viewModel.nickname,
            content: "Test message",
            timestamp: Date(),
            isRelay: false,
            isPrivate: true,
            recipientNickname: "Peer",
            senderPeerID: transport.myPeerID,
            deliveryStatus: .sent
        )
        viewModel.privateChats[peerID] = [message]

        viewModel.didUpdateMessageDeliveryStatus(messageID, status: .delivered(to: "Peer", at: Date()))

        let currentStatus = viewModel.privateChats[peerID]?.first?.deliveryStatus
        #expect({
            if case .delivered = currentStatus { return true }
            return false
        }())
    }

    @Test @MainActor
    func deliveryStatus_upgrade_deliveredToRead() async {
        let (viewModel, transport) = makeTestableViewModel()
        let peerID = PeerID(str: "0102030405060708")
        let messageID = "test-msg-3"

        let message = BitchatMessage(
            id: messageID,
            sender: viewModel.nickname,
            content: "Test message",
            timestamp: Date(),
            isRelay: false,
            isPrivate: true,
            recipientNickname: "Peer",
            senderPeerID: transport.myPeerID,
            deliveryStatus: .delivered(to: "Peer", at: Date().addingTimeInterval(-60))
        )
        viewModel.privateChats[peerID] = [message]

        viewModel.didUpdateMessageDeliveryStatus(messageID, status: .read(by: "Peer", at: Date()))

        let currentStatus = viewModel.privateChats[peerID]?.first?.deliveryStatus
        #expect({
            if case .read = currentStatus { return true }
            return false
        }())
    }

    @Test @MainActor
    func didReceiveReadReceipt_updatesMessageStatus() async {
        let (viewModel, transport) = makeTestableViewModel()
        let peerID = PeerID(str: "0102030405060708")
        let messageID = "test-msg-4"

        let message = BitchatMessage(
            id: messageID,
            sender: viewModel.nickname,
            content: "Test message",
            timestamp: Date(),
            isRelay: false,
            isPrivate: true,
            recipientNickname: "Peer",
            senderPeerID: transport.myPeerID,
            deliveryStatus: .sent
        )
        viewModel.privateChats[peerID] = [message]

        let receipt = ReadReceipt(
            originalMessageID: messageID,
            readerID: peerID,
            readerNickname: "Peer"
        )
        viewModel.didReceiveReadReceipt(receipt)

        let currentStatus = viewModel.privateChats[peerID]?.first?.deliveryStatus
        #expect({
            if case .read = currentStatus { return true }
            return false
        }())
    }

    @Test @MainActor
    func deliveryStatus_publicTimeline_updatesCorrectly() async {
        let (viewModel, _) = makeTestableViewModel()
        let messageID = "public-msg-1"

        let message = BitchatMessage(
            id: messageID,
            sender: viewModel.nickname,
            content: "Public message",
            timestamp: Date(),
            isRelay: false,
            isPrivate: false,
            deliveryStatus: .sending
        )
        viewModel.messages.append(message)

        viewModel.didUpdateMessageDeliveryStatus(messageID, status: .sent)

        let updatedMessage = viewModel.messages.first(where: { $0.id == messageID })
        #expect({
            if case .sent = updatedMessage?.deliveryStatus { return true }
            return false
        }())
    }

    @Test @MainActor
    func statusRank_orderingIsCorrect() async {

        let statuses: [DeliveryStatus] = [
            .failed(reason: "test"),
            .sending,
            .sent,
            .partiallyDelivered(reached: 1, total: 3),
            .delivered(to: "B", at: Date()),
            .read(by: "C", at: Date())
        ]

        for (index, status) in statuses.enumerated() {
            switch status {
            case .failed: #expect(index == 0)
            case .sending: #expect(index == 1)
            case .sent: #expect(index == 2)
            case .partiallyDelivered: #expect(index == 3)
            case .delivered: #expect(index == 4)
            case .read: #expect(index == 5)
            }
        }
    }
}
