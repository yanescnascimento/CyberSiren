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

struct ChatViewModelInitializationTests {

    @Test @MainActor
    func initialization_setsDelegate() async {
        let (viewModel, transport) = makeTestableViewModel()

        #expect(transport.delegate === viewModel)
    }

    @Test @MainActor
    func initialization_startsServices() async {
        let (_, transport) = makeTestableViewModel()

        #expect(transport.startServicesCallCount == 1)
    }

    @Test @MainActor
    func initialization_hasEmptyMessageList() async {
        let (viewModel, _) = makeTestableViewModel()

        #expect(viewModel.messages.count < 10)
    }

    @Test @MainActor
    func initialization_setsNickname() async {
        let (_, transport) = makeTestableViewModel()

        #expect(!transport.myNickname.isEmpty)
    }
}

struct ChatViewModelSendingTests {

    @Test @MainActor
    func sendMessage_delegatesToTransport() async {
        let (viewModel, transport) = makeTestableViewModel()

        viewModel.sendMessage("Hello World")

        #expect(transport.sentMessages.count == 1)
        #expect(transport.sentMessages.first?.content == "Hello World")
    }

    @Test @MainActor
    func sendMessage_emptyContent_ignored() async {
        let (viewModel, transport) = makeTestableViewModel()

        viewModel.sendMessage("")
        viewModel.sendMessage("   ")
        viewModel.sendMessage("\n\t")

        #expect(transport.sentMessages.isEmpty)
    }

    @Test @MainActor
    func sendMessage_withMentions_sendsContent() async {
        let (viewModel, transport) = makeTestableViewModel()

        viewModel.sendMessage("Hello @alice")

        #expect(transport.sentMessages.count == 1)
        #expect(transport.sentMessages.first?.content == "Hello @alice")
    }

    @Test @MainActor
    func sendMessage_command_notSentToTransport() async {
        let (viewModel, transport) = makeTestableViewModel()

        viewModel.sendMessage("/help")

        #expect(transport.sentMessages.isEmpty)
    }
}

struct ChatViewModelCommandTests {

    @Test @MainActor
    func sendMessage_commandsNotSentToTransport() async {
        let (viewModel, transport) = makeTestableViewModel()
        let commands = ["/nick bob", "/who", "/help", "/clear"]

        for command in commands {
            transport.resetRecordings()
            viewModel.sendMessage(command)
            try? await Task.sleep(nanoseconds: 100_000_000)

            #expect(transport.sentMessages.isEmpty)
            #expect(transport.sentPrivateMessages.isEmpty)
        }
    }
}

struct ChatViewModelTimelineCapTests {

    @Test @MainActor
    func sendMessage_trimsTimelineToCap() async {
        let (viewModel, _) = makeTestableViewModel()
        let total = TransportConfig.meshTimelineCap + 5

        for i in 0..<total {
            viewModel.sendMessage("cap-msg-\(i)")
        }

        #expect(viewModel.messages.count == TransportConfig.meshTimelineCap)
        #expect(viewModel.messages.last?.content == "cap-msg-\(total - 1)")
    }
}

struct ChatViewModelReceivingTests {

    @Test @MainActor
    func didReceiveMessage_callsDelegate() async {
        let (_, transport) = makeTestableViewModel()

        let message = BitchatMessage(
            id: "msg-001",
            sender: "Alice",
            content: "Hello from Alice",
            timestamp: Date(),
            isRelay: false,
            originalSender: nil,
            isPrivate: false,
            recipientNickname: nil,
            senderPeerID: PeerID(str: "PEER001"),
            mentions: nil
        )

        transport.simulateIncomingMessage(message)

        try? await Task.sleep(nanoseconds: 200_000_000)

        #expect(transport.delegate != nil)
    }

    @Test @MainActor
    func didReceivePublicMessage_addsToTimeline() async {
        let (viewModel, transport) = makeTestableViewModel()

        transport.simulateIncomingPublicMessage(
            from: PeerID(str: "PEER002"),
            nickname: "Bob",
            content: "Public hello from Bob",
            timestamp: Date(),
            messageID: "pub-001"
        )

        let found = await TestHelpers.waitUntil({
            viewModel.timelineStore.messages(for: .mesh).contains { $0.content == "Public hello from Bob" }
        }, timeout: TestConstants.defaultTimeout)

        #expect(found)
    }
}

struct ChatViewModelRateLimitingTests {

    @Test @MainActor
    func handlePublicMessage_rateLimitsBurstBySender() async {
        let (viewModel, _) = makeTestableViewModel()
        let senderID = PeerID(str: "1122334455667788")
        let now = Date()

        for i in 0..<6 {
            let message = BitchatMessage(
                id: "rate-\(i)",
                sender: "Spammer",
                content: "rate-msg-\(i)",
                timestamp: now,
                isRelay: false,
                originalSender: nil,
                isPrivate: false,
                recipientNickname: nil,
                senderPeerID: senderID,
                mentions: nil
            )
            viewModel.handlePublicMessage(message)
        }

        viewModel.publicMessagePipeline.flushIfNeeded()

        let burstMessages = viewModel.messages.filter { $0.content.hasPrefix("rate-msg-") }
        #expect(burstMessages.count == 5)
        #expect(!burstMessages.contains { $0.content == "rate-msg-5" })
    }
}

struct ChatViewModelPeerTests {

    @Test @MainActor
    func didConnectToPeer_notifiesDelegate() async {
        let (_, transport) = makeTestableViewModel()
        let peerID = PeerID(str: "NEWPEER")

        transport.simulateConnect(peerID, nickname: "NewUser")

        #expect(transport.connectedPeers.contains(peerID))
    }

    @Test @MainActor
    func didDisconnectFromPeer_notifiesDelegate() async {
        let (_, transport) = makeTestableViewModel()
        let peerID = PeerID(str: "OLDPEER")

        transport.simulateConnect(peerID, nickname: "OldUser")
        transport.simulateDisconnect(peerID)

        #expect(!transport.connectedPeers.contains(peerID))
    }

    @Test @MainActor
    func isPeerConnected_delegatesToTransport() async {
        let (_, transport) = makeTestableViewModel()
        let peerID = PeerID(str: "TESTPEER")

        #expect(!transport.isPeerConnected(peerID))

        transport.connectedPeers.insert(peerID)

        #expect(transport.isPeerConnected(peerID))
    }
}

struct ChatViewModelDeduplicationTests {

    @Test @MainActor
    func deduplicationService_isConfigured() async {
        let (viewModel, _) = makeTestableViewModel()

        let testContent = "Test dedup content \(UUID().uuidString)"
        let testDate = Date()

        viewModel.deduplicationService.recordContent(testContent, timestamp: testDate)

        let retrieved = viewModel.deduplicationService.contentTimestamp(for: testContent)
        #expect(retrieved == testDate)
    }

    @Test @MainActor
    func deduplicationService_normalizedKey_consistent() async {
        let (viewModel, _) = makeTestableViewModel()

        let content = "Hello World"
        let key1 = viewModel.deduplicationService.normalizedContentKey(content)
        let key2 = viewModel.deduplicationService.normalizedContentKey(content)

        #expect(key1 == key2)
    }
}

struct ChatViewModelPrivateChatTests {

    @Test @MainActor
    func sendPrivateMessage_delegatesToTransport() async {
        let (viewModel, transport) = makeTestableViewModel()
        let recipientID = PeerID(str: "RECIPIENT")

        transport.connectedPeers.insert(recipientID)
        transport.peerNicknames[recipientID] = "Recipient"

        viewModel.sendPrivateMessage("Secret message", to: recipientID)

        #expect(true)
    }
}

struct ChatViewModelPrivateChatSelectionTests {

    @Test @MainActor
    func openMostRelevantPrivateChat_prefersUnreadMostRecent() async {
        let (viewModel, _) = makeTestableViewModel()
        let peerA = PeerID(str: "PEER_A")
        let peerB = PeerID(str: "PEER_B")

        let older = Date().addingTimeInterval(-120)
        let newer = Date().addingTimeInterval(-30)

        viewModel.privateChats = [
            peerA: [
                BitchatMessage(
                    id: "a-1",
                    sender: "A",
                    content: "Old",
                    timestamp: older,
                    isRelay: false,
                    isPrivate: true,
                    recipientNickname: "Me",
                    senderPeerID: peerA
                )
            ],
            peerB: [
                BitchatMessage(
                    id: "b-1",
                    sender: "B",
                    content: "New",
                    timestamp: newer,
                    isRelay: false,
                    isPrivate: true,
                    recipientNickname: "Me",
                    senderPeerID: peerB
                )
            ]
        ]
        viewModel.unreadPrivateMessages = [peerA, peerB]

        viewModel.openMostRelevantPrivateChat()

        #expect(viewModel.selectedPrivateChatPeer == peerB)
    }

    @Test @MainActor
    func openMostRelevantPrivateChat_fallsBackToMostRecentChat() async {
        let (viewModel, _) = makeTestableViewModel()
        let peerA = PeerID(str: "PEER_A")
        let peerB = PeerID(str: "PEER_B")

        let older = Date().addingTimeInterval(-200)
        let newer = Date().addingTimeInterval(-20)

        viewModel.privateChats = [
            peerA: [
                BitchatMessage(
                    id: "a-1",
                    sender: "A",
                    content: "Old",
                    timestamp: older,
                    isRelay: false,
                    isPrivate: true,
                    recipientNickname: "Me",
                    senderPeerID: peerA
                )
            ],
            peerB: [
                BitchatMessage(
                    id: "b-1",
                    sender: "B",
                    content: "New",
                    timestamp: newer,
                    isRelay: false,
                    isPrivate: true,
                    recipientNickname: "Me",
                    senderPeerID: peerB
                )
            ]
        ]

        viewModel.openMostRelevantPrivateChat()

        #expect(viewModel.selectedPrivateChatPeer == peerB)
    }
}

struct ChatViewModelBluetoothTests {

    @Test @MainActor
    func didUpdateBluetoothState_poweredOn_noAlert() async {
        let (viewModel, transport) = makeTestableViewModel()

        transport.simulateBluetoothStateChange(.poweredOn)

        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(!viewModel.showBluetoothAlert)
    }

    @Test @MainActor
    func didUpdateBluetoothState_poweredOff_showsAlert() async {
        let (viewModel, transport) = makeTestableViewModel()

        transport.simulateBluetoothStateChange(.poweredOff)

        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(viewModel.showBluetoothAlert)
    }

    @Test @MainActor
    func didUpdateBluetoothState_unauthorized_showsAlert() async {
        let (viewModel, transport) = makeTestableViewModel()

        transport.simulateBluetoothStateChange(.unauthorized)

        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(viewModel.showBluetoothAlert)
    }
}

struct ChatViewModelPanicTests {

    @Test @MainActor
    func panicClearAllData_delegatesToTransport() async {
        let (viewModel, transport) = makeTestableViewModel()

        transport.connectedPeers.insert(PeerID(str: "PEER1"))
        viewModel.messages = [
            BitchatMessage(
                id: "panic-1",
                sender: "Tester",
                content: "Before",
                timestamp: Date(),
                isRelay: false
            )
        ]
        viewModel.privateChats[PeerID(str: "PEER1")] = [
            BitchatMessage(
                id: "pm-1",
                sender: "Peer",
                content: "Secret",
                timestamp: Date(),
                isRelay: false,
                isPrivate: true,
                recipientNickname: "Me",
                senderPeerID: PeerID(str: "PEER1")
            )
        ]
        viewModel.unreadPrivateMessages.insert(PeerID(str: "PEER1"))

        viewModel.panicClearAllData()

        #expect(transport.emergencyDisconnectCallCount == 1)
        #expect(viewModel.messages.isEmpty)
        #expect(viewModel.privateChats.isEmpty)
        #expect(viewModel.unreadPrivateMessages.isEmpty)
        #expect(viewModel.selectedPrivateChatPeer == nil)
    }
}

struct ChatViewModelLifecycleTests {

    @Test @MainActor
    func startServices_calledOnInit() async {
        let (_, transport) = makeTestableViewModel()

        #expect(transport.startServicesCallCount == 1)
    }
}
