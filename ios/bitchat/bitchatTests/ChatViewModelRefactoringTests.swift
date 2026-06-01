import Testing
import Foundation
import BitFoundation
@testable import bitchat

struct ChatViewModelRefactoringTests {

    @MainActor
    private func makePinnedViewModel() -> (viewModel: ChatViewModel, transport: MockTransport, identity: MockIdentityManager) {
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

        return (viewModel, transport, identityManager)
    }

    @Test @MainActor
    func command_msg_routesToTransport() async throws {
        let (viewModel, transport, _) = makePinnedViewModel()

        let peerID = PeerID(str: "0000000000000001")
        transport.simulateConnect(peerID, nickname: "alice")

        let didResolve = await TestHelpers.waitUntil({ viewModel.getPeerIDForNickname("alice") != nil },
                                                     timeout: TestConstants.shortTimeout)
        #expect(didResolve)

        viewModel.sendMessage("/msg @alice Hello Private World")

        let didSend = await TestHelpers.waitUntil({ transport.sentPrivateMessages.count == 1 },
                                                  timeout: TestConstants.shortTimeout)
        #expect(didSend)

        #expect(transport.sentMessages.isEmpty, "Command should not be sent as public message")

        #expect(transport.sentPrivateMessages.count == 1)
        #expect(transport.sentPrivateMessages.first?.content == "Hello Private World")
        #expect(transport.sentPrivateMessages.first?.peerID == peerID)
    }

    @Test @MainActor
    func command_block_updatesIdentity() async throws {
        let (viewModel, transport, identity) = makePinnedViewModel()

        let peerID = PeerID(str: "0000000000000002")

        transport.peerFingerprints[peerID] = "fingerprint_123"
        transport.simulateConnect(peerID, nickname: "troll")

        let didResolve = await TestHelpers.waitUntil({ viewModel.getPeerIDForNickname("troll") != nil },
                                                     timeout: TestConstants.shortTimeout)
        #expect(didResolve)

        viewModel.sendMessage("/block @troll")

        let didBlock = await TestHelpers.waitUntil({ identity.isBlocked(fingerprint: "fingerprint_123") },
                                                   timeout: TestConstants.shortTimeout)
        #expect(didBlock)
    }

    @Test @MainActor
    func routing_incomingPrivateMessage_addsToPrivateChats() async {
        let (viewModel, _, _) = makePinnedViewModel()
        let senderID = PeerID(str: "sender_1")

        let message = BitchatMessage(
            id: "msg_1",
            sender: "bob",
            content: "Secret",
            timestamp: Date(),
            isRelay: false,
            originalSender: nil,
            isPrivate: true,
            recipientNickname: "me",
            senderPeerID: senderID,
            mentions: nil
        )

        viewModel.didReceiveMessage(message)

        let found = await TestHelpers.waitUntil(
            { viewModel.privateChats[senderID]?.first?.content == "Secret" },
            timeout: TestConstants.defaultTimeout
        )

        #expect(found)
    }

    @Test @MainActor
    func routing_incomingPublicMessage_addsToPublicTimeline() async {
        let (viewModel, _, _) = makePinnedViewModel()
        let senderID = PeerID(str: "sender_2")

        viewModel.didReceivePublicMessage(
            from: senderID,
            nickname: "charlie",
            content: "Public Hi",
            timestamp: Date(),
            messageID: "msg_2"
        )

        let found = await TestHelpers.waitUntil(
            {
                viewModel.timelineStore.messages(for: .mesh).contains(where: { $0.content == "Public Hi" })
            },
            timeout: TestConstants.defaultTimeout
        )

        #expect(found)
    }
}
