import BitLogger
import BitFoundation
import Foundation
import SwiftUI
import Combine
import CommonCrypto
import CoreBluetooth
import Tor
#if os(iOS)
import UIKit
#endif
import UniformTypeIdentifiers

final class ChatViewModel: ObservableObject, BitchatDelegate, CommandContextProvider, GeohashParticipantContext, MessageFormattingContext {

    typealias Patterns = MessageFormattingEngine.Patterns

    typealias GeoOutgoingContext = (channel: GeohashChannel, event: NostrEvent, identity: NostrIdentity, teleported: Bool)

    @MainActor
    var canSendMediaInCurrentContext: Bool {
        if let peer = selectedPrivateChatPeer {
            return !(peer.isGeoDM || peer.isGeoChat)
        }
        switch activeChannel {
        case .mesh: return true
        case .location: return false
        }
    }

    private var publicRateLimiter = MessageRateLimiter(
        senderCapacity: TransportConfig.uiSenderRateBucketCapacity,
        senderRefillPerSec: TransportConfig.uiSenderRateBucketRefillPerSec,
        contentCapacity: TransportConfig.uiContentRateBucketCapacity,
        contentRefillPerSec: TransportConfig.uiContentRateBucketRefillPerSec
    )

    @MainActor
    private func normalizedSenderKey(for message: BitchatMessage) -> String {
        if let spid = message.senderPeerID {
            if spid.isGeoChat || spid.isGeoDM {
                let full = (nostrKeyMapping[spid] ?? spid.bare).lowercased()
                return "nostr:" + full
            } else if spid.id.count == 16, let full = getNoiseKeyForShortID(spid)?.id.lowercased() {
                return "noise:" + full
            } else {
                return "mesh:" + spid.id.lowercased()
            }
        }
        return "name:" + message.sender.lowercased()
    }

    @Published var messages: [BitchatMessage] = []
    @Published var currentColorScheme: ColorScheme = .light
    private let maxMessages = TransportConfig.meshTimelineCap
    @Published var isConnected = false
    private var recentlySeenPeers: Set<PeerID> = []
    private var lastNetworkNotificationTime = Date.distantPast
    private var networkResetTimer: Timer? = nil
    private var networkEmptyTimer: Timer? = nil
    private let networkResetGraceSeconds: TimeInterval = TransportConfig.networkResetGraceSeconds
    @Published var nickname: String = "" {
        didSet {

            let trimmed = nickname.trimmedOrNilIfEmpty ?? ""
            if trimmed != nickname {
                nickname = trimmed
                return
            }

            if !meshService.myPeerID.isEmpty {
                meshService.setNickname(nickname)
            }
        }
    }

    let commandProcessor: CommandProcessor
    let messageRouter: MessageRouter
    let privateChatManager: PrivateChatManager
    let unifiedPeerService: UnifiedPeerService
    let autocompleteService: AutocompleteService
    let deduplicationService: MessageDeduplicationService

    @MainActor
    var connectedPeers: Set<PeerID> { unifiedPeerService.connectedPeerIDs }
    @Published var allPeers: [BitchatPeer] = []
    var privateChats: [PeerID: [BitchatMessage]] {
        get { privateChatManager.privateChats }
        set { privateChatManager.privateChats = newValue }
    }
    var selectedPrivateChatPeer: PeerID? {
        get { privateChatManager.selectedPeer }
        set {
            if let peerID = newValue {
                privateChatManager.startChat(with: peerID)
            } else {
                privateChatManager.endChat()
            }
        }
    }
    var unreadPrivateMessages: Set<PeerID> {
        get { privateChatManager.unreadMessages }
        set { privateChatManager.unreadMessages = newValue }
    }

    var hasAnyUnreadMessages: Bool {
        !unreadPrivateMessages.isEmpty
    }

    @MainActor
    func openMostRelevantPrivateChat() {

        let unreadSorted = unreadPrivateMessages
            .map { ($0, privateChats[$0]?.last?.timestamp ?? Date.distantPast) }
            .sorted { $0.1 > $1.1 }
        if let target = unreadSorted.first?.0 {
            startPrivateChat(with: target)
            return
        }

        let recent = privateChats
            .map { (id: $0.key, ts: $0.value.last?.timestamp ?? Date.distantPast) }
            .sorted { $0.ts > $1.ts }
        if let target = recent.first?.id {
            startPrivateChat(with: target)
        }
    }

    var peerIDToPublicKeyFingerprint: [PeerID: String] = [:]
    private var selectedPrivateChatFingerprint: String? = nil

    private var shortIDToNoiseKey: [PeerID: PeerID] = [:]

    @MainActor
    private func getNoiseKeyForShortID(_ shortPeerID: PeerID) -> PeerID? {
        if let mapped = shortIDToNoiseKey[shortPeerID] { return mapped }

        if shortPeerID.id.count == 16,
           let key = meshService.getNoiseService().getPeerPublicKeyData(shortPeerID) {
            let stable = PeerID(hexData: key)
            shortIDToNoiseKey[shortPeerID] = stable
            return stable
        }
        return nil
    }

    @MainActor
    func getShortIDForNoiseKey(_ fullNoiseKeyHex: PeerID) -> PeerID {
        guard fullNoiseKeyHex.id.count == 64 else { return fullNoiseKeyHex }

        if let match = allPeers.first(where: { PeerID(hexData: $0.noisePublicKey) == fullNoiseKeyHex }) {
            return match.peerID
        }

        if let pair = shortIDToNoiseKey.first(where: { $0.value == fullNoiseKeyHex }) {
            return pair.key
        }
        return fullNoiseKeyHex
    }
    private var peerIndex: [PeerID: BitchatPeer] = [:]

    @Published var autocompleteSuggestions: [String] = []
    @Published var showAutocomplete: Bool = false
    @Published var autocompleteRange: NSRange? = nil
    @Published var selectedAutocompleteIndex: Int = 0

    @Published var showPasswordPrompt = false

    let meshService: Transport
    let idBridge: NostrIdentityBridge
    let identityManager: SecureIdentityStateManagerProtocol

    var nostrRelayManager: NostrRelayManager?
    private let userDefaults = UserDefaults.standard
    let keychain: KeychainManagerProtocol
    private let nicknameKey = "bitchat.nickname"

    @Published var activeChannel: ChannelID = .mesh
    var geoSubscriptionID: String? = nil
    var geoDmSubscriptionID: String? = nil
    var currentGeohash: String? = nil
    var cachedGeohashIdentity: (geohash: String, identity: NostrIdentity)? = nil
    var geoNicknames: [String: String] = [:]

    var torStatusAnnounced = false

    var torRestartPending: Bool = false

    var nostrHandlersSetup: Bool = false
    var geoChannelCoordinator: GeoChannelCoordinator?

    private var encryptionStatusCache: [PeerID: EncryptionStatus] = [:]

    @MainActor
    var favoritePeers: Set<String> { unifiedPeerService.favoritePeers }
    @MainActor
    var blockedUsers: Set<String> { unifiedPeerService.blockedUsers }

    @Published var peerEncryptionStatus: [PeerID: EncryptionStatus] = [:]
    @Published var verifiedFingerprints: Set<String> = []
    @Published var showingFingerprintFor: PeerID? = nil

    @Published var showBluetoothAlert = false
    @Published var bluetoothAlertMessage = ""
    @Published var bluetoothState: CBManagerState = .unknown

    @Published var isLocationChannelsSheetPresented: Bool = false
    @Published var isAppInfoPresented: Bool = false
    @Published var showScreenshotPrivacyWarning: Bool = false

    var timelineStore = PublicTimelineStore(
        meshCap: TransportConfig.meshTimelineCap,
        geohashCap: TransportConfig.geoTimelineCap
    )

    var lastPublicActivityAt: [String: Date] = [:]

    let participantTracker = GeohashParticipantTracker(activityCutoff: -TransportConfig.uiRecentCutoffFiveMinutesSeconds)

    @Published var teleportedGeo: Set<String> = []

    var geoSamplingSubs: [String: String] = [:]
    var lastGeoNotificationAt: [String: Date] = [:]

    var cancellables = Set<AnyCancellable>()
    var transferIdToMessageIDs: [String: [String]] = [:]
    var messageIDToTransferId: [String: String] = [:]

    private struct PendingVerification {
        let noiseKeyHex: String
        let signKeyHex: String
        let nonceA: Data
        let startedAt: Date
        var sent: Bool
    }
    private var pendingQRVerifications: [PeerID: PendingVerification] = [:]

    private var lastVerifyNonceByPeer: [PeerID: Data] = [:]

    private var lastInboundVerifyChallengeAt: [String: Date] = [:]

    private var lastMutualToastAt: [String: Date] = [:]

    let publicMessagePipeline: PublicMessagePipeline
    @Published private(set) var isBatchingPublic: Bool = false

    var sentReadReceipts: Set<String> = [] {
        didSet {

            guard oldValue != sentReadReceipts else { return }

            if let data = try? JSONEncoder().encode(Array(sentReadReceipts)) {
                UserDefaults.standard.set(data, forKey: "sentReadReceipts")
            } else {
                SecureLogger.error("Failed to encode read receipts for persistence", category: .session)
            }
        }
    }

    var lastVerifyToastAt: [String: Date] = [:]

    var sentGeoDeliveryAcks: Set<String> = []

    private var isStartupPhase = true

    var torInitialReadyAnnounced: Bool = false

    var nostrKeyMapping: [PeerID: String] = [:]

    @MainActor
    convenience init(
        keychain: KeychainManagerProtocol,
        idBridge: NostrIdentityBridge,
        identityManager: SecureIdentityStateManagerProtocol
    ) {
        self.init(
            keychain: keychain,
            idBridge: idBridge,
            identityManager: identityManager,
            transport: BLEService(keychain: keychain, idBridge: idBridge, identityManager: identityManager)
        )
    }

    @MainActor
    init(
        keychain: KeychainManagerProtocol,
        idBridge: NostrIdentityBridge,
        identityManager: SecureIdentityStateManagerProtocol,
        transport: Transport
    ) {
        self.keychain = keychain
        self.idBridge = idBridge
        self.identityManager = identityManager
        self.meshService = transport
        self.publicMessagePipeline = PublicMessagePipeline()

        if let data = UserDefaults.standard.data(forKey: "sentReadReceipts"),
           let receipts = try? JSONDecoder().decode([String].self, from: data) {
            self.sentReadReceipts = Set(receipts)

        } else {

        }

        self.commandProcessor = CommandProcessor(identityManager: identityManager)
        self.privateChatManager = PrivateChatManager(meshService: meshService)
        self.unifiedPeerService = UnifiedPeerService(meshService: meshService, idBridge: idBridge, identityManager: identityManager)
        let nostrTransport = NostrTransport(keychain: keychain, idBridge: idBridge)
        nostrTransport.senderPeerID = meshService.myPeerID
        self.messageRouter = MessageRouter(transports: [meshService, nostrTransport])

        self.privateChatManager.messageRouter = self.messageRouter

        self.privateChatManager.unifiedPeerService = self.unifiedPeerService

        self.unifiedPeerService.messageRouter = self.messageRouter
        self.autocompleteService = AutocompleteService()
        self.deduplicationService = MessageDeduplicationService()

        self.commandProcessor.contextProvider = self
        self.participantTracker.configure(context: self)

        privateChatManager.objectWillChange
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)

        participantTracker.objectWillChange
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)
        self.commandProcessor.meshService = meshService

        loadNickname()
        loadVerifiedFingerprints()
        meshService.delegate = self

        DispatchQueue.main.asyncAfter(deadline: .now() + TransportConfig.uiStartupInitialDelaySeconds) { [weak self] in
            if let self = self {
                _ = self.getMyFingerprint()
            }
        }

        meshService.setNickname(nickname)

        meshService.startServices()

        publicMessagePipeline.delegate = self
        publicMessagePipeline.updateActiveChannel(activeChannel)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            guard let self = self else { return }
            if let bleService = self.meshService as? BLEService {
                let state = bleService.getCurrentBluetoothState()
                self.updateBluetoothState(state)
            }
        }

        if TorManager.shared.torEnforced && !torStatusAnnounced && TorManager.shared.isAutoStartAllowed() {
            torStatusAnnounced = true
            addGeohashOnlySystemMessage(
                String(localized: "system.tor.starting", comment: "System message when Tor is starting")
            )
        } else if !TorManager.shared.torEnforced && !torStatusAnnounced {
            torStatusAnnounced = true
            addGeohashOnlySystemMessage(
                String(localized: "system.tor.dev_bypass", comment: "System message when Tor bypass is enabled in development")
            )
        }

        nostrRelayManager = NostrRelayManager.shared

        messageRouter.flushAllOutbox()

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(TransportConfig.uiStartupPhaseDurationSeconds * 1_000_000_000))
            self.isStartupPhase = false
        }

        let peersCancellable = unifiedPeerService.$peers
            .receive(on: DispatchQueue.main)
            .sink { [weak self] peers in
                guard let self = self else { return }

                self.allPeers = peers

                var uniquePeers: [PeerID: BitchatPeer] = [:]
                for peer in peers {

                    if uniquePeers[peer.peerID] == nil {
                        uniquePeers[peer.peerID] = peer
                    } else {
                        SecureLogger.warning("Duplicate peer ID detected: \(peer.peerID) (\(peer.displayName))", category: .session)
                    }
                }
                self.peerIndex = uniquePeers

                if self.selectedPrivateChatFingerprint != nil {
                    self.updatePrivateChatPeerIfNeeded()
                }
            }
        self.cancellables.insert(peersCancellable)

        if let relayMgr = self.nostrRelayManager {
            relayMgr.$isConnected
                .receive(on: DispatchQueue.main)
                .sink { [weak self] connected in
                    guard let self = self else { return }
                    if connected {
                        Task { @MainActor in

                            if !self.nostrHandlersSetup {
                                self.setupNostrMessageHandling()
                                self.nostrHandlersSetup = true
                            }
                            self.resubscribeCurrentGeohash()

                            self.geoChannelCoordinator?.refreshSampling()
                        }
                    }
                }
                .store(in: &self.cancellables)
        }

        setupNoiseCallbacks()

        TransferProgressManager.shared.publisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] event in
                self?.handleTransferEvent(event)
            }
            .store(in: &cancellables)

        geoChannelCoordinator = GeoChannelCoordinator(
            onChannelSwitch: { [weak self] channel in
                self?.switchLocationChannel(to: channel)
            },
            beginSampling: { [weak self] geohashes in
                self?.beginGeohashSampling(for: geohashes)
            },
            endSampling: { [weak self] in
                self?.endGeohashSampling()
            }
        )

        LocationChannelManager.shared.$teleported
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isTeleported in
                guard let self = self else { return }
                Task { @MainActor in
                    guard case .location(let ch) = self.activeChannel,
                          let id = try? idBridge.deriveIdentity(forGeohash: ch.geohash) else { return }
                    let key = id.publicKeyHex.lowercased()
                    let hasRegional = !LocationChannelManager.shared.availableChannels.isEmpty
                    let inRegional = LocationChannelManager.shared.availableChannels.contains { $0.geohash == ch.geohash }
                    if isTeleported && hasRegional && !inRegional {
                        self.teleportedGeo = self.teleportedGeo.union([key])
                    } else {
                        self.teleportedGeo.remove(key)
                    }
                }
            }
            .store(in: &cancellables)

        NotificationService.shared.requestAuthorization()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleFavoriteStatusChanged),
            name: .favoriteStatusChanged,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handlePeerStatusUpdate),
            name: Notification.Name("peerStatusUpdated"),
            object: nil
        )

        #if os(macOS)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBecomeActive),
            name: NSApplication.didBecomeActiveNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillResignActive),
            name: NSApplication.willResignActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationWillTerminate),
            name: NSApplication.willTerminateNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTorWillRestart),
            name: .TorWillRestart,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTorDidBecomeReady),
            name: .TorDidBecomeReady,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTorWillStart),
            name: .TorWillStart,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTorPreferenceChanged(_:)),
            name: .TorUserPreferenceChanged,
            object: nil
        )
        #else
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(userDidTakeScreenshot),
            name: UIApplication.userDidTakeScreenshotNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationWillTerminate),
            name: UIApplication.willTerminateNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTorWillRestart),
            name: .TorWillRestart,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTorDidBecomeReady),
            name: .TorDidBecomeReady,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTorWillStart),
            name: .TorWillStart,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleTorPreferenceChanged(_:)),
            name: .TorUserPreferenceChanged,
            object: nil
        )
        #endif
    }

    deinit {

    }

    private func loadNickname() {
        if let savedNickname = userDefaults.string(forKey: nicknameKey) {
            nickname = savedNickname.trimmed
        } else {
            nickname = "anon\(Int.random(in: 1000...9999))"
            saveNickname()
        }
    }

    func saveNickname() {
        userDefaults.set(nickname, forKey: nicknameKey)

        meshService.sendBroadcastAnnounce()
    }

    func validateAndSaveNickname() {
        nickname = nickname.trimmedOrNilIfEmpty ?? "anon\(Int.random(in: 1000...9999))"
        saveNickname()
    }

    @MainActor
    func hasUnreadMessages(for peerID: PeerID) -> Bool {

        if unreadPrivateMessages.contains(peerID) {
            return true
        }

        if let peer = unifiedPeerService.getPeer(by: peerID) {
            let noiseKeyHex = PeerID(hexData: peer.noisePublicKey)
            if unreadPrivateMessages.contains(noiseKeyHex) {
                return true
            }

            if let nostrHex = peer.nostrPublicKey {
                let convKey = PeerID(nostr_: nostrHex)
                if unreadPrivateMessages.contains(convKey) {
                    return true
                }
            }
        }

        let peerNickname = meshService.peerNickname(peerID: peerID)?.lowercased() ?? ""

        for unreadPeerID in unreadPrivateMessages {
            if unreadPeerID.isGeoDM {

                if let messages = privateChats[unreadPeerID],
                   let firstMessage = messages.first,
                   firstMessage.sender.lowercased() == peerNickname {
                    return true
                }
            }
        }

        return false
    }

    @MainActor
    func toggleFavorite(peerID: PeerID) {

        if let noisePublicKey = peerID.noiseKey {

            let ephemeralPeerID = unifiedPeerService.peers.first { peer in
                peer.noisePublicKey == noisePublicKey
            }?.peerID

            if let ephemeralID = ephemeralPeerID {

                unifiedPeerService.toggleFavorite(ephemeralID)

                objectWillChange.send()
            } else {

                let currentStatus = FavoritesPersistenceService.shared.getFavoriteStatus(for: noisePublicKey)
                let wasFavorite = currentStatus?.isFavorite ?? false

                if wasFavorite {

                    FavoritesPersistenceService.shared.removeFavorite(peerNoisePublicKey: noisePublicKey)
                } else {

                    var nickname = currentStatus?.peerNickname

                    if nickname == nil, let messages = privateChats[peerID], !messages.isEmpty {

                        nickname = messages.first { $0.senderPeerID == peerID }?.sender
                    }

                    let finalNickname = nickname ?? "Unknown"
                    let nostrKey = currentStatus?.peerNostrPublicKey ?? idBridge.getNostrPublicKey(for: noisePublicKey)

                    FavoritesPersistenceService.shared.addFavorite(
                        peerNoisePublicKey: noisePublicKey,
                        peerNostrPublicKey: nostrKey,
                        peerNickname: finalNickname
                    )
                }

                objectWillChange.send()

                if !wasFavorite && currentStatus?.theyFavoritedUs == true {

                    sendFavoriteNotificationViaNostr(noisePublicKey: noisePublicKey, isFavorite: true)
                } else if wasFavorite {

                    sendFavoriteNotificationViaNostr(noisePublicKey: noisePublicKey, isFavorite: false)
                }
            }
        } else {

            unifiedPeerService.toggleFavorite(peerID)

            objectWillChange.send()
        }
    }

    @MainActor
    func isFavorite(peerID: PeerID) -> Bool {

        if let noisePublicKey = peerID.noiseKey {

            if let status = FavoritesPersistenceService.shared.getFavoriteStatus(for: noisePublicKey) {
                return status.isFavorite
            }
        } else {

            if let peer = unifiedPeerService.getPeer(by: peerID) {
                return peer.isFavorite
            }
        }

        return false
    }

    @MainActor
    func isPeerBlocked(_ peerID: PeerID) -> Bool {
        return unifiedPeerService.isBlocked(peerID)
    }

    @MainActor
    private func getCurrentPeerIDForFingerprint(_ fingerprint: String) -> PeerID? {

        for peerID in connectedPeers {
            if let mappedFingerprint = peerIDToPublicKeyFingerprint[peerID],
               mappedFingerprint == fingerprint {
                return peerID
            }
        }
        return nil
    }

    @MainActor
    private func updatePrivateChatPeerIfNeeded() {
        guard let chatFingerprint = selectedPrivateChatFingerprint else { return }

        if let currentPeerID = getCurrentPeerIDForFingerprint(chatFingerprint) {

            if let oldPeerID = selectedPrivateChatPeer, oldPeerID != currentPeerID {

                if let oldMessages = privateChats[oldPeerID] {
                    var chats = privateChats
                    if chats[currentPeerID] == nil {
                        chats[currentPeerID] = []
                    }
                    chats[currentPeerID]?.append(contentsOf: oldMessages)

                    chats[currentPeerID]?.sort { $0.timestamp < $1.timestamp }

                    var seen = Set<String>()
                    chats[currentPeerID] = chats[currentPeerID]?.filter { msg in
                        if seen.contains(msg.id) {
                            return false
                        }
                        seen.insert(msg.id)
                        return true
                    }

                    chats.removeValue(forKey: oldPeerID)

                    privateChats = chats
                }

                if unreadPrivateMessages.contains(oldPeerID) {
                    unreadPrivateMessages.remove(oldPeerID)
                    unreadPrivateMessages.insert(currentPeerID)
                }

                selectedPrivateChatPeer = currentPeerID

                Task { @MainActor in

                }
            } else if selectedPrivateChatPeer == nil {

                selectedPrivateChatPeer = currentPeerID

            }

            unreadPrivateMessages.remove(currentPeerID)
        }
    }

    @MainActor
    func sendMessage(_ content: String) {

        guard let trimmed = content.trimmedOrNilIfEmpty else { return }

        if content.hasPrefix("/") {
            Task { @MainActor in
                handleCommand(content)
            }
            return
        }

        if selectedPrivateChatPeer != nil {

            updatePrivateChatPeerIfNeeded()

            if let selectedPeer = selectedPrivateChatPeer {
                sendPrivateMessage(content, to: selectedPeer)
            }
            return
        }

        let mentions = parseMentions(from: content)

        var geoContext: GeoOutgoingContext? = nil

        var displaySender = nickname
        var localSenderPeerID = meshService.myPeerID
        var messageID: String? = nil
        var messageTimestamp = Date()

        switch activeChannel {
        case .mesh:
            break
        case .location(let ch):
            do {
                let identity = try idBridge.deriveIdentity(forGeohash: ch.geohash)
                let suffix = String(identity.publicKeyHex.suffix(4))
                displaySender = nickname + "#" + suffix
                localSenderPeerID = PeerID(nostr: identity.publicKeyHex)
                let teleported = LocationChannelManager.shared.teleported
                let event = try NostrProtocol.createEphemeralGeohashEvent(
                    content: trimmed,
                    geohash: ch.geohash,
                    senderIdentity: identity,
                    nickname: nickname,
                    teleported: teleported
                )
                messageID = event.id
                messageTimestamp = Date(timeIntervalSince1970: TimeInterval(event.created_at))
                geoContext = (channel: ch, event: event, identity: identity, teleported: teleported)
            } catch {
                SecureLogger.error("Failed to prepare geohash message: \(error)", category: .session)
                addSystemMessage(
                    String(localized: "system.location.send_failed", comment: "System message when a location channel send fails")
                )
                return
            }
        }

        let message = BitchatMessage(
            id: messageID,
            sender: displaySender,
            content: trimmed,
            timestamp: messageTimestamp,
            isRelay: false,
            senderPeerID: localSenderPeerID,
            mentions: mentions.isEmpty ? nil : mentions
        )

        timelineStore.append(message, to: activeChannel)
        refreshVisibleMessages(from: activeChannel)

        let ckey = deduplicationService.normalizedContentKey(message.content)
        deduplicationService.recordContentKey(ckey, timestamp: message.timestamp)

        trimMessagesIfNeeded()

        updateChannelActivityTimeThenSend(content: content,
                                          trimmed: trimmed,
                                          mentions: mentions,
                                          geoContext: geoContext,
                                          messageID: message.id,
                                          timestamp: message.timestamp)
    }

    private func updateChannelActivityTimeThenSend(content: String,
                                                   trimmed: String,
                                                   mentions: [String],
                                                   geoContext: GeoOutgoingContext?,
                                                   messageID: String,
                                                   timestamp: Date) {
        switch activeChannel {
        case .mesh:
            lastPublicActivityAt["mesh"] = Date()

            meshService.sendMessage(content, mentions: mentions, messageID: messageID, timestamp: timestamp)
        case .location(let ch):
            lastPublicActivityAt["geo:\(ch.geohash)"] = Date()
            guard let context = geoContext, context.channel.geohash == ch.geohash else {
                SecureLogger.error("Geo: missing send context for \(ch.geohash)", category: .session)
                addSystemMessage(
                    String(localized: "system.location.send_failed", comment: "System message when a location channel send fails")
                )
                return
            }

            Task { @MainActor in
                self.sendGeohash(context: context)
            }
        }
    }

    @MainActor
    func isSelfSender(peerID: PeerID?, displayName: String?) -> Bool {
        guard let peerID else { return false }
        if peerID == meshService.myPeerID { return true }
        guard peerID.isGeoDM || peerID.isGeoChat else { return false }

        if let mapped = nostrKeyMapping[peerID]?.lowercased(),
           let gh = currentGeohash,
           let myIdentity = try? idBridge.deriveIdentity(forGeohash: gh) {
            if mapped == myIdentity.publicKeyHex.lowercased() { return true }
        }

        if let gh = currentGeohash,
           let myIdentity = try? idBridge.deriveIdentity(forGeohash: gh) {
            if peerID == PeerID(nostr: myIdentity.publicKeyHex) { return true }
            let suffix = myIdentity.publicKeyHex.suffix(4)
            let expected = (nickname + "#" + suffix).lowercased()
            if let display = displayName?.lowercased(), display == expected { return true }
        }

        return false
    }

    var geohashPeople: [GeoPerson] {
        participantTracker.visiblePeople
    }

    @MainActor
    func visibleGeohashPeople() -> [GeoPerson] {
        participantTracker.getVisiblePeople()
    }

    func getVisibleGeoParticipants() -> [CommandGeoParticipant] {
        visibleGeohashPeople().map { CommandGeoParticipant(id: $0.id, displayName: $0.displayName) }
    }

    @MainActor
    func geohashParticipantCount(for geohash: String) -> Int {
        participantTracker.participantCount(for: geohash)
    }

    func displayNameForPubkey(_ pubkeyHex: String) -> String {
        displayNameForNostrPubkey(pubkeyHex)
    }

    func isBlocked(_ pubkeyHexLowercased: String) -> Bool {
        identityManager.isNostrBlocked(pubkeyHexLowercased: pubkeyHexLowercased)
    }

    @MainActor
    func isGeohashUserBlocked(pubkeyHexLowercased: String) -> Bool {
        return identityManager.isNostrBlocked(pubkeyHexLowercased: pubkeyHexLowercased)
    }
    @MainActor
    func blockGeohashUser(pubkeyHexLowercased: String, displayName: String) {
        let hex = pubkeyHexLowercased.lowercased()
        identityManager.setNostrBlocked(hex, isBlocked: true)

        participantTracker.removeParticipant(pubkeyHex: hex)

        if let gh = currentGeohash {
            let predicate: (BitchatMessage) -> Bool = { [self] msg in
                guard let spid = msg.senderPeerID, spid.isGeoDM || spid.isGeoChat else { return false }
                if let full = self.nostrKeyMapping[spid]?.lowercased() { return full == hex }
                return false
            }
            timelineStore.removeMessages(in: gh, where: predicate)
            if case .location = activeChannel {
                messages.removeAll(where: predicate)
            }
        }

        let convKey = PeerID(nostr_: hex)
        if privateChats[convKey] != nil {
            privateChats.removeValue(forKey: convKey)
            unreadPrivateMessages.remove(convKey)
        }

        for (key, value) in self.nostrKeyMapping where value.lowercased() == hex {
            self.nostrKeyMapping.removeValue(forKey: key)
        }

        addSystemMessage(
            String(
                format: String(localized: "system.geohash.blocked", comment: "System message shown when a user is blocked in geohash chats"),
                locale: .current,
                displayName
            )
        )
    }
    @MainActor
    func unblockGeohashUser(pubkeyHexLowercased: String, displayName: String) {
        identityManager.setNostrBlocked(pubkeyHexLowercased, isBlocked: false)
        addSystemMessage(
            String(
                format: String(localized: "system.geohash.unblocked", comment: "System message shown when a user is unblocked in geohash chats"),
                locale: .current,
                displayName
            )
        )
    }

    func displayNameForNostrPubkey(_ pubkeyHex: String) -> String {
        let suffix = String(pubkeyHex.suffix(4))

        if let gh = currentGeohash, let myGeoIdentity = try? idBridge.deriveIdentity(forGeohash: gh) {
            if myGeoIdentity.publicKeyHex.lowercased() == pubkeyHex.lowercased() {
                return nickname + "#" + suffix
            }
        }

        if let nick = geoNicknames[pubkeyHex.lowercased()], !nick.isEmpty {
            return nick + "#" + suffix
        }

        return "anon#\(suffix)"
    }

    private enum MediaSendError: Error {
        case encodingFailed
        case tooLarge
        case copyFailed
    }

    func currentPublicSender() -> (name: String, peerID: PeerID) {
        var displaySender = nickname
        var senderPeerID = meshService.myPeerID
        if case .location(let ch) = activeChannel,
           let identity = try? idBridge.deriveIdentity(forGeohash: ch.geohash) {
            let suffix = String(identity.publicKeyHex.suffix(4))
            displaySender = nickname + "#" + suffix
            senderPeerID = PeerID(nostr: identity.publicKeyHex)
        }
        return (displaySender, senderPeerID)
    }

    @MainActor
    func nicknameForPeer(_ peerID: PeerID) -> String {
        if let name = meshService.peerNickname(peerID: peerID) {
            return name
        }
        if let favorite = FavoritesPersistenceService.shared.getFavoriteStatus(forPeerID: peerID),
           !favorite.peerNickname.isEmpty {
            return favorite.peerNickname
        }
        if let noiseKey = Data(hexString: peerID.id),
           let favorite = FavoritesPersistenceService.shared.getFavoriteStatus(for: noiseKey),
           !favorite.peerNickname.isEmpty {
            return favorite.peerNickname
        }
        return "user"
    }

    @MainActor
    func removeMessage(withID messageID: String, cleanupFile: Bool = false) {
        var removedMessage: BitchatMessage?

        if let idx = messages.firstIndex(where: { $0.id == messageID }) {
            removedMessage = messages.remove(at: idx)
        }

        if let storeRemoved = timelineStore.removeMessage(withID: messageID) {
            removedMessage = removedMessage ?? storeRemoved
        }

        var chats = privateChats
        for (peerID, items) in chats {
            let filtered = items.filter { $0.id != messageID }
            if filtered.count != items.count {
                if filtered.isEmpty {
                    chats.removeValue(forKey: peerID)
                } else {
                    chats[peerID] = filtered
                }
                if removedMessage == nil {
                    removedMessage = items.first(where: { $0.id == messageID })
                }
            }
        }
        privateChats = chats

        if cleanupFile, let message = removedMessage {
            cleanupLocalFile(forMessage: message)
        }

        objectWillChange.send()
    }

    @MainActor
    func addLocalPrivateSystemMessage(_ content: String, to peerID: PeerID) {
        let systemMessage = BitchatMessage(
            sender: "system",
            content: content,
            timestamp: Date(),
            isRelay: false,
            originalSender: nil,
            isPrivate: true,
            recipientNickname: meshService.peerNickname(peerID: peerID),
            senderPeerID: meshService.myPeerID
        )
        if privateChats[peerID] == nil { privateChats[peerID] = [] }
        privateChats[peerID]?.append(systemMessage)
        objectWillChange.send()
    }

    @MainActor
    func updateBluetoothState(_ state: CBManagerState) {
        bluetoothState = state

        switch state {
        case .poweredOff:
            bluetoothAlertMessage = String(localized: "content.alert.bluetooth_required.off", comment: "Message shown when Bluetooth is turned off")
            showBluetoothAlert = true
        case .unauthorized:
            bluetoothAlertMessage = String(localized: "content.alert.bluetooth_required.permission", comment: "Message shown when Bluetooth permission is missing")
            showBluetoothAlert = true
        case .unsupported:
            bluetoothAlertMessage = String(localized: "content.alert.bluetooth_required.unsupported", comment: "Message shown when the device lacks Bluetooth support")
            showBluetoothAlert = true
        case .poweredOn:

            showBluetoothAlert = false
            bluetoothAlertMessage = ""
        case .unknown, .resetting:

            showBluetoothAlert = false
        @unknown default:
            showBluetoothAlert = false
        }
    }

    @MainActor
    func startPrivateChat(with peerID: PeerID) {

        if peerID == meshService.myPeerID {
            return
        }

        let peerNickname = meshService.peerNickname(peerID: peerID) ?? "unknown"

        if unifiedPeerService.isBlocked(peerID) {
            addSystemMessage(
                String(
                    format: String(localized: "system.chat.blocked", comment: "System message when starting chat fails because peer is blocked"),
                    locale: .current,
                    peerNickname
                )
            )
            return
        }

        if let peer = unifiedPeerService.getPeer(by: peerID),
           peer.isFavorite && !peer.theyFavoritedUs && !peer.isConnected {
            addSystemMessage(
                String(
                    format: String(localized: "system.chat.requires_favorite", comment: "System message when mutual favorite requirement blocks chat"),
                    locale: .current,
                    peerNickname
                )
            )
            return
        }

        _ = privateChatManager.consolidateMessages(for: peerID, peerNickname: peerNickname, persistedReadReceipts: sentReadReceipts)

        if !peerID.isGeoDM && !peerID.isGeoChat {
            let sessionState = meshService.getNoiseSessionState(for: peerID)
            switch sessionState {
            case .none, .failed:
                meshService.triggerHandshake(with: peerID)
            case .handshakeQueued, .handshaking, .established:
                break
            }
        } else {
            SecureLogger.debug("GeoDM: skipping mesh handshake for virtual peerID=\(peerID)", category: .session)
        }

        privateChatManager.syncReadReceiptsForSentMessages(peerID: peerID, nickname: nickname, externalReceipts: &sentReadReceipts)

        privateChatManager.startChat(with: peerID)

        markPrivateMessagesAsRead(from: peerID)
    }

    func endPrivateChat() {
        selectedPrivateChatPeer = nil
        selectedPrivateChatFingerprint = nil
    }

    @MainActor
    @objc private func handlePeerStatusUpdate(_ notification: Notification) {

        updatePrivateChatPeerIfNeeded()
    }

    @objc private func handleFavoriteStatusChanged(_ notification: Notification) {
        guard let peerPublicKey = notification.userInfo?["peerPublicKey"] as? Data else { return }

        Task { @MainActor in

            if let isKeyUpdate = notification.userInfo?["isKeyUpdate"] as? Bool,
               isKeyUpdate,
               let oldKey = notification.userInfo?["oldPeerPublicKey"] as? Data {
                let oldPeerID = PeerID(hexData: oldKey)
                let newPeerID = PeerID(hexData: peerPublicKey)

                if selectedPrivateChatPeer == oldPeerID {
                    SecureLogger.info("Updating private chat peer ID due to key change: \(oldPeerID) -> \(newPeerID)", category: .session)

                    if let messages = privateChats[oldPeerID] {
                        var chats = privateChats
                        chats[newPeerID] = messages
                        chats.removeValue(forKey: oldPeerID)
                        privateChats = chats
                    }

                    if unreadPrivateMessages.contains(oldPeerID) {
                        unreadPrivateMessages.remove(oldPeerID)
                        unreadPrivateMessages.insert(newPeerID)
                    }

                    selectedPrivateChatPeer = newPeerID

                    if let fingerprint = peerIDToPublicKeyFingerprint[oldPeerID] {
                        peerIDToPublicKeyFingerprint.removeValue(forKey: oldPeerID)
                        peerIDToPublicKeyFingerprint[newPeerID] = fingerprint
                        selectedPrivateChatFingerprint = fingerprint
                    }

                } else {

                    if let messages = privateChats[oldPeerID] {
                        SecureLogger.debug("Migrating private chat messages from \(oldPeerID) to \(newPeerID)", category: .session)
                        var chats = privateChats
                        chats[newPeerID] = messages
                        chats.removeValue(forKey: oldPeerID)
                        privateChats = chats
                    }

                    if unreadPrivateMessages.contains(oldPeerID) {
                        unreadPrivateMessages.remove(oldPeerID)
                        unreadPrivateMessages.insert(newPeerID)
                    }

                    if let fingerprint = peerIDToPublicKeyFingerprint[oldPeerID] {
                        peerIDToPublicKeyFingerprint.removeValue(forKey: oldPeerID)
                        peerIDToPublicKeyFingerprint[newPeerID] = fingerprint
                    }
                }
            }

            updatePrivateChatPeerIfNeeded()

            if let isFavorite = notification.userInfo?["isFavorite"] as? Bool {
                let peerID = PeerID(hexData: peerPublicKey)
                let action = isFavorite ? "favorited" : "unfavorited"

                let peerNickname: String
                if let nickname = meshService.peerNickname(peerID: peerID) {
                    peerNickname = nickname
                } else if let favorite = FavoritesPersistenceService.shared.getFavoriteStatus(for: peerPublicKey) {
                    peerNickname = favorite.peerNickname
                } else {
                    peerNickname = "Unknown"
                }

                let systemMessage = BitchatMessage(
                    id: UUID().uuidString,
                sender: "System",
                content: "\(peerNickname) \(action) you",
                timestamp: Date(),
                isRelay: false,
                originalSender: nil,
                isPrivate: false,
                recipientNickname: nil,
                senderPeerID: nil,
                mentions: nil
            )

            addMessage(systemMessage)

            }
        }
    }

    @MainActor
    @objc private func appDidBecomeActive() {

        if let bleService = meshService as? BLEService {
            let currentState = bleService.getCurrentBluetoothState()
            updateBluetoothState(currentState)
        }

        if let peerID = selectedPrivateChatPeer {

            self.markPrivateMessagesAsRead(from: peerID)

            DispatchQueue.main.asyncAfter(deadline: .now() + TransportConfig.uiAnimationMediumSeconds) {
                self.markPrivateMessagesAsRead(from: peerID)
            }
        }

    }

    @MainActor
    @objc private func userDidTakeScreenshot() {

        if isLocationChannelsSheetPresented {

            showScreenshotPrivacyWarning = true
            return
        }
        if isAppInfoPresented {

            return
        }

        let screenshotMessage = "* \(nickname) took a screenshot *"

        if let peerID = selectedPrivateChatPeer {

            if let peerNickname = meshService.peerNickname(peerID: peerID) {

                let sessionState = meshService.getNoiseSessionState(for: peerID)
                switch sessionState {
                case .established:

                    messageRouter.sendPrivate(screenshotMessage, to: peerID, recipientNickname: peerNickname, messageID: UUID().uuidString)
                case  .none, .failed, .handshakeQueued, .handshaking:

                    SecureLogger.debug("Skipping screenshot notification to \(peerID) - no established session", category: .security)
                }
            }

            let localNotification = BitchatMessage(
                sender: "system",
                content: "you took a screenshot",
                timestamp: Date(),
                isRelay: false,
                originalSender: nil,
                isPrivate: true,
                recipientNickname: meshService.peerNickname(peerID: peerID),
                senderPeerID: meshService.myPeerID
            )
            var chats = privateChats
            if chats[peerID] == nil {
                chats[peerID] = []
            }
            chats[peerID]?.append(localNotification)
            privateChats = chats

        } else {

            switch activeChannel {
            case .mesh:
                meshService.sendMessage(screenshotMessage,
                                        mentions: [],
                                        messageID: UUID().uuidString,
                                        timestamp: Date())
            case .location(let ch):
                Task { @MainActor in
                    do {
                        let identity = try idBridge.deriveIdentity(forGeohash: ch.geohash)
                        let event = try NostrProtocol.createEphemeralGeohashEvent(
                            content: screenshotMessage,
                            geohash: ch.geohash,
                            senderIdentity: identity,
                            nickname: self.nickname,
                            teleported: LocationChannelManager.shared.teleported
                        )
                        let targetRelays = GeoRelayDirectory.shared.closestRelays(toGeohash: ch.geohash, count: 5)
                        if targetRelays.isEmpty {
                            SecureLogger.warning("Geo: no geohash relays available for \(ch.geohash); not sending", category: .session)
                        } else {
                            NostrRelayManager.shared.sendEvent(event, to: targetRelays)
                        }

                        self.participantTracker.recordParticipant(pubkeyHex: identity.publicKeyHex)
                    } catch {
                        SecureLogger.error("Failed to send geohash screenshot message: \(error)", category: .session)
                        self.addSystemMessage(
                            String(localized: "system.location.send_failed", comment: "System message when a location channel send fails")
                        )
                    }
                }
            }

            let localNotification = BitchatMessage(
                sender: "system",
                content: "you took a screenshot",
                timestamp: Date(),
                isRelay: false
            )

            addMessage(localNotification)
        }
    }

    @objc private func appWillResignActive() {

    }

    func saveIdentityState() {

        identityManager.forceSave()

        _ = keychain.verifyIdentityKeyExists()
    }

    @objc func applicationWillTerminate() {

        meshService.stopServices()

        saveIdentityState()
    }

    @MainActor
    private func sendReadReceipt(_ receipt: ReadReceipt, to peerID: PeerID, originalTransport: String? = nil) {

        var actualPeerID = peerID

        if meshService.peerNickname(peerID: peerID) == nil {

            if let oldNoiseKey = Data(hexString: peerID.id),
               let favoriteStatus = FavoritesPersistenceService.shared.getFavoriteStatus(for: oldNoiseKey) {
                let peerNickname = favoriteStatus.peerNickname

                for (currentPeerID, currentNickname) in meshService.getPeerNicknames() {
                    if currentNickname == peerNickname {
                        SecureLogger.info("Resolved updated peer ID for read receipt: \(peerID) -> \(currentPeerID)", category: .session)
                        actualPeerID = currentPeerID
                        break
                    }
                }
            }
        }

        if originalTransport == "nostr" {
            return
        }

        messageRouter.sendReadReceipt(receipt, to: actualPeerID)
    }

    @MainActor
    func markPrivateMessagesAsRead(from peerID: PeerID) {
        privateChatManager.markAsRead(from: peerID)

        if peerID.isGeoDM,
           let recipientHex = nostrKeyMapping[peerID],
           case .location(let ch) = LocationChannelManager.shared.selectedChannel,
           let id = try? idBridge.deriveIdentity(forGeohash: ch.geohash) {
            let messages = privateChats[peerID] ?? []
            for message in messages where message.senderPeerID == peerID && !message.isRelay {
                if !sentReadReceipts.contains(message.id) {
                    SecureLogger.debug("GeoDM: sending READ for mid=\(message.id.prefix(8))… to=\(recipientHex.prefix(8))…", category: .session)
                    let nostrTransport = NostrTransport(keychain: keychain, idBridge: idBridge)
                    nostrTransport.senderPeerID = meshService.myPeerID
                    nostrTransport.sendReadReceiptGeohash(message.id, toRecipientHex: recipientHex, from: id)
                    sentReadReceipts.insert(message.id)
                }
            }
            return
        }

        var noiseKeyHex: PeerID? = nil
        var peerNostrPubkey: String? = nil

        if let noiseKey = Data(hexString: peerID.id),
           let favoriteStatus = FavoritesPersistenceService.shared.getFavoriteStatus(for: noiseKey) {
            noiseKeyHex = peerID
            peerNostrPubkey = favoriteStatus.peerNostrPublicKey
        }

        else if let peer = unifiedPeerService.getPeer(by: peerID) {
            noiseKeyHex = PeerID(hexData: peer.noisePublicKey)
            let favoriteStatus = FavoritesPersistenceService.shared.getFavoriteStatus(for: peer.noisePublicKey)
            peerNostrPubkey = favoriteStatus?.peerNostrPublicKey

            if let keyHex = noiseKeyHex, unreadPrivateMessages.contains(keyHex) {
                unreadPrivateMessages.remove(keyHex)
            }
        }

        if peerNostrPubkey != nil {

            let messagesToAck = getPrivateChatMessages(for: peerID)

            for message in messagesToAck {

                if (message.senderPeerID == peerID || message.senderPeerID == noiseKeyHex) && !message.isRelay {

                    if !sentReadReceipts.contains(message.id) {

                        let recipPeer = peerID.isHex ? peerID : (unifiedPeerService.getPeer(by: peerID)?.peerID ?? peerID)
                        let receipt = ReadReceipt(originalMessageID: message.id, readerID: meshService.myPeerID, readerNickname: nickname)
                        messageRouter.sendReadReceipt(receipt, to: recipPeer)
                        sentReadReceipts.insert(message.id)
                    }
                }
            }
        }
    }

    func getMessages(for peerID: PeerID?) -> [BitchatMessage] {
        if let peerID {
            return getPrivateChatMessages(for: peerID)
        } else {
            return messages
        }
    }

    @MainActor
    func getPrivateChatMessages(for peerID: PeerID) -> [BitchatMessage] {
        var combined: [BitchatMessage] = []

        if let ephemeralMessages = privateChats[peerID] {
            combined.append(contentsOf: ephemeralMessages)
        }

        if let peer = unifiedPeerService.getPeer(by: peerID) {
            let noiseKeyHex = PeerID(hexData: peer.noisePublicKey)
            if noiseKeyHex != peerID, let nostrMessages = privateChats[noiseKeyHex] {
                combined.append(contentsOf: nostrMessages)
            }
        }

        func statusRank(_ s: DeliveryStatus?) -> Int {
            guard let s = s else { return 0 }
            switch s {
            case .failed: return 1
            case .sending: return 2
            case .sent: return 3
            case .partiallyDelivered: return 4
            case .delivered: return 5
            case .read: return 6
            }
        }

        var bestByID: [String: BitchatMessage] = [:]
        for msg in combined {
            if let existing = bestByID[msg.id] {
                let lhs = statusRank(existing.deliveryStatus)
                let rhs = statusRank(msg.deliveryStatus)
                if rhs > lhs || (rhs == lhs && msg.timestamp > existing.timestamp) {
                    bestByID[msg.id] = msg
                }
            } else {
                bestByID[msg.id] = msg
            }
        }

        return bestByID.values.sorted { $0.timestamp < $1.timestamp }
    }

    @MainActor
    func getPeerIDForNickname(_ nickname: String) -> PeerID? {

        switch LocationChannelManager.shared.selectedChannel {
        case .location:

            if nickname.contains("#") {
                if let person = visibleGeohashPeople().first(where: { $0.displayName == nickname }) {
                    let convKey = PeerID(nostr_: person.id)
                    nostrKeyMapping[convKey] = person.id
                    return convKey
                }
            }
            let base: String = {
                if let hashIndex = nickname.firstIndex(of: "#") { return String(nickname[..<hashIndex]) }
                return nickname
            }().lowercased()

            if let pub = geoNicknames.first(where: { (_, nick) in nick.lowercased() == base })?.key {
                let convKey = PeerID(nostr_: pub)
                nostrKeyMapping[convKey] = pub
                return convKey
            }
        case .mesh:
            break
        }

        return unifiedPeerService.getPeerID(for: nickname)
    }

    @MainActor
    func panicClearAllData() {

        messages.removeAll()
        privateChatManager.privateChats.removeAll()
        privateChatManager.unreadMessages.removeAll()

        _ = keychain.deleteAllKeychainData()

        userDefaults.removeObject(forKey: "bitchat.noiseIdentityKey")
        userDefaults.removeObject(forKey: "bitchat.messageRetentionKey")

        verifiedFingerprints.removeAll()

        nickname = "anon\(Int.random(in: 1000...9999))"
        saveNickname()

        identityManager.clearAllIdentityData()
        peerIDToPublicKeyFingerprint.removeAll()

        FavoritesPersistenceService.shared.clearAllFavorites()

        autocompleteSuggestions.removeAll()
        showAutocomplete = false
        autocompleteRange = nil
        selectedAutocompleteIndex = 0

        selectedPrivateChatPeer = nil
        selectedPrivateChatFingerprint = nil

        sentReadReceipts.removeAll()
        deduplicationService.clearAll()

        invalidateEncryptionCache()

        nostrRelayManager?.disconnect()
        nostrRelayManager = nil

        idBridge.clearAllAssociations()

        meshService.emergencyDisconnectAll()
        if let bleService = meshService as? BLEService {
            bleService.resetIdentityForPanic(currentNickname: nickname)
        }

        Task { @MainActor in

            try? await Task.sleep(nanoseconds: TransportConfig.uiAsyncShortSleepNs)

            nostrRelayManager = NostrRelayManager()
            setupNostrMessageHandling()
            nostrRelayManager?.connect()
        }

        Task.detached(priority: .utility) {
            do {
                let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                let filesDir = base.appendingPathComponent("files", isDirectory: true)

                if FileManager.default.fileExists(atPath: filesDir.path) {
                    try FileManager.default.removeItem(at: filesDir)
                    SecureLogger.info("Deleted all media files during panic clear", category: .session)
                }

                try FileManager.default.createDirectory(at: filesDir, withIntermediateDirectories: true, attributes: nil)
                try FileManager.default.createDirectory(at: filesDir.appendingPathComponent("voicenotes/incoming", isDirectory: true), withIntermediateDirectories: true, attributes: nil)
                try FileManager.default.createDirectory(at: filesDir.appendingPathComponent("voicenotes/outgoing", isDirectory: true), withIntermediateDirectories: true, attributes: nil)
                try FileManager.default.createDirectory(at: filesDir.appendingPathComponent("images/incoming", isDirectory: true), withIntermediateDirectories: true, attributes: nil)
                try FileManager.default.createDirectory(at: filesDir.appendingPathComponent("images/outgoing", isDirectory: true), withIntermediateDirectories: true, attributes: nil)
                try FileManager.default.createDirectory(at: filesDir.appendingPathComponent("files/incoming", isDirectory: true), withIntermediateDirectories: true, attributes: nil)
                try FileManager.default.createDirectory(at: filesDir.appendingPathComponent("files/outgoing", isDirectory: true), withIntermediateDirectories: true, attributes: nil)
            } catch {
                SecureLogger.error("Failed to clear media files during panic: \(error)", category: .session)
            }

            #if os(iOS)
            Self.clearAppSwitcherSnapshots()
            #endif
        }

    }

    #if os(iOS)
    private nonisolated static func clearAppSwitcherSnapshots() {
        do {
            let cacheDir = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: false)
            let snapshotsDir = cacheDir.appendingPathComponent("Snapshots", isDirectory: true)

            if FileManager.default.fileExists(atPath: snapshotsDir.path) {
                let contents = try FileManager.default.contentsOfDirectory(at: snapshotsDir, includingPropertiesForKeys: nil)
                for item in contents {
                    try FileManager.default.removeItem(at: item)
                }
                SecureLogger.info("Cleared app switcher snapshots during panic clear", category: .session)
            }
        } catch {
            SecureLogger.error("Failed to clear app switcher snapshots: \(error)", category: .session)
        }
    }
    #endif

    func updateAutocomplete(for text: String, cursorPosition: Int) {

        let peerCandidates: [String] = {
            switch activeChannel {
            case .mesh:
                let values = meshService.getPeerNicknames().values
                return Array(values.filter { $0 != meshService.myNickname })
            case .location(let ch):

                var tokens = Set<String>()
                for (pubkey, nick) in geoNicknames {
                    let suffix = String(pubkey.suffix(4))
                    tokens.insert("\(nick)#\(suffix)")
                }

                if let id = try? idBridge.deriveIdentity(forGeohash: ch.geohash) {
                    let myToken = nickname + "#" + String(id.publicKeyHex.suffix(4))
                    tokens.remove(myToken)
                }
                return Array(tokens)
            }
        }()

        let (suggestions, range) = autocompleteService.getSuggestions(
            for: text,
            peers: peerCandidates,
            cursorPosition: cursorPosition
        )

        if !suggestions.isEmpty {
            autocompleteSuggestions = suggestions
            autocompleteRange = range
            showAutocomplete = true
            selectedAutocompleteIndex = 0
        } else {
            autocompleteSuggestions = []
            autocompleteRange = nil
            showAutocomplete = false
            selectedAutocompleteIndex = 0
        }
    }

    func completeNickname(_ nickname: String, in text: inout String) -> Int {
        guard let range = autocompleteRange else { return text.count }

        text = autocompleteService.applySuggestion(nickname, to: text, range: range)

        showAutocomplete = false
        autocompleteSuggestions = []
        autocompleteRange = nil
        selectedAutocompleteIndex = 0

        return range.location + nickname.count + (nickname.hasPrefix("@") ? 1 : 2)
    }

    @MainActor
    func formatMessageAsText(_ message: BitchatMessage, colorScheme: ColorScheme) -> AttributedString {

        let isSelf: Bool = {
            if let spid = message.senderPeerID {

                if case .location(let ch) = activeChannel, spid.isGeoChat {
                    let myGeo: NostrIdentity? = {
                        if let cached = cachedGeohashIdentity, cached.geohash == ch.geohash {
                            return cached.identity
                        }

                        if let identity = try? idBridge.deriveIdentity(forGeohash: ch.geohash) {
                            cachedGeohashIdentity = (ch.geohash, identity)
                            return identity
                        }
                        return nil
                    }()
                    if let myGeo {
                        return spid == PeerID(nostr: myGeo.publicKeyHex)
                    }
                }
                return spid == meshService.myPeerID
            }

            if message.sender == nickname { return true }
            if message.sender.hasPrefix(nickname + "#") { return true }
            return false
        }()

        let isDark = colorScheme == .dark
        if let cachedText = message.getCachedFormattedText(isDark: isDark, isSelf: isSelf) {
            return cachedText
        }

        var result = AttributedString()

        let baseColor: Color = isSelf ? .orange : peerColor(for: message, isDark: isDark)

        if message.sender != "system" {

            let (baseName, suffix) = message.sender.splitSuffix()
            var senderStyle = AttributeContainer()

            senderStyle.foregroundColor = baseColor

            let fontWeight: Font.Weight = isSelf ? .bold : .medium
            senderStyle.font = .bitchatSystem(size: 14, weight: fontWeight, design: .monospaced)

            if let spid = message.senderPeerID, let url = URL(string: "bitchat://user/\(spid.toPercentEncoded())") {
                senderStyle.link = url
            }

            result.append(AttributedString("<@").mergingAttributes(senderStyle))

            result.append(AttributedString(baseName).mergingAttributes(senderStyle))

            if !suffix.isEmpty {
                var suffixStyle = senderStyle
                suffixStyle.foregroundColor = baseColor.opacity(0.6)
                result.append(AttributedString(suffix).mergingAttributes(suffixStyle))
            }

            result.append(AttributedString("> ").mergingAttributes(senderStyle))

            let content = message.content

            let nsContent = content as NSString
            let nsLen = nsContent.length
            let containsCashuEarly: Bool = {
                let rx = Patterns.quickCashuPresence
                return rx.numberOfMatches(in: content, options: [], range: NSRange(location: 0, length: nsLen)) > 0
            }()
            if (content.count > 4000 || content.hasVeryLongToken(threshold: 1024)) && !containsCashuEarly {
                var plainStyle = AttributeContainer()
                plainStyle.foregroundColor = baseColor
                plainStyle.font = isSelf
                    ? .bitchatSystem(size: 14, weight: .bold, design: .monospaced)
                    : .bitchatSystem(size: 14, design: .monospaced)
                result.append(AttributedString(content).mergingAttributes(plainStyle))
            } else {

            let hashtagRegex = Patterns.hashtag
            let mentionRegex = Patterns.mention
            let cashuRegex = Patterns.cashu
            let bolt11Regex = Patterns.bolt11
            let lnurlRegex = Patterns.lnurl
            let lightningSchemeRegex = Patterns.lightningScheme
            let detector = Patterns.linkDetector
            let hasMentionsHint = content.contains("@")
            let hasHashtagsHint = content.contains("#")
            let hasURLHint = content.contains("://") || content.contains("www.") || content.contains("http")
            let hasLightningHint = content.lowercased().contains("ln") || content.lowercased().contains("lightning:")
            let hasCashuHint = content.lowercased().contains("cashu")

            let hashtagMatches = hasHashtagsHint ? hashtagRegex.matches(in: content, options: [], range: NSRange(location: 0, length: nsLen)) : []
            let mentionMatches = hasMentionsHint ? mentionRegex.matches(in: content, options: [], range: NSRange(location: 0, length: nsLen)) : []
            let urlMatches = hasURLHint ? (detector?.matches(in: content, options: [], range: NSRange(location: 0, length: nsLen)) ?? []) : []
            let cashuMatches = hasCashuHint ? cashuRegex.matches(in: content, options: [], range: NSRange(location: 0, length: nsLen)) : []
            let lightningMatches = hasLightningHint ? lightningSchemeRegex.matches(in: content, options: [], range: NSRange(location: 0, length: nsLen)) : []
            let bolt11Matches = hasLightningHint ? bolt11Regex.matches(in: content, options: [], range: NSRange(location: 0, length: nsLen)) : []
            let lnurlMatches = hasLightningHint ? lnurlRegex.matches(in: content, options: [], range: NSRange(location: 0, length: nsLen)) : []

            let mentionRanges = mentionMatches.map { $0.range(at: 0) }
            func overlapsMention(_ r: NSRange) -> Bool {
                for mr in mentionRanges { if NSIntersectionRange(r, mr).length > 0 { return true } }
                return false
            }

            func attachedToMention(_ r: NSRange) -> Bool {
                if let nsRange = Range(r, in: content), nsRange.lowerBound > content.startIndex {
                    var i = content.index(before: nsRange.lowerBound)
                    while true {
                        let ch = content[i]
                        if ch.isWhitespace || ch.isNewline { break }
                        if ch == "@" { return true }
                        if i == content.startIndex { break }
                        i = content.index(before: i)
                    }
                }
                return false
            }

            func isStandaloneHashtag(_ r: NSRange) -> Bool {
                guard let nsRange = Range(r, in: content) else { return false }
                if nsRange.lowerBound == content.startIndex { return true }
                let prev = content.index(before: nsRange.lowerBound)
                return content[prev].isWhitespace || content[prev].isNewline
            }
            var allMatches: [(range: NSRange, type: String)] = []
            for match in hashtagMatches where !overlapsMention(match.range(at: 0)) && !attachedToMention(match.range(at: 0)) && isStandaloneHashtag(match.range(at: 0)) {
                allMatches.append((match.range(at: 0), "hashtag"))
            }
            for match in mentionMatches {
                allMatches.append((match.range(at: 0), "mention"))
            }
            for match in urlMatches where !overlapsMention(match.range) {
                allMatches.append((match.range, "url"))
            }
            for match in cashuMatches where !overlapsMention(match.range(at: 0)) {
                allMatches.append((match.range(at: 0), "cashu"))
            }

            for match in lightningMatches where !overlapsMention(match.range(at: 0)) {
                allMatches.append((match.range(at: 0), "lightning"))
            }

            let occupied: [NSRange] = urlMatches.map { $0.range } + lightningMatches.map { $0.range(at: 0) }
            func overlapsOccupied(_ r: NSRange) -> Bool {
                for or in occupied { if NSIntersectionRange(r, or).length > 0 { return true } }
                return false
            }
            for match in bolt11Matches where !overlapsMention(match.range(at: 0)) && !overlapsOccupied(match.range(at: 0)) {
                allMatches.append((match.range(at: 0), "bolt11"))
            }
            for match in lnurlMatches where !overlapsMention(match.range(at: 0)) && !overlapsOccupied(match.range(at: 0)) {
                allMatches.append((match.range(at: 0), "lnurl"))
            }
            allMatches.sort { $0.range.location < $1.range.location }

            var lastEnd = content.startIndex
            let isMentioned = message.mentions?.contains(nickname) ?? false

            for (range, type) in allMatches {

                if let nsRange = Range(range, in: content) {
                    if lastEnd < nsRange.lowerBound {
                        let beforeText = String(content[lastEnd..<nsRange.lowerBound])
                        if !beforeText.isEmpty {
                            var beforeStyle = AttributeContainer()
                            beforeStyle.foregroundColor = baseColor
                            beforeStyle.font = isSelf
                                ? .bitchatSystem(size: 14, weight: .bold, design: .monospaced)
                                : .bitchatSystem(size: 14, design: .monospaced)
                            if isMentioned {
                                beforeStyle.font = beforeStyle.font?.bold()
                            }
                            result.append(AttributedString(beforeText).mergingAttributes(beforeStyle))
                        }
                    }

                    let matchText = String(content[nsRange])
                    if type == "mention" {

                        let (mBase, mSuffix) = matchText.splitSuffix()

                        let mySuffix: String? = {
                            if case .location(let ch) = activeChannel, let id = try? idBridge.deriveIdentity(forGeohash: ch.geohash) {
                                return String(id.publicKeyHex.suffix(4))
                            }
                            return String(meshService.myPeerID.id.prefix(4))
                        }()
                        let isMentionToMe: Bool = {
                            if mBase == nickname {
                                if let suf = mySuffix, !mSuffix.isEmpty {
                                    return mSuffix == "#\(suf)"
                                }
                                return mSuffix.isEmpty
                            }
                            return false
                        }()
                        var mentionStyle = AttributeContainer()
                        mentionStyle.font = .bitchatSystem(size: 14, weight: isSelf ? .bold : .semibold, design: .monospaced)
                        let mentionColor: Color = isMentionToMe ? .orange : baseColor
                        mentionStyle.foregroundColor = mentionColor

                        let at = "@"
                        result.append(AttributedString("\(at)").mergingAttributes(mentionStyle))

                        result.append(AttributedString(mBase).mergingAttributes(mentionStyle))

                        if !mSuffix.isEmpty {
                            var light = mentionStyle
                            light.foregroundColor = mentionColor.opacity(0.6)
                            result.append(AttributedString(mSuffix).mergingAttributes(light))
                        }
                    } else {

                        if type == "hashtag" {

                            let token = String(matchText.dropFirst()).lowercased()
                            let allowed = Set("0123456789bcdefghjkmnpqrstuvwxyz")
                            let isGeohash = (2...12).contains(token.count) && token.allSatisfy { allowed.contains($0) }

                            let attachedToMention: Bool = {

                                if nsRange.lowerBound > content.startIndex {
                                    var i = content.index(before: nsRange.lowerBound)
                                    while true {
                                        let ch = content[i]
                                        if ch.isWhitespace || ch.isNewline { break }
                                        if ch == "@" { return true }
                                        if i == content.startIndex { break }
                                        i = content.index(before: i)
                                    }
                                }
                                return false
                            }()

                            let standalone: Bool = {
                                if nsRange.lowerBound == content.startIndex { return true }
                                let prev = content.index(before: nsRange.lowerBound)
                                return content[prev].isWhitespace || content[prev].isNewline
                            }()
                            var tagStyle = AttributeContainer()
                            tagStyle.font = isSelf
                                ? .bitchatSystem(size: 14, weight: .bold, design: .monospaced)
                                : .bitchatSystem(size: 14, design: .monospaced)
                            tagStyle.foregroundColor = baseColor
                            if isGeohash && !attachedToMention && standalone, let url = URL(string: "bitchat://geohash/\(token)") {
                                tagStyle.link = url
                                tagStyle.underlineStyle = .single
                            }
                            result.append(AttributedString(matchText).mergingAttributes(tagStyle))
                        } else if type == "cashu" {

                            var spacer = AttributeContainer()
                            spacer.foregroundColor = baseColor
                            spacer.font = isSelf
                                ? .bitchatSystem(size: 14, weight: .bold, design: .monospaced)
                                : .bitchatSystem(size: 14, design: .monospaced)
                            result.append(AttributedString(" ").mergingAttributes(spacer))
                        } else if type == "lightning" || type == "bolt11" || type == "lnurl" {

                            var spacer = AttributeContainer()
                            spacer.foregroundColor = baseColor
                            spacer.font = isSelf
                                ? .bitchatSystem(size: 14, weight: .bold, design: .monospaced)
                                : .bitchatSystem(size: 14, design: .monospaced)
                            result.append(AttributedString(" ").mergingAttributes(spacer))
                        } else {

                            var matchStyle = AttributeContainer()
                            matchStyle.font = .bitchatSystem(size: 14, weight: isSelf ? .bold : .semibold, design: .monospaced)
                            if type == "url" {
                                matchStyle.foregroundColor = isSelf ? .orange : .blue
                                matchStyle.underlineStyle = .single
                                if let url = URL(string: matchText) {
                                    matchStyle.link = url
                                }
                            }
                            result.append(AttributedString(matchText).mergingAttributes(matchStyle))
                        }
                    }

                    if lastEnd < nsRange.upperBound {
                        lastEnd = nsRange.upperBound
                    }
                }
            }

            if lastEnd < content.endIndex {
                let remainingText = String(content[lastEnd...])
                var remainingStyle = AttributeContainer()
                remainingStyle.foregroundColor = baseColor
                remainingStyle.font = isSelf
                    ? .bitchatSystem(size: 14, weight: .bold, design: .monospaced)
                    : .bitchatSystem(size: 14, design: .monospaced)
                if isMentioned {
                    remainingStyle.font = remainingStyle.font?.bold()
                }
                result.append(AttributedString(remainingText).mergingAttributes(remainingStyle))
            }
            }

            let timestamp = AttributedString(" [\(message.formattedTimestamp)]")
            var timestampStyle = AttributeContainer()
            timestampStyle.foregroundColor = Color.gray.opacity(0.7)
            timestampStyle.font = .bitchatSystem(size: 10, design: .monospaced)
            result.append(timestamp.mergingAttributes(timestampStyle))
        } else {

            var contentStyle = AttributeContainer()
            contentStyle.foregroundColor = Color.gray
            let content = AttributedString("* \(message.content) *")
            contentStyle.font = .bitchatSystem(size: 12, design: .monospaced).italic()
            result.append(content.mergingAttributes(contentStyle))

            let timestamp = AttributedString(" [\(message.formattedTimestamp)]")
            var timestampStyle = AttributeContainer()
            timestampStyle.foregroundColor = Color.gray.opacity(0.5)
            timestampStyle.font = .bitchatSystem(size: 10, design: .monospaced)
            result.append(timestamp.mergingAttributes(timestampStyle))
        }

        message.setCachedFormattedText(result, isDark: isDark, isSelf: isSelf)

        return result
    }

    @MainActor
    func formatMessageHeader(_ message: BitchatMessage, colorScheme: ColorScheme) -> AttributedString {
        let isSelf: Bool = {
            if let spid = message.senderPeerID {
                if case .location(let ch) = activeChannel, spid.id.hasPrefix("nostr:") {
                    if let myGeo = try? idBridge.deriveIdentity(forGeohash: ch.geohash) {
                        return spid == PeerID(nostr: myGeo.publicKeyHex)
                    }
                }
                return spid == meshService.myPeerID
            }
            if message.sender == nickname { return true }
            if message.sender.hasPrefix(nickname + "#") { return true }
            return false
        }()

        let isDark = colorScheme == .dark
        let baseColor: Color = isSelf ? .orange : peerColor(for: message, isDark: isDark)

        if message.sender == "system" {
            var style = AttributeContainer()
            style.foregroundColor = baseColor
            style.font = .bitchatSystem(size: 14, weight: .medium, design: .monospaced)
            return AttributedString(message.sender).mergingAttributes(style)
        }

        var result = AttributedString()
        let (baseName, suffix) = message.sender.splitSuffix()
        var senderStyle = AttributeContainer()
        senderStyle.foregroundColor = baseColor
        senderStyle.font = .bitchatSystem(size: 14, weight: isSelf ? .bold : .medium, design: .monospaced)
        if let spid = message.senderPeerID,
           let url = URL(string: "bitchat://user/\(spid.id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? spid.id)") {
            senderStyle.link = url
        }

        result.append(AttributedString("<@").mergingAttributes(senderStyle))
        result.append(AttributedString(baseName).mergingAttributes(senderStyle))
        if !suffix.isEmpty {
            var suffixStyle = senderStyle
            suffixStyle.foregroundColor = baseColor.opacity(0.6)
            result.append(AttributedString(suffix).mergingAttributes(suffixStyle))
        }
        result.append(AttributedString("> ").mergingAttributes(senderStyle))
        return result
    }

    @MainActor
    func updateEncryptionStatusForPeers() {
        for peerID in connectedPeers {
            updateEncryptionStatusForPeer(peerID)
        }
    }

    @MainActor
    private func updateEncryptionStatusForPeer(_ peerID: PeerID) {
        let noiseService = meshService.getNoiseService()

        if noiseService.hasEstablishedSession(with: peerID) {
            peerEncryptionStatus[peerID] = encryptionStatus(for: peerID)
        } else if noiseService.hasSession(with: peerID) {

            peerEncryptionStatus[peerID] = .noiseHandshaking
        } else {

            peerEncryptionStatus[peerID] = Optional.none
        }

        invalidateEncryptionCache(for: peerID)

    }

    @MainActor
    func getEncryptionStatus(for peerID: PeerID) -> EncryptionStatus {

        if let cachedStatus = encryptionStatusCache[peerID] {
            return cachedStatus
        }

        let hasEverEstablishedSession = getFingerprint(for: peerID) != nil

        let sessionState = meshService.getNoiseSessionState(for: peerID)

        let status: EncryptionStatus

        switch sessionState {
        case .established:
            status = encryptionStatus(for: peerID)
        case .handshaking, .handshakeQueued:

            if hasEverEstablishedSession {

                status = encryptionStatus(for: peerID)
            } else {

                status = .noiseHandshaking
            }
        case .none:

            if hasEverEstablishedSession {

                status = encryptionStatus(for: peerID)
            } else {

                status = .noHandshake
            }
        case .failed:

            if hasEverEstablishedSession {

                status = encryptionStatus(for: peerID)
            } else {

                status = .none
            }
        }

        encryptionStatusCache[peerID] = status

        return status
    }

    private func invalidateEncryptionCache(for peerID: PeerID? = nil) {
        if let peerID {
            encryptionStatusCache.removeValue(forKey: peerID)
        } else {
            encryptionStatusCache.removeAll()
        }
    }

    func trimMessagesIfNeeded() {
        if messages.count > maxMessages {
            messages = Array(messages.suffix(maxMessages))
        }
    }

    @MainActor
    func refreshVisibleMessages(from channel: ChannelID? = nil) {
        let target = channel ?? activeChannel
        messages = timelineStore.messages(for: target)
    }

    @MainActor
    private func peerColor(for message: BitchatMessage, isDark: Bool) -> Color {
        if let spid = message.senderPeerID {
            if spid.isGeoChat || spid.isGeoDM {
                let full = nostrKeyMapping[spid]?.lowercased() ?? spid.bare.lowercased()
                return getNostrPaletteColor(for: full, isDark: isDark)
            } else if spid.id.count == 16 {

                return getPeerPaletteColor(for: spid, isDark: isDark)
            } else {
                return getPeerPaletteColor(for: PeerID(str: spid.id.lowercased()), isDark: isDark)
            }
        }

        return Color(peerSeed: message.sender.lowercased(), isDark: isDark)
    }

    @MainActor
    func isSelfMessage(_ message: BitchatMessage) -> Bool {
        if let spid = message.senderPeerID {

            if case .location(let ch) = activeChannel, spid.isGeoChat {
                let myGeo: NostrIdentity? = {
                    if let cached = cachedGeohashIdentity, cached.geohash == ch.geohash {
                        return cached.identity
                    }

                    if let identity = try? idBridge.deriveIdentity(forGeohash: ch.geohash) {
                        cachedGeohashIdentity = (ch.geohash, identity)
                        return identity
                    }
                    return nil
                }()
                if let myGeo {
                    return spid == PeerID(nostr: myGeo.publicKeyHex)
                }
            }
            return spid == meshService.myPeerID
        }

        if message.sender == nickname { return true }
        if message.sender.hasPrefix(nickname + "#") { return true }
        return false
    }

    @MainActor
    func senderColor(for message: BitchatMessage, isDark: Bool) -> Color {
        return peerColor(for: message, isDark: isDark)
    }

    @MainActor
    func peerURL(for peerID: PeerID) -> URL? {
        return URL(string: "bitchat://user/\(peerID.toPercentEncoded())")
    }

    @MainActor
    func colorForNostrPubkey(_ pubkeyHexLowercased: String, isDark: Bool) -> Color {
        return getNostrPaletteColor(for: pubkeyHexLowercased.lowercased(), isDark: isDark)
    }

    @MainActor
    func colorForMeshPeer(id peerID: PeerID, isDark: Bool) -> Color {
        return getPeerPaletteColor(for: peerID, isDark: isDark)
    }

    private let meshPalette = MinimalDistancePalette(config: .mesh)
    private let nostrPalette = MinimalDistancePalette(config: .nostr)

    @MainActor
    private func meshSeed(for peerID: PeerID) -> String {
        if let full = getNoiseKeyForShortID(peerID)?.id.lowercased() {
            return "noise:" + full
        }
        return peerID.id.lowercased()
    }

    @MainActor
    private func getPeerPaletteColor(for peerID: PeerID, isDark: Bool) -> Color {
        if peerID == meshService.myPeerID {
            return .orange
        }

        meshPalette.ensurePalette(for: currentMeshPaletteSeeds())
        if let color = meshPalette.color(for: peerID.id, isDark: isDark) {
            return color
        }
        return Color(peerSeed: meshSeed(for: peerID), isDark: isDark)
    }

    @MainActor
    private func currentMeshPaletteSeeds() -> [String: String] {
        let myID = meshService.myPeerID
        var seeds: [String: String] = [:]
        for peer in allPeers where peer.peerID != myID {
            seeds[peer.peerID.id] = meshSeed(for: peer.peerID)
        }
        return seeds
    }

    @MainActor
    private func getNostrPaletteColor(for pubkeyHexLowercased: String, isDark: Bool) -> Color {
        let myHex = currentGeohashIdentityHex()
        if let myHex, pubkeyHexLowercased == myHex {
            return .orange
        }

        nostrPalette.ensurePalette(for: currentNostrPaletteSeeds(excluding: myHex))
        if let color = nostrPalette.color(for: pubkeyHexLowercased, isDark: isDark) {
            return color
        }
        return Color(peerSeed: "nostr:" + pubkeyHexLowercased, isDark: isDark)
    }

    @MainActor
    private func currentNostrPaletteSeeds(excluding myHex: String?) -> [String: String] {
        var seeds: [String: String] = [:]
        let excluded = myHex ?? ""
        for person in visibleGeohashPeople() where person.id != excluded {
            seeds[person.id] = "nostr:" + person.id
        }
        return seeds
    }

    @MainActor
    private func currentGeohashIdentityHex() -> String? {
        if case .location(let channel) = LocationChannelManager.shared.selectedChannel,
           let identity = try? idBridge.deriveIdentity(forGeohash: channel.geohash) {
            return identity.publicKeyHex.lowercased()
        }
        return nil
    }

    @MainActor
    func clearCurrentPublicTimeline() {

        messages.removeAll()
        timelineStore.clear(channel: activeChannel)

        Task.detached(priority: .utility) {
            do {
                let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                let filesDir = base.appendingPathComponent("files", isDirectory: true)

                let outgoingDirs = [
                    filesDir.appendingPathComponent("voicenotes/outgoing", isDirectory: true),
                    filesDir.appendingPathComponent("images/outgoing", isDirectory: true),
                    filesDir.appendingPathComponent("files/outgoing", isDirectory: true)
                ]

                for dir in outgoingDirs {
                    if FileManager.default.fileExists(atPath: dir.path) {
                        try? FileManager.default.removeItem(at: dir)
                        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
                    }
                }
            } catch {
                SecureLogger.error("Failed to clear media files: \(error)", category: .session)
            }
        }
    }

    private func addMessage(_ message: BitchatMessage) {

        guard !messages.contains(where: { $0.id == message.id }) else { return }
        messages.append(message)
        trimMessagesIfNeeded()
    }

    @MainActor
    private func updateEncryptionStatus(for peerID: PeerID) {
        let noiseService = meshService.getNoiseService()

        if noiseService.hasEstablishedSession(with: peerID) {
            peerEncryptionStatus[peerID] = encryptionStatus(for: peerID)
        } else if noiseService.hasSession(with: peerID) {
            peerEncryptionStatus[peerID] = .noiseHandshaking
        } else {
            peerEncryptionStatus[peerID] = Optional.none
        }

        invalidateEncryptionCache(for: peerID)

    }

    func showFingerprint(for peerID: PeerID) {
        showingFingerprintFor = peerID
    }

    func getPeer(byID peerID: PeerID) -> BitchatPeer? {
        return peerIndex[peerID]
    }

    @MainActor
    func getFingerprint(for peerID: PeerID) -> String? {
        return unifiedPeerService.getFingerprint(for: peerID)
    }

    @MainActor
    private func encryptionStatus(for peerID: PeerID) -> EncryptionStatus {
        if let fp = getFingerprint(for: peerID), verifiedFingerprints.contains(fp) {
            return .noiseVerified
        } else {
            return .noiseSecured
        }
    }

    @MainActor
    private func resolveNickname(for peerID: PeerID) -> String {

        guard !peerID.isEmpty else {
            return "unknown"
        }

        if !peerID.isHex {

            return peerID.id
        }

        let peerNicknames = meshService.getPeerNicknames()
        if let nickname = peerNicknames[peerID] {
            return nickname
        }

        if let fingerprint = getFingerprint(for: peerID) {
            if let identity = identityManager.getSocialIdentity(for: fingerprint) {

                if let petname = identity.localPetname {
                    return petname
                }

                return identity.claimedNickname
            }
        }

        let prefixLength = min(4, peerID.id.count)
        let prefix = String(peerID.id.prefix(prefixLength))

        if prefix.starts(with: "anon") {
            return "peer\(prefix)"
        }
        return "anon\(prefix)"
    }

    func getMyFingerprint() -> String {
        let fingerprint = meshService.getNoiseService().getIdentityFingerprint()
        return fingerprint
    }

    @MainActor
    func verifyFingerprint(for peerID: PeerID) {
        guard let fingerprint = getFingerprint(for: peerID) else { return }

        identityManager.setVerified(fingerprint: fingerprint, verified: true)
        saveIdentityState()

        verifiedFingerprints.insert(fingerprint)

        updateEncryptionStatus(for: peerID)
    }

    @MainActor
    func unverifyFingerprint(for peerID: PeerID) {
        guard let fingerprint = getFingerprint(for: peerID) else { return }
        identityManager.setVerified(fingerprint: fingerprint, verified: false)
        saveIdentityState()
        verifiedFingerprints.remove(fingerprint)
        updateEncryptionStatus(for: peerID)
    }

    @MainActor
    func loadVerifiedFingerprints() {

        verifiedFingerprints = identityManager.getVerifiedFingerprints()

        let sample = Array(verifiedFingerprints.prefix(TransportConfig.uiFingerprintSampleCount)).map { $0.prefix(8) }.joined(separator: ", ")
        SecureLogger.info("Verified loaded: \(verifiedFingerprints.count) [\(sample)]", category: .security)

        let offlineFavorites = unifiedPeerService.favorites.filter { !$0.isConnected }
        for fav in offlineFavorites {
            let fp = unifiedPeerService.getFingerprint(for: fav.peerID)
            let isVer = fp.flatMap { verifiedFingerprints.contains($0) } ?? false
            let fpShort = fp?.prefix(8) ?? "nil"
            SecureLogger.info("⭐Favorite offline: \(fav.nickname) fp=\(fpShort) verified=\(isVer)", category: .security)
        }

        invalidateEncryptionCache()

        objectWillChange.send()
    }

    private func setupNoiseCallbacks() {
        let noiseService = meshService.getNoiseService()

        noiseService.onPeerAuthenticated = { [weak self] peerID, fingerprint in
            DispatchQueue.main.async {
                guard let self = self else { return }

                SecureLogger.debug("Authenticated: \(peerID)", category: .security)

                if self.verifiedFingerprints.contains(fingerprint) {
                    self.peerEncryptionStatus[peerID] = .noiseVerified

                } else {
                    self.peerEncryptionStatus[peerID] = .noiseSecured

                }

                self.invalidateEncryptionCache(for: peerID)

                if self.shortIDToNoiseKey[peerID] == nil,
                   let keyData = self.meshService.getNoiseService().getPeerPublicKeyData(peerID) {
                    let stable = PeerID(hexData: keyData)
                    self.shortIDToNoiseKey[peerID] = stable
                    SecureLogger.debug("Mapped short peerID to Noise key for header continuity: \(peerID) -> \(stable.id.prefix(8))…", category: .session)
                }

                if var pending = self.pendingQRVerifications[peerID], pending.sent == false {
                    self.meshService.sendVerifyChallenge(to: peerID, noiseKeyHex: pending.noiseKeyHex, nonceA: pending.nonceA)
                    pending.sent = true
                    self.pendingQRVerifications[peerID] = pending
                    SecureLogger.debug("Sent deferred verify challenge to \(peerID) after handshake", category: .security)
                }

            }
        }

        noiseService.onHandshakeRequired = { [weak self] peerID in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.peerEncryptionStatus[peerID] = .noiseHandshaking

                self.invalidateEncryptionCache(for: peerID)
            }
        }
    }

    @MainActor
    private func handleCommand(_ command: String) {
        let result = commandProcessor.process(command)

        switch result {
        case .success(let message):
            if let msg = message {
                addSystemMessage(msg)
            }
        case .error(let message):
            addSystemMessage(message)
        case .handled:

            break
        }
    }

    func didReceiveMessage(_ message: BitchatMessage) {
        Task { @MainActor in

            guard !isMessageBlocked(message) else { return }
            guard !message.content.trimmed.isEmpty || message.isPrivate else { return }

            if message.isPrivate {
                handlePrivateMessage(message)
            } else {
                handlePublicMessage(message)
            }

            checkForMentions(message)
            sendHapticFeedback(for: message)
        }
    }

    private func findMessageIndex(messageID: String, peerID: PeerID) -> (peerID: PeerID, index: Int)? {

        if let messages = privateChats[peerID],
           let idx = messages.firstIndex(where: { $0.id == messageID }) {
            return (peerID, idx)
        }

        if peerID.bare.count == 16,
           let peer = unifiedPeerService.getPeer(by: peerID),
           !peer.noisePublicKey.isEmpty {
            let longID = PeerID(hexData: peer.noisePublicKey)
            if let messages = privateChats[longID],
               let idx = messages.firstIndex(where: { $0.id == messageID }) {
                return (longID, idx)
            }
        }

        if peerID.bare.count == 64 {
            let shortID = peerID.toShort()
            if let messages = privateChats[shortID],
               let idx = messages.firstIndex(where: { $0.id == messageID }) {
                return (shortID, idx)
            }
        }

        return nil
    }

    func didReceiveNoisePayload(from peerID: PeerID, type: NoisePayloadType, payload: Data, timestamp: Date) {
        Task { @MainActor in
            switch type {
            case .privateMessage:
                guard let pm = PrivateMessagePacket.decode(from: payload) else { return }

                if isPeerBlocked(peerID) {
                    SecureLogger.debug("Ignoring Noise payload from blocked peer: \(peerID)", category: .security)
                    return
                }

                let senderName = unifiedPeerService.getPeer(by: peerID)?.nickname ?? "Unknown"
                let pmMentions = parseMentions(from: pm.content)
                let msg = BitchatMessage(
                    id: pm.messageID,
                    sender: senderName,
                    content: pm.content,
                    timestamp: timestamp,
                    isRelay: false,
                    originalSender: nil,
                    isPrivate: true,
                    recipientNickname: nickname,
                    senderPeerID: peerID,
                    mentions: pmMentions.isEmpty ? nil : pmMentions
                )
                handlePrivateMessage(msg)

                meshService.sendDeliveryAck(for: pm.messageID, to: peerID)

            case .delivered:
                guard let messageID = String(data: payload, encoding: .utf8) else { return }
                guard let name = unifiedPeerService.getPeer(by: peerID)?.nickname,
                      let (foundPeerID, idx) = findMessageIndex(messageID: messageID, peerID: peerID) else { return }

                if case .read = privateChats[foundPeerID]?[idx].deliveryStatus { return }

                privateChats[foundPeerID]?[idx].deliveryStatus = .delivered(to: name, at: Date())
                objectWillChange.send()

            case .readReceipt:
                guard let messageID = String(data: payload, encoding: .utf8) else { return }
                guard let name = unifiedPeerService.getPeer(by: peerID)?.nickname,
                      let (foundPeerID, idx) = findMessageIndex(messageID: messageID, peerID: peerID) else { return }

                if let messages = privateChats[foundPeerID], idx < messages.count {
                    messages[idx].deliveryStatus = .read(by: name, at: Date())
                    privateChats[foundPeerID] = messages
                    privateChatManager.objectWillChange.send()
                    objectWillChange.send()
                }
            case .verifyChallenge:

                guard let tlv = VerificationService.shared.parseVerifyChallenge(payload) else { return }

                let myNoiseHex = meshService.getNoiseService().getStaticPublicKeyData().hexEncodedString().lowercased()
                guard tlv.noiseKeyHex.lowercased() == myNoiseHex else { return }

                if let last = lastVerifyNonceByPeer[peerID], last == tlv.nonceA { return }
                lastVerifyNonceByPeer[peerID] = tlv.nonceA

                if let fp = getFingerprint(for: peerID) {
                    lastInboundVerifyChallengeAt[fp] = Date()

                    if verifiedFingerprints.contains(fp) {
                        let now = Date()
                        let last = lastMutualToastAt[fp] ?? .distantPast
                        if now.timeIntervalSince(last) > 60 {
                            lastMutualToastAt[fp] = now
                            let name = unifiedPeerService.getPeer(by: peerID)?.nickname ?? resolveNickname(for: peerID)
                            NotificationService.shared.sendLocalNotification(
                                title: "Mutual verification",
                                body: "You and \(name) verified each other",
                                identifier: "verify-mutual-\(peerID)-\(UUID().uuidString)"
                            )
                        }
                    }
                }
                meshService.sendVerifyResponse(to: peerID, noiseKeyHex: tlv.noiseKeyHex, nonceA: tlv.nonceA)

            case .verifyResponse:
                guard let resp = VerificationService.shared.parseVerifyResponse(payload) else { return }

                guard let pending = pendingQRVerifications[peerID] else { return }
                guard resp.noiseKeyHex.lowercased() == pending.noiseKeyHex.lowercased(), resp.nonceA == pending.nonceA else { return }

                let ok = VerificationService.shared.verifyResponseSignature(noiseKeyHex: resp.noiseKeyHex, nonceA: resp.nonceA, signature: resp.signature, signerPublicKeyHex: pending.signKeyHex)
                if ok {
                    pendingQRVerifications.removeValue(forKey: peerID)
                    if let fp = getFingerprint(for: peerID) {
                        let short = fp.prefix(8)
                        SecureLogger.info("Marking verified fingerprint: \(short)", category: .security)
                        identityManager.setVerified(fingerprint: fp, verified: true)
                        saveIdentityState()
                        verifiedFingerprints.insert(fp)
                        let name = unifiedPeerService.getPeer(by: peerID)?.nickname ?? resolveNickname(for: peerID)
                        NotificationService.shared.sendLocalNotification(
                            title: "Verified",
                            body: "You verified \(name)",
                            identifier: "verify-success-\(peerID)-\(UUID().uuidString)"
                        )

                        if let t = lastInboundVerifyChallengeAt[fp], Date().timeIntervalSince(t) < 600 {
                            let now = Date()
                            let lastToast = lastMutualToastAt[fp] ?? .distantPast
                            if now.timeIntervalSince(lastToast) > 60 {
                                lastMutualToastAt[fp] = now
                                NotificationService.shared.sendLocalNotification(
                                    title: "Mutual verification",
                                    body: "You and \(name) verified each other",
                                    identifier: "verify-mutual-\(peerID)-\(UUID().uuidString)"
                                )
                            }
                        }
                        updateEncryptionStatus(for: peerID)
                    }
                }
            }
        }
    }

    func didReceivePublicMessage(from peerID: PeerID, nickname: String, content: String, timestamp: Date, messageID: String?) {
        Task { @MainActor in
            let normalized = content.trimmed
            let publicMentions = parseMentions(from: normalized)
            let msg = BitchatMessage(
                id: messageID,
                sender: nickname,
                content: normalized,
                timestamp: timestamp,
                isRelay: false,
                originalSender: nil,
                isPrivate: false,
                recipientNickname: nil,
                senderPeerID: peerID,
                mentions: publicMentions.isEmpty ? nil : publicMentions
            )
            handlePublicMessage(msg)
            checkForMentions(msg)
            sendHapticFeedback(for: msg)
        }
    }

    @MainActor
    func beginQRVerification(with qr: VerificationService.VerificationQR) -> Bool {

        let targetNoise = qr.noiseKeyHex.lowercased()
        guard let peer = unifiedPeerService.peers.first(where: { $0.noisePublicKey.hexEncodedString().lowercased() == targetNoise }) else {
            return false
        }
        let peerID = peer.peerID

        if pendingQRVerifications[peerID] != nil {
            return true
        }

        var nonce = Data(count: 16)
        _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        var pending = PendingVerification(noiseKeyHex: qr.noiseKeyHex, signKeyHex: qr.signKeyHex, nonceA: nonce, startedAt: Date(), sent: false)
        pendingQRVerifications[peerID] = pending

        let noise = meshService.getNoiseService()
        if noise.hasEstablishedSession(with: peerID) {
            meshService.sendVerifyChallenge(to: peerID, noiseKeyHex: qr.noiseKeyHex, nonceA: nonce)
            pending.sent = true
            pendingQRVerifications[peerID] = pending
        } else {
            meshService.triggerHandshake(with: peerID)
        }
        return true
    }

    func didUpdateBluetoothState(_ state: CBManagerState) {
        Task { @MainActor in
            updateBluetoothState(state)
        }
    }

    func didConnectToPeer(_ peerID: PeerID) {
        SecureLogger.debug("Peer connected: \(peerID)", category: .session)

        Task { @MainActor in
            isConnected = true

            identityManager.registerEphemeralSession(peerID: peerID, handshakeState: .none)

            objectWillChange.send()

            if let peer = unifiedPeerService.getPeer(by: peerID) {
                let noiseKeyHex = PeerID(hexData: peer.noisePublicKey)
                shortIDToNoiseKey[peerID] = noiseKeyHex
            }

            messageRouter.flushOutbox(for: peerID)
        }
    }

    func didDisconnectFromPeer(_ peerID: PeerID) {
        SecureLogger.debug("Peer disconnected: \(peerID)", category: .session)

        identityManager.removeEphemeralSession(peerID: peerID)

        var derivedStableKeyHex = shortIDToNoiseKey[peerID]
        if derivedStableKeyHex == nil,
           let key = meshService.getNoiseService().getPeerPublicKeyData(peerID) {
            derivedStableKeyHex = PeerID(hexData: key)
            shortIDToNoiseKey[peerID] = derivedStableKeyHex
        }

        if let current = selectedPrivateChatPeer, current == peerID, let stableKeyHex = derivedStableKeyHex {

            if let messages = privateChats[peerID] {
                if privateChats[stableKeyHex] == nil { privateChats[stableKeyHex] = [] }
                let existing = Set(privateChats[stableKeyHex]!.map { $0.id })
                for msg in messages where !existing.contains(msg.id) {
                    let updated = BitchatMessage(
                        id: msg.id,
                        sender: msg.sender,
                        content: msg.content,
                        timestamp: msg.timestamp,
                        isRelay: msg.isRelay,
                        originalSender: msg.originalSender,
                        isPrivate: msg.isPrivate,
                        recipientNickname: msg.recipientNickname,
                        senderPeerID: msg.senderPeerID == meshService.myPeerID ? meshService.myPeerID : stableKeyHex,
                        mentions: msg.mentions,
                        deliveryStatus: msg.deliveryStatus
                    )
                    privateChats[stableKeyHex]?.append(updated)
                }
                privateChats[stableKeyHex]?.sort { $0.timestamp < $1.timestamp }
                privateChats.removeValue(forKey: peerID)
            }
            if unreadPrivateMessages.contains(peerID) {
                unreadPrivateMessages.remove(peerID)
                unreadPrivateMessages.insert(stableKeyHex)
            }
            selectedPrivateChatPeer = stableKeyHex
            objectWillChange.send()
        }

        DispatchQueue.main.async { [weak self] in

            self?.objectWillChange.send()
        }

        if let messages = privateChats[peerID] {
            for message in messages {

                if message.senderPeerID == peerID {
                    sentReadReceipts.remove(message.id)
                }
            }
        }
    }

    func didUpdatePeerList(_ peers: [PeerID]) {

        DispatchQueue.main.async {

            self.isConnected = !peers.isEmpty

            self.cleanupStaleUnreadPeerIDs()

            let meshPeers = peers.filter { peerID in
                self.meshService.isPeerConnected(peerID) || self.meshService.isPeerReachable(peerID)
            }
            let meshPeerSet = Set(meshPeers)

            if meshPeerSet.isEmpty {
                self.scheduleNetworkEmptyTimer()
            } else {
                self.invalidateNetworkEmptyTimer()

                let newPeers = meshPeerSet.subtracting(self.recentlySeenPeers)

                if !newPeers.isEmpty {

                    let cooldown = TransportConfig.networkNotificationCooldownSeconds
                    if Date().timeIntervalSince(self.lastNetworkNotificationTime) >= cooldown {

                        self.recentlySeenPeers.formUnion(newPeers)
                        self.lastNetworkNotificationTime = Date()
                        NotificationService.shared.sendNetworkAvailableNotification(peerCount: meshPeers.count)
                        SecureLogger.info(
                            "Sent bitchatters nearby notification for \(meshPeers.count) mesh peers (new: \(newPeers.count))",
                            category: .session
                        )
                    }
                    self.scheduleNetworkResetTimer()
                }
            }

            for peerID in peers {
                self.identityManager.registerEphemeralSession(peerID: peerID, handshakeState: .none)
            }

            self.updateEncryptionStatusForPeers()

            if self.selectedPrivateChatFingerprint != nil {
                self.updatePrivateChatPeerIfNeeded()
            }

        }
    }

    @MainActor
    private func cleanupStaleUnreadPeerIDs() {
        let currentPeerIDs = Set(unifiedPeerService.peers.map { $0.peerID })
        let staleIDs = unreadPrivateMessages.subtracting(currentPeerIDs)

        if !staleIDs.isEmpty {
            var idsToRemove: [PeerID] = []
            for staleID in staleIDs {

                if staleID.isGeoDM {

                    if let messages = privateChats[staleID], !messages.isEmpty {

                        continue
                    }
                }

                if staleID.isNoiseKeyHex {
                    if let messages = privateChats[staleID], !messages.isEmpty {

                        continue
                    }
                }

                idsToRemove.append(staleID)
                unreadPrivateMessages.remove(staleID)
            }

            if !idsToRemove.isEmpty {
                SecureLogger.debug("Cleaned up \(idsToRemove.count) stale unread peer IDs", category: .session)
            }
        }

        cleanupOldReadReceipts()
    }

    @MainActor
    private func scheduleNetworkResetTimer() {
        networkResetTimer?.invalidate()
        networkResetTimer = Timer.scheduledTimer(
            timeInterval: networkResetGraceSeconds,
            target: self,
            selector: #selector(onNetworkResetTimerFired(_:)),
            userInfo: nil,
            repeats: false
        )
    }

    @MainActor
    @objc private func onNetworkResetTimerFired(_ timer: Timer) {
        let activeMeshPeers = meshService
            .currentPeerSnapshots()
            .filter { snapshot in
                snapshot.isConnected || meshService.isPeerReachable(snapshot.peerID)
            }
        if activeMeshPeers.isEmpty {
            recentlySeenPeers.removeAll()
            SecureLogger.debug("⏱Network notification window reset after quiet period", category: .session)
        } else {
            SecureLogger.debug("⏱Skipped network notification reset; still seeing \(activeMeshPeers.count) mesh peers", category: .session)
        }
        networkResetTimer = nil
    }

    @MainActor
    private func scheduleNetworkEmptyTimer() {
        guard networkEmptyTimer == nil else { return }
        networkEmptyTimer = Timer.scheduledTimer(
            timeInterval: TransportConfig.uiMeshEmptyConfirmationSeconds,
            target: self,
            selector: #selector(onNetworkEmptyTimerFired(_:)),
            userInfo: nil,
            repeats: false
        )
        SecureLogger.debug("⏳ Mesh empty — waiting before resetting notification state", category: .session)
    }

    @MainActor
    private func invalidateNetworkEmptyTimer() {
        if networkEmptyTimer != nil {
            networkEmptyTimer?.invalidate()
            networkEmptyTimer = nil
        }
    }

    @MainActor
    @objc private func onNetworkEmptyTimerFired(_ timer: Timer) {
        let activeMeshPeers = meshService
            .currentPeerSnapshots()
            .filter { snapshot in
                snapshot.isConnected || meshService.isPeerReachable(snapshot.peerID)
            }
        if activeMeshPeers.isEmpty {
            recentlySeenPeers.removeAll()
            SecureLogger.debug("⏳ Mesh empty — notification state reset after confirmation", category: .session)
        } else {
            SecureLogger.debug("⏳ Mesh empty timer cancelled; \(activeMeshPeers.count) mesh peers detected again", category: .session)
        }
        networkEmptyTimer = nil
    }

    private func cleanupOldReadReceipts() {

        if isStartupPhase || privateChats.isEmpty {
            return
        }

        var validMessageIDs = Set<String>()
        for (_, messages) in privateChats {
            for message in messages {
                validMessageIDs.insert(message.id)
            }
        }

        let oldCount = sentReadReceipts.count
        sentReadReceipts = sentReadReceipts.intersection(validMessageIDs)

        let removedCount = oldCount - sentReadReceipts.count
        if removedCount > 0 {
            SecureLogger.debug("Cleaned up \(removedCount) old read receipts", category: .session)
        }
    }

    func parseMentions(from content: String) -> [String] {

        let regex = Patterns.mention
        let nsContent = content as NSString
        let nsLen = nsContent.length
        let matches = regex.matches(in: content, options: [], range: NSRange(location: 0, length: nsLen))

        var mentions: [String] = []
        let peerNicknames = meshService.getPeerNicknames()

        var validTokens = Set(peerNicknames.values)

        validTokens.insert(nickname)
        let selfSuffixToken = nickname + "#" + String(meshService.myPeerID.id.prefix(4))
        validTokens.insert(selfSuffixToken)

        for match in matches {
            if let range = Range(match.range(at: 1), in: content) {
                let mentionedName = String(content[range])

                if validTokens.contains(mentionedName) {
                    mentions.append(mentionedName)
                }
            }
        }

        return Array(Set(mentions))
    }

    func isFavorite(fingerprint: String) -> Bool {
        return identityManager.isFavorite(fingerprint: fingerprint)
    }

    func didReceiveReadReceipt(_ receipt: ReadReceipt) {

        updateMessageDeliveryStatus(receipt.originalMessageID, status: .read(by: receipt.readerNickname, at: receipt.timestamp))
    }

    func didUpdateMessageDeliveryStatus(_ messageID: String, status: DeliveryStatus) {
        updateMessageDeliveryStatus(messageID, status: status)
    }

    func updateMessageDeliveryStatus(_ messageID: String, status: DeliveryStatus) {

        func shouldSkipUpdate(currentStatus: DeliveryStatus?, newStatus: DeliveryStatus) -> Bool {
            guard let current = currentStatus else { return false }

            switch (current, newStatus) {
            case (.read, .delivered):
                return true
            case (.read, .sent):
                return true
            default:
                return false
            }
        }

        if let index = messages.firstIndex(where: { $0.id == messageID }) {
            let currentStatus = messages[index].deliveryStatus
            if !shouldSkipUpdate(currentStatus: currentStatus, newStatus: status) {
                messages[index].deliveryStatus = status
            }
        }

        for (peerID, chatMessages) in privateChats {
            guard let index = chatMessages.firstIndex(where: { $0.id == messageID }) else { continue }

            let currentStatus = chatMessages[index].deliveryStatus
            guard !shouldSkipUpdate(currentStatus: currentStatus, newStatus: status) else { continue }

            privateChats[peerID]?[index].deliveryStatus = status
        }

        DispatchQueue.main.async { [weak self] in
            self?.objectWillChange.send()
        }

    }

    func addSystemMessage(_ content: String, timestamp: Date = Date()) {
        let systemMessage = BitchatMessage(
            sender: "system",
            content: content,
            timestamp: timestamp,
            isRelay: false
        )
        messages.append(systemMessage)
    }

    @MainActor
    func addMeshOnlySystemMessage(_ content: String) {
        let systemMessage = BitchatMessage(
            sender: "system",
            content: content,
            timestamp: Date(),
            isRelay: false
        )
        timelineStore.append(systemMessage, to: .mesh)
        refreshVisibleMessages()
        trimMessagesIfNeeded()
        objectWillChange.send()
    }

    @MainActor
    func addPublicSystemMessage(_ content: String) {
        let systemMessage = BitchatMessage(
            sender: "system",
            content: content,
            timestamp: Date(),
            isRelay: false
        )
        timelineStore.append(systemMessage, to: activeChannel)
        refreshVisibleMessages(from: activeChannel)

        let contentKey = deduplicationService.normalizedContentKey(systemMessage.content)
        deduplicationService.recordContentKey(contentKey, timestamp: systemMessage.timestamp)
        trimMessagesIfNeeded()
        objectWillChange.send()
    }

    @MainActor
    func addGeohashOnlySystemMessage(_ content: String) {
        if case .location = activeChannel {
            addPublicSystemMessage(content)
        } else {

            timelineStore.queueGeohashSystemMessage(content)
        }
    }

    @MainActor
    func sendPublicRaw(_ content: String) {
        if case .location(let ch) = activeChannel {
            Task { @MainActor in
                do {
                    let identity = try idBridge.deriveIdentity(forGeohash: ch.geohash)
                    let event = try NostrProtocol.createEphemeralGeohashEvent(
                        content: content,
                        geohash: ch.geohash,
                        senderIdentity: identity,
                        nickname: self.nickname,
                        teleported: LocationChannelManager.shared.teleported
                    )
                    let targetRelays = GeoRelayDirectory.shared.closestRelays(toGeohash: ch.geohash, count: 5)
                    if targetRelays.isEmpty {
                        NostrRelayManager.shared.sendEvent(event)
                    } else {
                        NostrRelayManager.shared.sendEvent(event, to: targetRelays)
                    }
                } catch {
                    SecureLogger.error("Failed to send geohash raw message: \(error)", category: .session)
                }
            }
            return
        }

        meshService.sendMessage(content,
                                mentions: [],
                                messageID: UUID().uuidString,
                                timestamp: Date())
    }

    static func base64URLDecode(_ s: String) -> Data? {
        var str = s.replacingOccurrences(of: "-", with: "+")
                    .replacingOccurrences(of: "_", with: "/")

        let rem = str.count % 4
        if rem > 0 { str.append(String(repeating: "=", count: 4 - rem)) }
        return Data(base64Encoded: str)
    }

    @MainActor
    func handlePublicMessage(_ message: BitchatMessage) {
        let finalMessage = processActionMessage(message)

        if isMessageBlocked(finalMessage) { return }

        let isGeo = finalMessage.senderPeerID?.isGeoChat == true

        let shouldRateLimit = finalMessage.sender != "system" || finalMessage.senderPeerID != nil
        if shouldRateLimit {
            let senderKey = normalizedSenderKey(for: finalMessage)
            let contentKey = deduplicationService.normalizedContentKey(finalMessage.content)
            if !publicRateLimiter.allow(senderKey: senderKey, contentKey: contentKey) { return }
        }

        if finalMessage.sender != "system" && finalMessage.content.count > 16000 { return }

        if !isGeo && finalMessage.sender != "system" {
            timelineStore.append(finalMessage, to: .mesh)
        }

        if isGeo && finalMessage.sender != "system" {
            if let gh = currentGeohash {
                _ = timelineStore.appendIfAbsent(finalMessage, toGeohash: gh)
            }
        }

        let isSystem = finalMessage.sender == "system"
        let channelMatches: Bool = {
            switch activeChannel {
            case .mesh: return !isGeo || isSystem
            case .location: return isGeo || isSystem
            }
        }()

        guard channelMatches else { return }

        if !finalMessage.content.trimmed.isEmpty, !messages.contains(where: { $0.id == finalMessage.id }) {
            publicMessagePipeline.enqueue(finalMessage)
        }
    }

        func checkForMentions(_ message: BitchatMessage) {

    var myTokens: Set<String> = [nickname]
    let meshPeers = meshService.getPeerNicknames()
    let collisions = meshPeers.values.filter { $0.hasPrefix(nickname + "#") }
    if !collisions.isEmpty {
        let suffix = "#" + String(meshService.myPeerID.id.prefix(4))
        myTokens = [nickname + suffix]
    }
    let isMentioned = (message.mentions?.contains { myTokens.contains($0) } ?? false)

    if isMentioned && message.sender != nickname {
        SecureLogger.info("Mention from \(message.sender)", category: .session)
        NotificationService.shared.sendMentionNotification(from: message.sender, message: message.content)
    }
}

    func sendHapticFeedback(for message: BitchatMessage) {        #if os(iOS)
        guard UIApplication.shared.applicationState == .active else { return }

        var tokens: [String] = [nickname]
        #if os(iOS)
        switch activeChannel {
        case .location(let ch):
            if let id = try? idBridge.deriveIdentity(forGeohash: ch.geohash) {
                let d = String(id.publicKeyHex.suffix(4))
                tokens.append(nickname + "#" + d)
            }
        case .mesh:
            break
        }
        #endif

        let hugsMe = tokens.contains { message.content.contains("hugs \($0)") } || message.content.contains("hugs you")
        let slapsMe = tokens.contains { message.content.contains("slaps \($0) around") } || message.content.contains("slaps you around")

        let isHugForMe = message.content.contains("") && hugsMe
        let isSlapForMe = message.content.contains("") && slapsMe

        if isHugForMe && message.sender != nickname {

            let impactFeedback = UIImpactFeedbackGenerator(style: .medium)
            impactFeedback.prepare()

            for i in 0..<8 {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * TransportConfig.uiBatchDispatchStaggerSeconds) {
                    impactFeedback.impactOccurred()
                }
            }
        } else if isSlapForMe && message.sender != nickname {

            let impactFeedback = UIImpactFeedbackGenerator(style: .heavy)
            impactFeedback.prepare()
            impactFeedback.impactOccurred()
        }
        #endif
    }
}

extension ChatViewModel: PublicMessagePipelineDelegate {
    func pipelineCurrentMessages(_ pipeline: PublicMessagePipeline) -> [BitchatMessage] {
        messages
    }

    func pipeline(_ pipeline: PublicMessagePipeline, setMessages messages: [BitchatMessage]) {
        self.messages = messages
    }

    func pipeline(_ pipeline: PublicMessagePipeline, normalizeContent content: String) -> String {
        deduplicationService.normalizedContentKey(content)
    }

    func pipeline(_ pipeline: PublicMessagePipeline, contentTimestampForKey key: String) -> Date? {
        deduplicationService.contentTimestamp(forKey: key)
    }

    func pipeline(_ pipeline: PublicMessagePipeline, recordContentKey key: String, timestamp: Date) {
        deduplicationService.recordContentKey(key, timestamp: timestamp)
    }

    func pipelineTrimMessages(_ pipeline: PublicMessagePipeline) {
        trimMessagesIfNeeded()
    }

    func pipelinePrewarmMessage(_ pipeline: PublicMessagePipeline, message: BitchatMessage) {
        _ = formatMessageAsText(message, colorScheme: currentColorScheme)
    }

    func pipelineSetBatchingState(_ pipeline: PublicMessagePipeline, isBatching: Bool) {
        isBatchingPublic = isBatching
    }
}
