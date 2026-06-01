import Testing
import Foundation
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

struct ChatViewModelTorTests {

    @Test @MainActor
    func handleTorWillStart_whenEnforced_setsAnnouncedFlag() async {
        let (viewModel, _) = makeTestableViewModel()

        #expect(!viewModel.torStatusAnnounced)

        viewModel.handleTorWillStart()

        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(viewModel.torStatusAnnounced)
    }

    @Test @MainActor
    func handleTorWillStart_whenAlreadyAnnounced_doesNotDuplicate() async {
        let (viewModel, _) = makeTestableViewModel()

        viewModel.torStatusAnnounced = true

        viewModel.switchLocationChannel(to: .location(GeohashChannel(level: .city, geohash: "u4pruydq")))
        try? await Task.sleep(nanoseconds: 100_000_000)

        let initialMessageCount = viewModel.messages.count

        viewModel.handleTorWillStart()
        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(viewModel.messages.count == initialMessageCount)
    }

    @Test @MainActor
    func handleTorWillRestart_setsPendingFlag() async {
        let (viewModel, _) = makeTestableViewModel()

        #expect(!viewModel.torRestartPending)

        viewModel.handleTorWillRestart()
        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(viewModel.torRestartPending)
    }

    @Test @MainActor
    func handleTorWillRestart_setsFlag_regardlessOfChannel() async {
        let (viewModel, _) = makeTestableViewModel()

        viewModel.handleTorWillRestart()
        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(viewModel.torRestartPending)
    }

    @Test @MainActor
    func handleTorDidBecomeReady_afterRestart_clearsPendingFlag() async {
        let (viewModel, _) = makeTestableViewModel()

        viewModel.torRestartPending = true

        viewModel.handleTorDidBecomeReady()
        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(!viewModel.torRestartPending)
    }

    @Test @MainActor
    func handleTorDidBecomeReady_initialStart_setsAnnouncedFlag() async {
        let (viewModel, _) = makeTestableViewModel()

        viewModel.torRestartPending = false
        viewModel.torInitialReadyAnnounced = false

        viewModel.handleTorDidBecomeReady()
        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(viewModel.torInitialReadyAnnounced)
    }

    @Test @MainActor
    func handleTorDidBecomeReady_alreadyAnnounced_noDuplicate() async {
        let (viewModel, _) = makeTestableViewModel()

        viewModel.torRestartPending = false
        viewModel.torInitialReadyAnnounced = true
        viewModel.switchLocationChannel(to: .location(GeohashChannel(level: .city, geohash: "u4pruydq")))
        try? await Task.sleep(nanoseconds: 100_000_000)

        let initialMessageCount = viewModel.messages.count

        viewModel.handleTorDidBecomeReady()
        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(viewModel.messages.count == initialMessageCount)
    }

    @Test @MainActor
    func handleTorPreferenceChanged_resetsAllFlags() async {
        let (viewModel, _) = makeTestableViewModel()

        viewModel.torStatusAnnounced = true
        viewModel.torInitialReadyAnnounced = true
        viewModel.torRestartPending = true

        viewModel.handleTorPreferenceChanged(Notification(name: .init("test")))
        try? await Task.sleep(nanoseconds: 100_000_000)

        #expect(!viewModel.torStatusAnnounced)
        #expect(!viewModel.torInitialReadyAnnounced)
        #expect(!viewModel.torRestartPending)
    }
}
