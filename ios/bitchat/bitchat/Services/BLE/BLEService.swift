import BitLogger
import BitFoundation
import Foundation
import CoreBluetooth
import Combine
import CryptoKit
#if os(iOS)
import UIKit
#endif

final class BLEService: NSObject {

    #if DEBUG
    static let serviceUUID = CBUUID(string: "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A")
    #else
    static let serviceUUID = CBUUID(string: "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
    #endif
    static let characteristicUUID = CBUUID(string: "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
    private static let centralRestorationID = "com.cybersiren.ios.ble.central"
    private static let peripheralRestorationID = "com.cybersiren.ios.ble.peripheral"

    private let defaultFragmentSize = TransportConfig.bleDefaultFragmentSize
    private let bleMaxMTU = 512
    private let maxMessageLength = InputValidator.Limits.maxMessageLength
    private let messageTTL: UInt8 = TransportConfig.messageTTLDefault

    private let maxInFlightAssemblies = TransportConfig.bleMaxInFlightAssemblies
    private let highDegreeThreshold = TransportConfig.bleHighDegreeThreshold

    private struct PeripheralState {
        let peripheral: CBPeripheral
        var characteristic: CBCharacteristic?
        var peerID: PeerID?
        var isConnecting: Bool = false
        var isConnected: Bool = false
        var lastConnectionAttempt: Date? = nil
        var assembler = NotificationStreamAssembler()
    }
    private var peripherals: [String: PeripheralState] = [:]
    private var peerToPeripheralUUID: [PeerID: String] = [:]

    private var subscribedCentrals: [CBCentral] = []
    private var centralToPeerID: [String: PeerID] = [:]

    private struct SubscriptionRateLimitState {
        var lastAnnounceTime: Date
        var attemptCount: Int
        var currentBackoffSeconds: TimeInterval
    }
    private var centralSubscriptionRateLimits: [String: SubscriptionRateLimitState] = [:]

    private struct PeerInfo {
        let peerID: PeerID
        var nickname: String
        var isConnected: Bool
        var noisePublicKey: Data?
        var signingPublicKey: Data?
        var isVerifiedNickname: Bool
        var lastSeen: Date
    }
    private var peers: [PeerID: PeerInfo] = [:]
    private var currentPeerIDs: [PeerID] {
        Array(peers.keys)
    }

    private let messageDeduplicator = MessageDeduplicator()
    private var selfBroadcastMessageIDs: [String: (id: String, timestamp: Date)] = [:]
    private lazy var mediaDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return formatter
    }()
    private let meshTopology = MeshTopologyTracker()

    private struct FragmentKey: Hashable { let sender: UInt64; let id: UInt64 }
    private var incomingFragments: [FragmentKey: [Int: Data]] = [:]
    private var fragmentMetadata: [FragmentKey: (type: UInt8, total: Int, timestamp: Date)] = [:]
    private struct ActiveTransferState {
        let totalFragments: Int
        var sentFragments: Int
        var workItems: [DispatchWorkItem]
    }
    private var activeTransfers: [String: ActiveTransferState] = [:]

    private var recentConnectTimeouts: [String: Date] = [:]

    private var lastAnnounceSent = Date.distantPast
    private let announceMinInterval: TimeInterval = TransportConfig.bleAnnounceMinInterval

    #if os(iOS)
    private var isAppActive: Bool = true
    #endif

    private var centralManager: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var characteristic: CBMutableCharacteristic?

    private var noiseService: NoiseEncryptionService
    private let identityManager: SecureIdentityStateManagerProtocol
    private let keychain: KeychainManagerProtocol
    private let idBridge: NostrIdentityBridge
    private var myPeerIDData: Data = Data()

    private let messageQueue = DispatchQueue(label: "mesh.message", attributes: .concurrent)
    private let collectionsQueue = DispatchQueue(label: "mesh.collections", attributes: .concurrent)
    private let messageQueueKey = DispatchSpecificKey<Void>()
    private let bleQueue = DispatchQueue(label: "mesh.bluetooth", qos: .userInitiated)
    private let bleQueueKey = DispatchSpecificKey<Void>()

    private var pendingMessagesAfterHandshake: [PeerID: [(content: String, messageID: String)]] = [:]

    private var pendingNoisePayloadsAfterHandshake: [PeerID: [Data]] = [:]

    private var pendingNotifications: [(data: Data, centrals: [CBCentral]?)] = []

    private var pendingWriteBuffers: [String: Data] = [:]

    private var scheduledRelays: [String: DispatchWorkItem] = [:]

    private var recentPacketTimestamps: [Date] = []

    private enum LinkID: Hashable {
        case peripheral(String)
        case central(String)
    }
    private var ingressByMessageID: [String: (link: LinkID, timestamp: Date)] = [:]

    private struct OutboundPriority: Comparable {
        let level: Int
        let suborder: Int

        static let high = OutboundPriority(level: 0, suborder: 0)
        static func fragment(totalFragments: Int) -> OutboundPriority {
            OutboundPriority(level: 1, suborder: max(1, min(totalFragments, Int(UInt16.max))))
        }
        static let fileTransfer = OutboundPriority(level: 2, suborder: Int.max - 1)
        static let low = OutboundPriority(level: 2, suborder: Int.max)

        static func < (lhs: OutboundPriority, rhs: OutboundPriority) -> Bool {
            if lhs.level != rhs.level { return lhs.level < rhs.level }
            return lhs.suborder < rhs.suborder
        }
    }
    private struct PendingWrite {
        let priority: OutboundPriority
        let data: Data
    }
    private struct PendingFragmentTransfer {
        let packet: BitchatPacket
        let pad: Bool
        let maxChunk: Int?
        let directedPeer: PeerID?
        let transferId: String?
    }
    private var pendingPeripheralWrites: [String: [PendingWrite]] = [:]
    private var pendingFragmentTransfers: [PendingFragmentTransfer] = []

    private var recentDisconnectNotifies: [PeerID: Date] = [:]

    private var pendingDirectedRelays: [PeerID: [String: (packet: BitchatPacket, enqueuedAt: Date)]] = [:]

    private var lastReconnectLogAt: [PeerID: Date] = [:]

    private var gossipSyncManager: GossipSyncManager?
    private let requestSyncManager = RequestSyncManager()

    private var maintenanceTimer: DispatchSourceTimer?
    private var maintenanceCounter = 0

    private let maxCentralLinks = TransportConfig.bleMaxCentralLinks
    private let connectRateLimitInterval: TimeInterval = TransportConfig.bleConnectRateLimitInterval
    private var lastGlobalConnectAttempt: Date = .distantPast
    private struct ConnectionCandidate {
        let peripheral: CBPeripheral
        let rssi: Int
        let name: String
        let isConnectable: Bool
        let discoveredAt: Date
    }
    private var connectionCandidates: [ConnectionCandidate] = []
    private var failureCounts: [String: Int] = [:]
    private var lastIsolatedAt: Date? = nil
    private var dynamicRSSIThreshold: Int = TransportConfig.bleDynamicRSSIThresholdDefault

    private var scanDutyTimer: DispatchSourceTimer?
    private var dutyEnabled: Bool = true
    private var dutyOnDuration: TimeInterval = TransportConfig.bleDutyOnDuration
    private var dutyOffDuration: TimeInterval = TransportConfig.bleDutyOffDuration
    private var dutyActive: Bool = false

    private var lastPeerPublishAt: Date = .distantPast
    private var peerPublishPending: Bool = false
    private let peerPublishMinInterval: TimeInterval = 0.1
    private func requestPeerDataPublish() {
        let now = Date()
        let elapsed = now.timeIntervalSince(lastPeerPublishAt)
        if elapsed >= peerPublishMinInterval {
            lastPeerPublishAt = now
            publishFullPeerData()
        } else if !peerPublishPending {
            peerPublishPending = true
            let delay = peerPublishMinInterval - elapsed
            messageQueue.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self = self else { return }
                self.lastPeerPublishAt = Date()
                self.peerPublishPending = false
                self.publishFullPeerData()
            }
        }
    }

    init(
        keychain: KeychainManagerProtocol,
        idBridge: NostrIdentityBridge,
        identityManager: SecureIdentityStateManagerProtocol,
        initializeBluetoothManagers: Bool = true
    ) {
        self.keychain = keychain
        self.idBridge = idBridge
        noiseService = NoiseEncryptionService(keychain: keychain)
        self.identityManager = identityManager
        super.init()

        configureNoiseServiceCallbacks(for: noiseService)
        refreshPeerIdentity()

        messageQueue.setSpecific(key: messageQueueKey, value: ())

        #if os(iOS)

        if Thread.isMainThread {
            isAppActive = UIApplication.shared.applicationState == .active
        } else {
            DispatchQueue.main.sync {
                isAppActive = UIApplication.shared.applicationState == .active
            }
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        #endif

        bleQueue.setSpecific(key: bleQueueKey, value: ())

        if initializeBluetoothManagers {

            #if os(iOS)
            let centralOptions: [String: Any] = [
                CBCentralManagerOptionRestoreIdentifierKey: BLEService.centralRestorationID
            ]
            centralManager = CBCentralManager(delegate: self, queue: bleQueue, options: centralOptions)

            let peripheralOptions: [String: Any] = [
                CBPeripheralManagerOptionRestoreIdentifierKey: BLEService.peripheralRestorationID
            ]
            peripheralManager = CBPeripheralManager(delegate: self, queue: bleQueue, options: peripheralOptions)
            #else
            centralManager = CBCentralManager(delegate: self, queue: bleQueue)
            peripheralManager = CBPeripheralManager(delegate: self, queue: bleQueue)
            #endif
        }

        let timer = DispatchSource.makeTimerSource(queue: bleQueue)
        timer.schedule(deadline: .now() + TransportConfig.bleMaintenanceInterval,
                       repeating: TransportConfig.bleMaintenanceInterval,
                       leeway: .seconds(TransportConfig.bleMaintenanceLeewaySeconds))
        timer.setEventHandler { [weak self] in
            self?.performMaintenance()
        }
        timer.resume()
        maintenanceTimer = timer

        requestPeerDataPublish()

        restartGossipManager()
    }

    private func restartGossipManager() {

        gossipSyncManager?.stop()

        let config = GossipSyncManager.Config(
            seenCapacity: TransportConfig.syncSeenCapacity,
            gcsMaxBytes: TransportConfig.syncGCSMaxBytes,
            gcsTargetFpr: TransportConfig.syncGCSTargetFpr,
            maxMessageAgeSeconds: TransportConfig.syncMaxMessageAgeSeconds,
            maintenanceIntervalSeconds: TransportConfig.syncMaintenanceIntervalSeconds,
            stalePeerCleanupIntervalSeconds: TransportConfig.syncStalePeerCleanupIntervalSeconds,
            stalePeerTimeoutSeconds: TransportConfig.syncStalePeerTimeoutSeconds,
            fragmentCapacity: TransportConfig.syncFragmentCapacity,
            fileTransferCapacity: TransportConfig.syncFileTransferCapacity,
            fragmentSyncIntervalSeconds: TransportConfig.syncFragmentIntervalSeconds,
            fileTransferSyncIntervalSeconds: TransportConfig.syncFileTransferIntervalSeconds,
            messageSyncIntervalSeconds: TransportConfig.syncMessageIntervalSeconds
        )

        let manager = GossipSyncManager(myPeerID: myPeerID, config: config, requestSyncManager: requestSyncManager)
        manager.delegate = self
        manager.start()
        gossipSyncManager = manager
    }

    deinit {
        maintenanceTimer?.cancel()
        scanDutyTimer?.cancel()
        scanDutyTimer = nil
        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()
        #if os(iOS)
        NotificationCenter.default.removeObserver(self)
        #endif
    }

    func resetIdentityForPanic(currentNickname: String) {
        messageQueue.sync(flags: .barrier) {
            pendingMessagesAfterHandshake.removeAll()
            pendingNoisePayloadsAfterHandshake.removeAll()
        }

        collectionsQueue.sync(flags: .barrier) {
            pendingPeripheralWrites.removeAll()
            pendingFragmentTransfers.removeAll()
            pendingNotifications.removeAll()
            pendingDirectedRelays.removeAll()
            ingressByMessageID.removeAll()
            recentPacketTimestamps.removeAll()
            scheduledRelays.values.forEach { $0.cancel() }
            scheduledRelays.removeAll()
        }

        bleQueue.sync {
            pendingWriteBuffers.removeAll()
            recentConnectTimeouts.removeAll()
        }
        recentDisconnectNotifies.removeAll()

        noiseService.clearEphemeralStateForPanic()
        noiseService.clearPersistentIdentity()

        let newNoise = NoiseEncryptionService(keychain: keychain)
        noiseService = newNoise
        configureNoiseServiceCallbacks(for: newNoise)
        refreshPeerIdentity()
        restartGossipManager()

        setNickname(currentNickname)

        messageDeduplicator.reset()
        messageQueue.async(flags: .barrier) { [weak self] in
            self?.selfBroadcastMessageIDs.removeAll()
        }
        requestPeerDataPublish()
        startServices()
    }

    func sendMessage(_ content: String, mentions: [String] = [], to recipientID: PeerID? = nil, messageID: String? = nil, timestamp: Date? = nil) {

        if DispatchQueue.getSpecific(key: messageQueueKey) == nil {
            messageQueue.async { [weak self] in
                self?.sendMessage(content, mentions: mentions, to: recipientID, messageID: messageID, timestamp: timestamp)
            }
            return
        }

        guard content.count <= maxMessageLength else {
            SecureLogger.error("Message too long: \(content.count) chars", category: .session)
            return
        }

        if let recipientID {
            sendPrivateMessage(content, to: recipientID, messageID: messageID ?? UUID().uuidString)
            return
        }

        let sendDate = timestamp ?? Date()
        let sendTimestampMs = UInt64(sendDate.timeIntervalSince1970 * 1000)
        let basePacket = BitchatPacket(
            type: MessageType.message.rawValue,
            senderID: Data(hexString: myPeerID.id) ?? Data(),
            recipientID: nil,
            timestamp: sendTimestampMs,
            payload: Data(content.utf8),
            signature: nil,
            ttl: messageTTL
        )
        guard let signedPacket = noiseService.signPacket(basePacket) else {
            SecureLogger.error("Failed to sign public message", category: .security)
            return
        }

        let senderHex = signedPacket.senderID.hexEncodedString()
        let dedupID = "\(senderHex)-\(signedPacket.timestamp)-\(signedPacket.type)"
        messageDeduplicator.markProcessed(dedupID)
        if let messageID {
            selfBroadcastMessageIDs[dedupID] = (id: messageID, timestamp: sendDate)
        }

        broadcastPacket(signedPacket)

        gossipSyncManager?.onPublicPacketSeen(signedPacket)
    }

    weak var delegate: BitchatDelegate?
    weak var peerEventsDelegate: TransportPeerEventsDelegate?

    private let peerSnapshotSubject = PassthroughSubject<[TransportPeerSnapshot], Never>()
    var peerSnapshotPublisher: AnyPublisher<[TransportPeerSnapshot], Never> {
        peerSnapshotSubject.eraseToAnyPublisher()
    }

    func currentPeerSnapshots() -> [TransportPeerSnapshot] {
        collectionsQueue.sync {
            let snapshot = Array(peers.values)
            let resolvedNames = PeerDisplayNameResolver.resolve(
                snapshot.map { ($0.peerID, $0.nickname, $0.isConnected) },
                selfNickname: myNickname
            )
            return snapshot.map { info in
                TransportPeerSnapshot(
                    peerID: info.peerID,
                    nickname: resolvedNames[info.peerID] ?? info.nickname,
                    isConnected: info.isConnected,
                    noisePublicKey: info.noisePublicKey,
                    lastSeen: info.lastSeen
                )
            }
        }
    }

    var myPeerID = PeerID(str: "")
    var myNickname: String = "anon"

    func setNickname(_ nickname: String) {
        self.myNickname = nickname

        sendAnnounce(forceSend: true)
    }

    func startServices() {

        if centralManager?.state == .poweredOn {
            centralManager?.scanForPeripherals(
                withServices: [BLEService.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
            )
        }

        messageQueue.asyncAfter(deadline: .now() + TransportConfig.bleInitialAnnounceDelaySeconds) { [weak self] in
            self?.sendAnnounce(forceSend: true)
        }
    }

    func stopServices() {

        var leavePacket = BitchatPacket(
            type: MessageType.leave.rawValue,
            senderID: myPeerIDData,
            recipientID: nil,
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            payload: Data(),
            signature: nil,
            ttl: messageTTL
        )

        if let signed = noiseService.signPacket(leavePacket) {
            leavePacket = signed
        }

        if let data = leavePacket.toBinaryData(padding: false) {
            let leavePriority = priority(for: leavePacket, data: data)

            let (peripheralStates, centralsCount, char) = bleQueue.sync {
                (Array(peripherals.values), subscribedCentrals.count, characteristic)
            }

            for state in peripheralStates where state.isConnected {
                if let characteristic = state.characteristic {
                    writeOrEnqueue(data, to: state.peripheral, characteristic: characteristic, priority: leavePriority)
                }
            }

            if centralsCount > 0, let ch = char {
                peripheralManager?.updateValue(data, for: ch, onSubscribedCentrals: nil)
            }
        }

        let deadline = Date().addingTimeInterval(TransportConfig.bleThreadSleepWriteShortDelaySeconds)
        while Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.01))
        }

        collectionsQueue.sync(flags: .barrier) {
            pendingNotifications.removeAll()
        }

        maintenanceTimer?.cancel()
        maintenanceTimer = nil
        scanDutyTimer?.cancel()
        scanDutyTimer = nil

        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()

        let peripheralsToDisconnect = bleQueue.sync { Array(peripherals.values) }
        for state in peripheralsToDisconnect {
            centralManager?.cancelPeripheralConnection(state.peripheral)
        }
    }

    func emergencyDisconnectAll() {
        stopServices()

        let cancelledTransfers: [(id: String, items: [DispatchWorkItem])] = collectionsQueue.sync(flags: .barrier) {
            let entries = activeTransfers.map { ($0.key, $0.value.workItems) }
            peers.removeAll()
            incomingFragments.removeAll()
            fragmentMetadata.removeAll()
            activeTransfers.removeAll()

            pendingMessagesAfterHandshake.removeAll()
            pendingNoisePayloadsAfterHandshake.removeAll()
            pendingDirectedRelays.removeAll()
            return entries
        }

        for entry in cancelledTransfers {
            entry.items.forEach { $0.cancel() }
            TransferProgressManager.shared.cancel(id: entry.id)
        }

        messageDeduplicator.reset()

        bleQueue.sync {
            peripherals.removeAll()
            peerToPeripheralUUID.removeAll()
            subscribedCentrals.removeAll()
            centralToPeerID.removeAll()
            centralSubscriptionRateLimits.removeAll()
        }
        meshTopology.reset()
    }

    func isPeerConnected(_ peerID: PeerID) -> Bool {

        let shortID = peerID.toShort()
        return collectionsQueue.sync { peers[shortID]?.isConnected ?? false }
    }

    func isPeerReachable(_ peerID: PeerID) -> Bool {

        let shortID = peerID.toShort()
        return collectionsQueue.sync {

            let meshAttached = peers.values.contains { $0.isConnected }
            guard let info = peers[shortID] else { return false }
            if info.isConnected { return true }
            guard meshAttached else { return false }

            let isVerified = info.isVerifiedNickname
            let retention: TimeInterval = isVerified ? TransportConfig.bleReachabilityRetentionVerifiedSeconds : TransportConfig.bleReachabilityRetentionUnverifiedSeconds
            return Date().timeIntervalSince(info.lastSeen) <= retention
        }
    }

    func peerNickname(peerID: PeerID) -> String? {
        collectionsQueue.sync {
            guard let peer = peers[peerID], peer.isConnected else { return nil }
            return peer.nickname
        }
    }

    func getPeerNicknames() -> [PeerID: String] {
        return collectionsQueue.sync {
            let connected = peers.filter { $0.value.isConnected }
            let tuples = connected.map { ($0.key, $0.value.nickname, true) }
            return PeerDisplayNameResolver.resolve(tuples, selfNickname: myNickname)
        }
    }

    func getFingerprint(for peerID: PeerID) -> String? {
        return collectionsQueue.sync {
            return peers[peerID]?.noisePublicKey?.sha256Fingerprint()
        }
    }

    func getNoiseSessionState(for peerID: PeerID) -> LazyHandshakeState {
        if noiseService.hasEstablishedSession(with: peerID) {
            return .established
        } else if noiseService.hasSession(with: peerID) {
            return .handshaking
        } else {
            return .none
        }
    }

    func triggerHandshake(with peerID: PeerID) {
        initiateNoiseHandshake(with: peerID)
    }

    func getNoiseService() -> NoiseEncryptionService {
        return noiseService
    }

    func getCurrentBluetoothState() -> CBManagerState {
        return centralManager?.state ?? .unknown
    }

    func cancelTransfer(_ transferId: String) {
        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            if let state = self.activeTransfers.removeValue(forKey: transferId) {
                state.workItems.forEach { $0.cancel() }
                TransferProgressManager.shared.cancel(id: transferId)
                SecureLogger.debug("Cancelled transfer \(transferId.prefix(8))…", category: .session)
                self.messageQueue.async { [weak self] in
                    self?.startNextPendingTransferIfNeeded()
                }
            } else if let pendingIndex = self.pendingFragmentTransfers.firstIndex(where: { $0.transferId == transferId }) {
                self.pendingFragmentTransfers.remove(at: pendingIndex)
                TransferProgressManager.shared.cancel(id: transferId)
                SecureLogger.debug("Removed pending transfer \(transferId.prefix(8))… before start", category: .session)
            }
        }
    }

    func sendMessage(_ content: String, mentions: [String]) {

        sendMessage(content, mentions: mentions, to: nil, messageID: nil, timestamp: nil)
    }

    func sendMessage(_ content: String, mentions: [String], messageID: String, timestamp: Date) {
        sendMessage(content, mentions: mentions, to: nil, messageID: messageID, timestamp: timestamp)
    }

    func sendPrivateMessage(_ content: String, to peerID: PeerID, recipientNickname: String, messageID: String) {
        sendPrivateMessage(content, to: peerID, messageID: messageID)
    }

    func sendFileBroadcast(_ filePacket: BitchatFilePacket, transferId: String) {
        messageQueue.async { [weak self] in
            guard let self = self else { return }
            guard let payload = filePacket.encode() else {
                SecureLogger.error("Failed to encode file packet for broadcast", category: .session)
                return
            }

            var packet = BitchatPacket(
                type: MessageType.fileTransfer.rawValue,
                senderID: self.myPeerIDData,
                recipientID: nil,
                timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                payload: payload,
                signature: nil,
                ttl: self.messageTTL,
                version: 2
            )

            if let signed = self.noiseService.signPacket(packet) {
                packet = signed
            } else {
                SecureLogger.error("Failed to sign file broadcast packet", category: .security)
                return
            }

            let senderHex = packet.senderID.hexEncodedString()
            let dedupID = "\(senderHex)-\(packet.timestamp)-\(packet.type)"
            self.messageDeduplicator.markProcessed(dedupID)

            SecureLogger.debug("Broadcasting file transfer payload bytes=\(payload.count)", category: .session)
            self.broadcastPacket(packet, transferId: transferId)
            self.gossipSyncManager?.onPublicPacketSeen(packet)
        }
    }

    func sendFilePrivate(_ filePacket: BitchatFilePacket, to peerID: PeerID, transferId: String) {
        messageQueue.async { [weak self] in
            guard let self = self else { return }
            guard let payload = filePacket.encode() else {
                SecureLogger.error("Failed to encode file packet for private send", category: .session)
                return
            }

            let targetID = peerID.toShort()
            guard let recipientData = Data(hexString: targetID.id) else {
                SecureLogger.error("Invalid recipient peer ID for file transfer: \(peerID)", category: .session)
                return
            }

            var packet = BitchatPacket(
                type: MessageType.fileTransfer.rawValue,
                senderID: self.myPeerIDData,
                recipientID: recipientData,
                timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                payload: payload,
                signature: nil,
                ttl: self.messageTTL,
                version: 2
            )

            if let signed = self.noiseService.signPacket(packet) {
                packet = signed
            }

            SecureLogger.debug("Sending private file transfer to \(peerID.id.prefix(8))… bytes=\(payload.count)", category: .session)
            self.broadcastPacket(packet, transferId: transferId)
        }
    }

    func sendReadReceipt(_ receipt: ReadReceipt, to peerID: PeerID) {

        var payload = Data([NoisePayloadType.readReceipt.rawValue])
        payload.append(contentsOf: receipt.originalMessageID.utf8)

        if noiseService.hasEstablishedSession(with: peerID) {
            SecureLogger.debug("Sending READ receipt for message \(receipt.originalMessageID) to \(peerID)", category: .session)
            do {
                let encrypted = try noiseService.encrypt(payload, for: peerID)
                let packet = BitchatPacket(
                    type: MessageType.noiseEncrypted.rawValue,
                    senderID: myPeerIDData,
                    recipientID: Data(hexString: peerID.id),
                    timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                    payload: encrypted,
                    signature: nil,
                    ttl: messageTTL
                )
                broadcastPacket(packet)
            } catch {
                SecureLogger.error("Failed to send read receipt: \(error)")
            }
        } else {

            collectionsQueue.async(flags: .barrier) { [weak self] in
                guard let self = self else { return }
                self.pendingNoisePayloadsAfterHandshake[peerID, default: []].append(payload)
            }
            if !noiseService.hasSession(with: peerID) { initiateNoiseHandshake(with: peerID) }
            SecureLogger.debug("Queued READ receipt for \(peerID) until handshake completes", category: .session)
        }
    }

    private enum ConnectionSource {
        case peripheral(String)
        case central(String)
        case unknown
    }

    private func validatePacket(_ packet: BitchatPacket, from peerID: PeerID, connectionSource: ConnectionSource = .unknown) -> Bool {
        let currentTime = UInt64(Date().timeIntervalSince1970 * 1000)

        let isRSR = packet.isRSR
        var skipTimestampCheck = false

        if isRSR {
            if requestSyncManager.isValidResponse(from: peerID, isRSR: true) {
                SecureLogger.debug("Valid RSR packet from \(peerID.id.prefix(8))… - skipping timestamp check", category: .security)
                skipTimestampCheck = true
            } else {
                SecureLogger.warning("Invalid or unsolicited RSR packet from \(peerID.id.prefix(8))… - rejecting", category: .security)
                return false
            }
        }

        if !skipTimestampCheck {
            let maxSkew: UInt64 = 120_000
            let packetTime = packet.timestamp
            let skew = (packetTime > currentTime) ? (packetTime - currentTime) : (currentTime - packetTime)

            if skew > maxSkew {
                SecureLogger.warning("Packet timestamp skewed by \(skew)ms (max \(maxSkew)ms) from \(peerID.id.prefix(8))…", category: .security)
                return false
            }
        }

        return true
    }

    private func broadcastPacket(_ packet: BitchatPacket, transferId: String? = nil) {

        let packetToSend: BitchatPacket
        if let recipientPeerID = PeerID(hexData: packet.recipientID) {
            packetToSend = applyRouteIfAvailable(packet, to: recipientPeerID)
        } else {
            packetToSend = packet
        }

        let padForBLE = padPolicy(for: packetToSend.type)
        if packetToSend.type == MessageType.fileTransfer.rawValue {
            sendFragmentedPacket(packetToSend, pad: padForBLE, maxChunk: nil, directedOnlyPeer: nil, transferId: transferId)
            return
        }
        guard let data = packetToSend.toBinaryData(padding: padForBLE) else {
            SecureLogger.error("Failed to convert packet to binary data", category: .session)
            return
        }
        if packetToSend.type == MessageType.noiseEncrypted.rawValue {
            sendEncrypted(packetToSend, data: data, pad: padForBLE)
            return
        }
        sendGenericBroadcast(packetToSend, data: data, pad: padForBLE)
    }

    private func padPolicy(for type: UInt8) -> Bool {
        switch MessageType(rawValue: type) {
        case .noiseEncrypted, .noiseHandshake:
            return true
        case .none, .announce, .message, .leave, .requestSync, .fragment, .fileTransfer:
            return false
        }
    }

    private func sendEncrypted(_ packet: BitchatPacket, data: Data, pad: Bool) {
        guard let recipientPeerID = PeerID(hexData: packet.recipientID) else { return }
        var sentEncrypted = false

        let outboundPriority = priority(for: packet, data: data)

        var peripheralMaxLen: Int?
        if let perUUID = (DispatchQueue.getSpecific(key: bleQueueKey) != nil) ? peerToPeripheralUUID[recipientPeerID] : bleQueue.sync(execute: { peerToPeripheralUUID[recipientPeerID] }) {
            if let state = (DispatchQueue.getSpecific(key: bleQueueKey) != nil) ? peripherals[perUUID] : bleQueue.sync(execute: { peripherals[perUUID] }) {
                peripheralMaxLen = state.peripheral.maximumWriteValueLength(for: .withoutResponse)
            }
        }
        var centralMaxLen: Int?
        do {
            let (centrals, mapping) = snapshotSubscribedCentrals()
            if let central = centrals.first(where: { mapping[$0.identifier.uuidString] == recipientPeerID }) {
                centralMaxLen = central.maximumUpdateValueLength
            }
        }
        if let pm = peripheralMaxLen, data.count > pm {
            let overhead = 13 + 8 + 8 + 13
            let chunk = max(64, pm - overhead)
            sendFragmentedPacket(packet, pad: pad, maxChunk: chunk, directedOnlyPeer: recipientPeerID)
            return
        }
        if let cm = centralMaxLen, data.count > cm {
            let overhead = 13 + 8 + 8 + 13
            let chunk = max(64, cm - overhead)
            sendFragmentedPacket(packet, pad: pad, maxChunk: chunk, directedOnlyPeer: recipientPeerID)
            return
        }

        if let peripheralUUID = (DispatchQueue.getSpecific(key: bleQueueKey) != nil) ? peerToPeripheralUUID[recipientPeerID] : bleQueue.sync(execute: { peerToPeripheralUUID[recipientPeerID] }),
           let state = (DispatchQueue.getSpecific(key: bleQueueKey) != nil) ? peripherals[peripheralUUID] : bleQueue.sync(execute: { peripherals[peripheralUUID] }),
           state.isConnected,
           let characteristic = state.characteristic {
            writeOrEnqueue(data, to: state.peripheral, characteristic: characteristic, priority: outboundPriority)
            sentEncrypted = true
        }

        if let characteristic = characteristic, !sentEncrypted {
            let (centrals, mapping) = snapshotSubscribedCentrals()
            for central in centrals where mapping[central.identifier.uuidString] == recipientPeerID {
                let success = peripheralManager?.updateValue(data, for: characteristic, onSubscribedCentrals: [central]) ?? false
                if success { sentEncrypted = true; break }
                enqueuePendingNotification(data: data, centrals: [central], context: "encrypted")
            }
        }

        if !sentEncrypted {

            sendOnAllLinks(packet: packet, data: data, pad: pad, directedOnlyPeer: recipientPeerID)
        }
    }

    private func sendGenericBroadcast(_ packet: BitchatPacket, data: Data, pad: Bool) {
        sendOnAllLinks(packet: packet, data: data, pad: pad, directedOnlyPeer: nil)
    }

    private func enqueuePendingNotification(data: Data, centrals: [CBCentral]?, context: String, attempt: Int = 0) {
        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            if self.pendingNotifications.count < TransportConfig.blePendingNotificationsCapCount {
                self.pendingNotifications.append((data: data, centrals: centrals))
                SecureLogger.debug("Queued \(context) packet for retry (pending=\(self.pendingNotifications.count))", category: .session)
                return
            }

            if attempt >= TransportConfig.bleNotificationRetryMaxAttempts {
                SecureLogger.error("Dropping \(context) packet after exhausting retry window (pending=\(self.pendingNotifications.count))", category: .session)
                return
            }

            let backoff = TransportConfig.bleNotificationRetryDelayMs * max(1, attempt + 1)
            let deadline = DispatchTime.now() + .milliseconds(backoff)
            self.messageQueue.asyncAfter(deadline: deadline) { [weak self] in
                self?.enqueuePendingNotification(data: data, centrals: centrals, context: context, attempt: attempt + 1)
            }
        }
    }

    private func sendOnAllLinks(packet: BitchatPacket, data: Data, pad: Bool, directedOnlyPeer: PeerID?) {

        let messageID = makeMessageID(for: packet)
        let ingressLink: LinkID? = collectionsQueue.sync { ingressByMessageID[messageID]?.link }
        let directedPeerHint: PeerID? = {
            if let explicit = directedOnlyPeer { return explicit }
            if let recipient = PeerID(str: packet.recipientID?.hexEncodedString()), !recipient.isEmpty {
                return recipient
            }
            return nil
        }()
        let outboundPriority = priority(for: packet, data: data)

        let states = snapshotPeripheralStates()
        var minCentralWriteLen: Int?
        for s in states where s.isConnected {
            let m = s.peripheral.maximumWriteValueLength(for: .withoutResponse)
            minCentralWriteLen = minCentralWriteLen.map { min($0, m) } ?? m
        }
        var snapshotCentrals: [CBCentral] = []
        if let _ = characteristic {
            let (centrals, _) = snapshotSubscribedCentrals()
            snapshotCentrals = centrals
        }
        var minNotifyLen: Int?
        if !snapshotCentrals.isEmpty {
            minNotifyLen = snapshotCentrals.map { $0.maximumUpdateValueLength }.min()
        }

        if packet.type != MessageType.fragment.rawValue,
           let minLen = [minCentralWriteLen, minNotifyLen].compactMap({ $0 }).min(),
           data.count > minLen {
            let overhead = 13 + 8 + 8 + 13
            let chunk = max(64, minLen - overhead)
            sendFragmentedPacket(packet, pad: pad, maxChunk: chunk, directedOnlyPeer: directedOnlyPeer)
            return
        }

        let connectedPeripheralIDs: [String] = states.filter { $0.isConnected }.map { $0.peripheral.identifier.uuidString }
        let subscribedCentrals: [CBCentral]
        var centralIDs: [String] = []
        if let _ = characteristic {
            let (centrals, _) = snapshotSubscribedCentrals()
            subscribedCentrals = centrals
            centralIDs = centrals.map { $0.identifier.uuidString }
        } else {
            subscribedCentrals = []
        }

        var allowedPeripheralIDs = connectedPeripheralIDs
        var allowedCentralIDs = centralIDs
        if let ingress = ingressLink {
            switch ingress {
            case .peripheral(let id):
                allowedPeripheralIDs.removeAll { $0 == id }
            case .central(let id):
                allowedCentralIDs.removeAll { $0 == id }
            }
        }

        var selectedPeripheralIDs = Set(allowedPeripheralIDs)
        var selectedCentralIDs = Set(allowedCentralIDs)
        if directedPeerHint == nil
            && packet.type != MessageType.fragment.rawValue
            && packet.type != MessageType.announce.rawValue
            && packet.type != MessageType.requestSync.rawValue {
            let kp = subsetSizeForFanout(allowedPeripheralIDs.count)
            let kc = subsetSizeForFanout(allowedCentralIDs.count)
            selectedPeripheralIDs = selectDeterministicSubset(ids: allowedPeripheralIDs, k: kp, seed: messageID)
            selectedCentralIDs = selectDeterministicSubset(ids: allowedCentralIDs, k: kc, seed: messageID)
        }

        if let only = directedPeerHint,
           selectedPeripheralIDs.isEmpty && selectedCentralIDs.isEmpty,
           (packet.type == MessageType.noiseEncrypted.rawValue || packet.type == MessageType.noiseHandshake.rawValue) {
            spoolDirectedPacket(packet, recipientPeerID: only)
        }

        for s in states where s.isConnected {
            let pid = s.peripheral.identifier.uuidString
            guard selectedPeripheralIDs.contains(pid) else { continue }
            if let ch = s.characteristic {
                writeOrEnqueue(data, to: s.peripheral, characteristic: ch, priority: outboundPriority)
            }
        }

        if let ch = characteristic {
            let targets = subscribedCentrals.filter { selectedCentralIDs.contains($0.identifier.uuidString) }
            if !targets.isEmpty {
                let success = peripheralManager?.updateValue(data, for: ch, onSubscribedCentrals: targets) ?? false
                if !success {

                    let context = packet.type == MessageType.fragment.rawValue ? "fragment" : "broadcast"
                    enqueuePendingNotification(data: data, centrals: targets, context: context)
                }
            }
        }
    }

    private func sendPacketDirected(_ packet: BitchatPacket, to peerID: PeerID) {
        guard let data = packet.toBinaryData(padding: false) else { return }
        sendOnAllLinks(packet: packet, data: data, pad: false, directedOnlyPeer: peerID)
    }

    private func spoolDirectedPacket(_ packet: BitchatPacket, recipientPeerID: PeerID) {
        let msgID = makeMessageID(for: packet)
        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            var byMsg = self.pendingDirectedRelays[recipientPeerID] ?? [:]
            if byMsg[msgID] == nil {
                byMsg[msgID] = (packet: packet, enqueuedAt: Date())
                self.pendingDirectedRelays[recipientPeerID] = byMsg
                SecureLogger.debug("Spooling directed packet for \(recipientPeerID) mid=\(msgID.prefix(8))…", category: .session)
            }
        }
    }

    private func flushDirectedSpool() {

        let toSend: [(String, BitchatPacket)] = collectionsQueue.sync(flags: .barrier) {
            var out: [(String, BitchatPacket)] = []
            let now = Date()
            for (recipient, dict) in pendingDirectedRelays {
                for (_, entry) in dict {
                    if now.timeIntervalSince(entry.enqueuedAt) <= TransportConfig.bleDirectedSpoolWindowSeconds {
                        out.append((recipient.id, entry.packet))
                    }
                }

                pendingDirectedRelays.removeValue(forKey: recipient)
            }
            return out
        }
        guard !toSend.isEmpty else { return }
        for (_, packet) in toSend {
            messageQueue.async { [weak self] in self?.broadcastPacket(packet) }
        }
    }

    private func handleFileTransfer(_ packet: BitchatPacket, from peerID: PeerID) {
        if peerID == myPeerID && packet.ttl != 0 { return }

        var accepted = false
        var senderNickname = ""

        let peersSnapshot = collectionsQueue.sync { peers }

        if peerID == myPeerID {
            accepted = true
            senderNickname = myNickname
        } else if let info = peersSnapshot[peerID], info.isVerifiedNickname {
            accepted = true
            senderNickname = info.nickname
            let hasCollision = peersSnapshot.values.contains { $0.isConnected && $0.nickname == info.nickname && $0.peerID != peerID } || (myNickname == info.nickname)
            if hasCollision {
                senderNickname += "#" + String(peerID.id.prefix(4))
            }
        } else if let info = peersSnapshot[peerID], info.isConnected {
            accepted = true
            senderNickname = info.nickname.isEmpty ? "anon" + String(peerID.id.prefix(4)) : info.nickname
            let hasCollision = peersSnapshot.values.contains { $0.isConnected && $0.nickname == info.nickname && $0.peerID != peerID } || (myNickname == info.nickname)
            if hasCollision {
                senderNickname += "#" + String(peerID.id.prefix(4))
            }
        } else if let signature = packet.signature, let packetData = packet.toBinaryDataForSigning() {
            let candidates = identityManager.getCryptoIdentitiesByPeerIDPrefix(peerID)
            for candidate in candidates {
                if let signingKey = candidate.signingPublicKey,
                   noiseService.verifySignature(signature, for: packetData, publicKey: signingKey) {
                    accepted = true
                    if let social = identityManager.getSocialIdentity(for: candidate.fingerprint) {
                        senderNickname = social.localPetname ?? social.claimedNickname
                    } else {
                        senderNickname = "anon" + String(peerID.id.prefix(4))
                    }
                    break
                }
            }
        }

        guard accepted else {
            SecureLogger.warning("Dropping file transfer from unverified or unknown peer \(peerID.id.prefix(8))…", category: .security)
            return
        }

        if let recipient = packet.recipientID {
            if PeerID(hexData: recipient) != myPeerID && !recipient.allSatisfy({ $0 == 0xFF }) {
                return
            }
        }

        if let recipient = packet.recipientID,
           recipient.allSatisfy({ $0 == 0xFF }) {
            gossipSyncManager?.onPublicPacketSeen(packet)
        } else if packet.recipientID == nil {
            gossipSyncManager?.onPublicPacketSeen(packet)
        }

        guard let filePacket = BitchatFilePacket.decode(packet.payload) else {
            SecureLogger.error("Failed to decode file transfer payload", category: .session)
            return
        }

        guard FileTransferLimits.isValidPayload(filePacket.content.count) else {
            SecureLogger.warning("Dropping file transfer exceeding size cap (\(filePacket.content.count) bytes)", category: .security)
            return
        }

        guard let mime = MimeType(filePacket.mimeType), mime.isAllowed else {
            SecureLogger.warning("MIME REJECT: '\(filePacket.mimeType ?? "<empty>")' not supported. Size=\(filePacket.content.count)b from \(peerID.id.prefix(8))...", category: .security)
            return
        }

        guard mime.matches(data: filePacket.content) else {
            let prefix = filePacket.content.prefix(20).map { String(format: "%02x", $0) }.joined(separator: " ")
            SecureLogger.warning("MAGIC REJECT: MIME='\(mime)' size=\(filePacket.content.count)b prefix=[\(prefix)] from \(peerID.id.prefix(8))...", category: .security)
            return
        }

        enforceIncomingFilesQuota(reservingBytes: filePacket.content.count)

        guard let destination = saveIncomingFile(
            data: filePacket.content,
            preferredName: filePacket.fileName,
            subdirectory: "\(mime.category.mediaDir)/incoming",
            fallbackExtension: mime.defaultExtension,
            defaultPrefix: mime.category.rawValue
        ) else {
            return
        }

        let isPrivateMessage = PeerID(hexData: packet.recipientID) == myPeerID

        if isPrivateMessage {
            updatePeerLastSeen(peerID)
        }

        let ts = Date(timeIntervalSince1970: Double(packet.timestamp) / 1000)
        let message = BitchatMessage(
            sender: senderNickname,
            content: "\(mime.category.messagePrefix)\(destination.lastPathComponent)",
            timestamp: ts,
            isRelay: false,
            originalSender: nil,
            isPrivate: isPrivateMessage,
            recipientNickname: nil,
            senderPeerID: peerID
        )

        SecureLogger.debug("Stored incoming media from \(peerID.id.prefix(8))… -> \(destination.lastPathComponent)", category: .session)

        notifyUI { [weak self] in
            self?.delegate?.didReceiveMessage(message)
        }
    }

    func sendFavoriteNotification(to peerID: PeerID, isFavorite: Bool) {
        SecureLogger.debug("sendFavoriteNotification called - peerID: \(peerID), isFavorite: \(isFavorite)", category: .session)

        var content = isFavorite ? "[FAVORITED]" : "[UNFAVORITED]"

        if let myNostrIdentity = try? idBridge.getCurrentNostrIdentity() {
            content += ":" + myNostrIdentity.npub
            SecureLogger.debug("Sending favorite notification with Nostr npub: \(myNostrIdentity.npub)", category: .session)
        }

        SecureLogger.debug("Sending favorite notification to \(peerID): \(content)", category: .session)
        sendPrivateMessage(content, to: peerID, messageID: UUID().uuidString)
    }

    func sendBroadcastAnnounce() {
        sendAnnounce()
    }

    func sendDeliveryAck(for messageID: String, to peerID: PeerID) {

        var payload = Data([NoisePayloadType.delivered.rawValue])
        payload.append(contentsOf: messageID.utf8)

        if noiseService.hasEstablishedSession(with: peerID) {
            do {
                let encrypted = try noiseService.encrypt(payload, for: peerID)
                let packet = BitchatPacket(
                    type: MessageType.noiseEncrypted.rawValue,
                    senderID: myPeerIDData,
                    recipientID: Data(hexString: peerID.id),
                    timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                    payload: encrypted,
                    signature: nil,
                    ttl: messageTTL
                )
                broadcastPacket(packet)
            } catch {
                SecureLogger.error("Failed to send delivery ACK: \(error)")
            }
        } else {

            collectionsQueue.async(flags: .barrier) { [weak self] in
                guard let self = self else { return }
                self.pendingNoisePayloadsAfterHandshake[peerID, default: []].append(payload)
            }
            if !noiseService.hasSession(with: peerID) { initiateNoiseHandshake(with: peerID) }
            SecureLogger.debug("Queued DELIVERED ack for \(peerID) until handshake completes", category: .session)
        }
    }

    private func handleLeave(_ packet: BitchatPacket, from peerID: PeerID) {
        _ = collectionsQueue.sync(flags: .barrier) {

            peers.removeValue(forKey: peerID)
        }

        gossipSyncManager?.removeAnnouncementForPeer(peerID)

        notifyUI { [weak self] in
            guard let self = self else { return }

            let currentPeerIDs = self.collectionsQueue.sync { Array(self.peers.keys) }

            self.delegate?.didDisconnectFromPeer(peerID)
            self.delegate?.didUpdatePeerList(currentPeerIDs)
        }
    }

    private func applicationFilesDirectory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let filesDir = base.appendingPathComponent("files", isDirectory: true)
        try FileManager.default.createDirectory(at: filesDir, withIntermediateDirectories: true, attributes: nil)
        return filesDir
    }

    private func sanitizeFileName(_ name: String?, defaultName: String, fallbackExtension: String?) -> String {
        var candidate = name ?? ""

        candidate = candidate.replacingOccurrences(of: "\0", with: "")

        candidate = candidate.precomposedStringWithCanonicalMapping

        candidate = candidate.replacingOccurrences(of: "/", with: "_")
        candidate = candidate.replacingOccurrences(of: "\\", with: "_")

        let invalid = CharacterSet(charactersIn: "<>:\"|?*\0").union(.controlCharacters)
        candidate = candidate.components(separatedBy: invalid).joined(separator: "_")

        candidate = candidate.trimmed
        if candidate.isEmpty { candidate = defaultName }

        if candidate.hasPrefix(".") {
            candidate = "_" + candidate
        }

        if candidate.count > 120 {
            let ext = (candidate as NSString).pathExtension
            let base = (candidate as NSString).deletingPathExtension
            if ext.isEmpty {
                candidate = String(candidate.prefix(120))
            } else {
                let maxBase = max(10, 120 - ext.count - 1)
                candidate = String(base.prefix(maxBase)) + "." + ext
            }
        }

        if let fallbackExtension = fallbackExtension, (candidate as NSString).pathExtension.isEmpty {
            candidate += ".\(fallbackExtension)"
        }

        if candidate.isEmpty { candidate = defaultName }
        return candidate
    }

    private func uniqueFileURL(in directory: URL, fileName: String) -> URL {
        var candidate = directory.appendingPathComponent(fileName)

        if !candidate.path.hasPrefix(directory.path) {
            SecureLogger.warning("Path traversal blocked: \(fileName)", category: .security)
            return directory.appendingPathComponent("blocked_\(UUID().uuidString)")
        }

        if !FileManager.default.fileExists(atPath: candidate.path) {
            return candidate
        }

        let baseName = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension
        var counter = 1

        while counter < 100 {
            let newName = ext.isEmpty ? "\(baseName) (\(counter))" : "\(baseName) (\(counter)).\(ext)"
            candidate = directory.appendingPathComponent(newName)

            guard candidate.path.hasPrefix(directory.path) else {
                return directory.appendingPathComponent("blocked_\(UUID().uuidString)")
            }

            if !FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            counter += 1
        }

        return directory.appendingPathComponent("\(baseName)_\(UUID().uuidString).\(ext.isEmpty ? "dat" : ext)")
    }

    private func saveIncomingFile(data: Data, preferredName: String?, subdirectory: String, fallbackExtension: String?, defaultPrefix: String) -> URL? {
        do {
            let base = try applicationFilesDirectory().appendingPathComponent(subdirectory, isDirectory: true)
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true, attributes: nil)
            let timestamp = mediaDateFormatter.string(from: Date())
            let defaultName = "\(defaultPrefix)_\(timestamp)"
            let sanitized = sanitizeFileName(preferredName, defaultName: defaultName, fallbackExtension: fallbackExtension)
            let destination = uniqueFileURL(in: base, fileName: sanitized)
            try data.write(to: destination, options: .atomic)
            return destination
        } catch {
            SecureLogger.error("Failed to persist incoming media: \(error)", category: .session)
            return nil
        }
    }

    private static let incomingFilesQuota: Int64 = 100 * 1024 * 1024

    private func enforceIncomingFilesQuota(reservingBytes: Int) {
        do {
            let base = try applicationFilesDirectory()
            let incomingDirs = [
                base.appendingPathComponent("voicenotes/incoming", isDirectory: true),
                base.appendingPathComponent("images/incoming", isDirectory: true),
                base.appendingPathComponent("files/incoming", isDirectory: true)
            ]

            var allFiles: [(url: URL, size: Int64, modified: Date)] = []
            let fileManager = FileManager.default

            for dir in incomingDirs {
                guard fileManager.fileExists(atPath: dir.path) else { continue }
                guard let contents = try? fileManager.contentsOfDirectory(
                    at: dir,
                    includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
                    options: [.skipsHiddenFiles]
                ) else { continue }

                for fileURL in contents {
                    guard let attrs = try? fileURL.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]),
                          let size = attrs.fileSize,
                          let modified = attrs.contentModificationDate else { continue }
                    allFiles.append((url: fileURL, size: Int64(size), modified: modified))
                }
            }

            let currentUsage = allFiles.reduce(0) { $0 + $1.size }
            let targetUsage = Self.incomingFilesQuota - Int64(reservingBytes)

            guard currentUsage > targetUsage else { return }

            let sortedFiles = allFiles.sorted { $0.modified < $1.modified }
            var freedSpace: Int64 = 0
            let needToFree = currentUsage - targetUsage

            for file in sortedFiles {
                guard freedSpace < needToFree else { break }
                do {
                    try fileManager.removeItem(at: file.url)
                    freedSpace += file.size
                    SecureLogger.debug("BCH-01-002: Deleted old incoming file to free space: \(file.url.lastPathComponent)", category: .security)
                } catch {
                    SecureLogger.warning("Failed to delete old file for quota: \(error)", category: .security)
                }
            }

            if freedSpace > 0 {
                SecureLogger.info("BCH-01-002: Freed \(ByteCountFormatter.string(fromByteCount: freedSpace, countStyle: .file)) to stay within incoming files quota", category: .security)
            }
        } catch {
            SecureLogger.warning("Could not enforce storage quota: \(error)", category: .security)
        }
    }

    private func sendAnnounce(forceSend: Bool = false) {

        let now = Date()
        let timeSinceLastAnnounce = now.timeIntervalSince(lastAnnounceSent)

        let minInterval = forceSend ? TransportConfig.bleForceAnnounceMinIntervalSeconds : announceMinInterval

        if timeSinceLastAnnounce < minInterval {

            return
        }
        lastAnnounceSent = now

        let noisePub = noiseService.getStaticPublicKeyData()
        let signingPub = noiseService.getSigningPublicKeyData()

        let connectedPeerIDs: [Data] = collectionsQueue.sync {
            peers.values.filter { $0.isConnected }.compactMap { $0.peerID.routingData }
        }

        let announcement = AnnouncementPacket(
            nickname: myNickname,
            noisePublicKey: noisePub,
            signingPublicKey: signingPub,
            directNeighbors: connectedPeerIDs
        )

        guard let payload = announcement.encode() else {
            SecureLogger.error("Failed to encode announce packet", category: .session)
            return
        }

        let packet = BitchatPacket(
            type: MessageType.announce.rawValue,
            senderID: myPeerIDData,
            recipientID: nil,
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            payload: payload,
            signature: nil,
            ttl: messageTTL
        )

        guard let signedPacket = noiseService.signPacket(packet) else {
            SecureLogger.error("Failed to sign announce packet", category: .security)
            return
        }

        if DispatchQueue.getSpecific(key: messageQueueKey) != nil {
            broadcastPacket(signedPacket)
        } else {
            messageQueue.async { [weak self] in
                self?.broadcastPacket(signedPacket)
            }
        }

        gossipSyncManager?.onPublicPacketSeen(signedPacket)
    }

    func sendVerifyChallenge(to peerID: PeerID, noiseKeyHex: String, nonceA: Data) {
        let payload = VerificationService.shared.buildVerifyChallenge(noiseKeyHex: noiseKeyHex, nonceA: nonceA)
        sendNoisePayload(payload, to: peerID)
    }

    func sendVerifyResponse(to peerID: PeerID, noiseKeyHex: String, nonceA: Data) {
        guard let payload = VerificationService.shared.buildVerifyResponse(noiseKeyHex: noiseKeyHex, nonceA: nonceA) else { return }
        sendNoisePayload(payload, to: peerID)
    }
}

extension BLEService: GossipSyncManager.Delegate {
    func sendPacket(_ packet: BitchatPacket) {
        broadcastPacket(packet)
    }

    func sendPacket(to peerID: PeerID, packet: BitchatPacket) {
        sendPacketDirected(packet, to: peerID)
    }

    func signPacketForBroadcast(_ packet: BitchatPacket) -> BitchatPacket {
        return noiseService.signPacket(packet) ?? packet
    }

    func getConnectedPeers() -> [PeerID] {
        return collectionsQueue.sync {
            peers.values.compactMap { $0.isConnected ? $0.peerID : nil }
        }
    }
}

extension BLEService: CBCentralManagerDelegate {
    #if os(iOS)
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String : Any]) {
        let restoredPeripherals = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral]) ?? []
        let restoredServices = (dict[CBCentralManagerRestoredStateScanServicesKey] as? [CBUUID]) ?? []
        let restoredOptions = (dict[CBCentralManagerRestoredStateScanOptionsKey] as? [String: Any]) ?? [:]
        let allowDuplicates = restoredOptions[CBCentralManagerScanOptionAllowDuplicatesKey] as? Bool

        SecureLogger.info(
            "Central restore: peripherals=\(restoredPeripherals.count) services=\(restoredServices.count) allowDuplicates=\(String(describing: allowDuplicates))",
            category: .session
        )

        for peripheral in restoredPeripherals {
            let identifier = peripheral.identifier.uuidString
            peripheral.delegate = self
            let existing = peripherals[identifier]
            let assembler = existing?.assembler ?? NotificationStreamAssembler()
            let characteristic = existing?.characteristic
            let peerID = existing?.peerID
            let wasConnecting = existing?.isConnecting ?? false
            let wasConnected = existing?.isConnected ?? false

            let restoredState = PeripheralState(
                peripheral: peripheral,
                characteristic: characteristic,
                peerID: peerID,
                isConnecting: wasConnecting || peripheral.state == .connecting,
                isConnected: wasConnected || peripheral.state == .connected,
                lastConnectionAttempt: existing?.lastConnectionAttempt,
                assembler: assembler
            )
            peripherals[identifier] = restoredState
        }

        captureBluetoothStatus(context: "central-restore")

        if central.state == .poweredOn {
            startScanning()
        }
    }
    #endif

    func centralManagerDidUpdateState(_ central: CBCentralManager) {

        Task { @MainActor in
            self.delegate?.didUpdateBluetoothState(central.state)
        }

        switch central.state {
        case .poweredOn:

            startScanning()

        case .poweredOff:

            SecureLogger.info("Bluetooth powered off - cleaning up central state", category: .session)
            central.stopScan()

            let peerIDs: [PeerID] = peripherals.compactMap { $0.value.peerID }
            for state in peripherals.values {
                central.cancelPeripheralConnection(state.peripheral)
            }
            peripherals.removeAll()
            peerToPeripheralUUID.removeAll()

            for peerID in peerIDs {
                notifyUI { [weak self] in
                    self?.notifyPeerDisconnectedDebounced(peerID)
                }
            }

        case .unauthorized:

            SecureLogger.warning("Bluetooth unauthorized - user denied permission", category: .session)
            central.stopScan()
            peripherals.removeAll()
            peerToPeripheralUUID.removeAll()

        case .unsupported:

            SecureLogger.error("Bluetooth LE not supported on this device", category: .session)

        case .resetting:

            SecureLogger.info("Bluetooth stack resetting...", category: .session)

        case .unknown:

            SecureLogger.debug("Bluetooth state unknown (initializing)", category: .session)

        @unknown default:
            SecureLogger.warning("Unknown Bluetooth state: \(central.state.rawValue)", category: .session)
        }
    }

    private func startScanning() {
        guard let central = centralManager,
              central.state == .poweredOn,
              !central.isScanning else { return }

        #if os(iOS)
        let allowDuplicates = isAppActive
        #else
        let allowDuplicates = true
        #endif

        central.scanForPeripherals(
                withServices: [BLEService.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates]
        )

    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let peripheralID = peripheral.identifier.uuidString
        let advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? (peripheralID.prefix(6) + "…")
        let isConnectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue ?? true
        let rssiValue = RSSI.intValue

        guard isConnectable else { return }

        if rssiValue <= dynamicRSSIThreshold {
            connectionCandidates.append(ConnectionCandidate(peripheral: peripheral, rssi: rssiValue, name: String(advertisedName), isConnectable: isConnectable, discoveredAt: Date()))

            connectionCandidates.sort { (a, b) in
                if a.rssi != b.rssi { return a.rssi > b.rssi }
                return a.discoveredAt < b.discoveredAt
            }
            if connectionCandidates.count > TransportConfig.bleConnectionCandidatesMax {
                connectionCandidates.removeLast(connectionCandidates.count - TransportConfig.bleConnectionCandidatesMax)
            }
            return
        }

        let currentCentralLinks = peripherals.values.filter { $0.isConnected || $0.isConnecting }.count
        if currentCentralLinks >= maxCentralLinks {

            connectionCandidates.append(ConnectionCandidate(peripheral: peripheral, rssi: rssiValue, name: String(advertisedName), isConnectable: isConnectable, discoveredAt: Date()))

            connectionCandidates.sort { (a, b) in
                if a.rssi != b.rssi { return a.rssi > b.rssi }
                return a.discoveredAt < b.discoveredAt
            }
            if connectionCandidates.count > TransportConfig.bleConnectionCandidatesMax {
                connectionCandidates.removeLast(connectionCandidates.count - TransportConfig.bleConnectionCandidatesMax)
            }
            return
        }

        let sinceLast = Date().timeIntervalSince(lastGlobalConnectAttempt)
        if sinceLast < connectRateLimitInterval {
            connectionCandidates.append(ConnectionCandidate(peripheral: peripheral, rssi: rssiValue, name: String(advertisedName), isConnectable: isConnectable, discoveredAt: Date()))
            connectionCandidates.sort { (a, b) in
                if a.rssi != b.rssi { return a.rssi > b.rssi }
                return a.discoveredAt < b.discoveredAt
            }

            let delay = connectRateLimitInterval - sinceLast + 0.05
            bleQueue.asyncAfter(deadline: .now() + delay) { [weak self] in
                self?.tryConnectFromQueue()
            }
            return
        }

        if let state = peripherals[peripheralID] {
            if state.isConnected || state.isConnecting {
                return
            }

        if let lastAttempt = state.lastConnectionAttempt {
            let timeSinceLastAttempt = Date().timeIntervalSince(lastAttempt)
            if timeSinceLastAttempt < 2.0 {
                return
            }
        }
        }

        if let lastTimeout = recentConnectTimeouts[peripheralID], Date().timeIntervalSince(lastTimeout) < 15 {
            return
        }

        if peripheral.state == .connecting || peripheral.state == .connected {

            central.cancelPeripheralConnection(peripheral)

            return
        }

        peripherals[peripheralID] = PeripheralState(
            peripheral: peripheral,
            characteristic: nil,
            peerID: nil,
            isConnecting: true,
            isConnected: false,
            lastConnectionAttempt: Date(),
            assembler: NotificationStreamAssembler()
        )
        peripheral.delegate = self

        SecureLogger.debug("Connect: \(advertisedName) [RSSI:\(rssiValue)]", category: .session)

        let options: [String: Any] = [
            CBConnectPeripheralOptionNotifyOnConnectionKey: true,
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
            CBConnectPeripheralOptionNotifyOnNotificationKey: true
        ]
        central.connect(peripheral, options: options)
        lastGlobalConnectAttempt = Date()

        bleQueue.asyncAfter(deadline: .now() + TransportConfig.bleConnectTimeoutSeconds) { [weak self] in
            guard let self = self,
                  let state = self.peripherals[peripheralID],
                  state.isConnecting && !state.isConnected else { return }

            guard peripheral.state != .connected else {
                SecureLogger.debug("⏱Timeout fired but peripheral already connected: \(advertisedName)", category: .session)
                return
            }

            SecureLogger.debug("⏱Timeout: \(advertisedName)", category: .session)
            central.cancelPeripheralConnection(peripheral)
            self.peripherals[peripheralID] = nil
            self.recentConnectTimeouts[peripheralID] = Date()
            self.failureCounts[peripheralID, default: 0] += 1

            self.tryConnectFromQueue()
        }
    }

func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let peripheralID = peripheral.identifier.uuidString

        if var state = peripherals[peripheralID] {
            state.isConnecting = false
            state.isConnected = true
            peripherals[peripheralID] = state
        } else {

            peripherals[peripheralID] = PeripheralState(
                peripheral: peripheral,
                characteristic: nil,
                peerID: nil,
                isConnecting: false,
                isConnected: true,
                lastConnectionAttempt: nil,
                assembler: NotificationStreamAssembler()
            )
        }

        failureCounts[peripheralID] = 0
        recentConnectTimeouts.removeValue(forKey: peripheralID)

        SecureLogger.debug("Connected: \(peripheral.name ?? "Unknown") [\(peripheralID)]", category: .session)

        peripheral.discoverServices([BLEService.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let peripheralID = peripheral.identifier.uuidString

        let peerID = peripherals[peripheralID]?.peerID

        SecureLogger.debug("Disconnect: \(peerID?.id ?? peripheralID)\(error != nil ? " (\(error!.localizedDescription))" : "")", category: .session)

        if error != nil {
            recentConnectTimeouts[peripheralID] = Date()
        }

        peripherals.removeValue(forKey: peripheralID)

        if let peerID {
            peerToPeripheralUUID.removeValue(forKey: peerID)

            collectionsQueue.sync(flags: .barrier) {
                if var info = peers[peerID] {
                    info.isConnected = false
                    peers[peerID] = info
                }
            }
            refreshLocalTopology()
        }

        if centralManager?.state == .poweredOn {

            centralManager?.stopScan()
            bleQueue.asyncAfter(deadline: .now() + TransportConfig.bleRestartScanDelaySeconds) { [weak self] in
                self?.startScanning()
            }
        }

        bleQueue.async { [weak self] in self?.tryConnectFromQueue() }

        notifyUI { [weak self] in
            guard let self = self else { return }

            let currentPeerIDs = self.collectionsQueue.sync { self.currentPeerIDs }

            if let peerID {
                self.notifyPeerDisconnectedDebounced(peerID)
            }
            self.requestPeerDataPublish()
            self.delegate?.didUpdatePeerList(currentPeerIDs)
        }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let peripheralID = peripheral.identifier.uuidString

        peripherals.removeValue(forKey: peripheralID)

        SecureLogger.error("Failed to connect to peripheral: \(peripheral.name ?? "Unknown") [\(peripheralID)] - Error: \(error?.localizedDescription ?? "Unknown")", category: .session)
        failureCounts[peripheralID, default: 0] += 1

        bleQueue.async { [weak self] in self?.tryConnectFromQueue() }
    }
}

extension BLEService {
    private func tryConnectFromQueue() {
        guard let central = centralManager, central.state == .poweredOn else { return }

        let current = peripherals.values.filter { $0.isConnected || $0.isConnecting }.count
        guard current < maxCentralLinks else { return }
        let delta = Date().timeIntervalSince(lastGlobalConnectAttempt)
        guard delta >= connectRateLimitInterval else {
            let delay = connectRateLimitInterval - delta + 0.05
            bleQueue.asyncAfter(deadline: .now() + delay) { [weak self] in self?.tryConnectFromQueue() }
            return
        }

        guard !connectionCandidates.isEmpty else { return }

        func score(_ c: ConnectionCandidate) -> Int {
            let uuid = c.peripheral.identifier.uuidString

            let fails = failureCounts[uuid] ?? 0
            let penalty = min(20, (1 << min(4, fails)))
            let timeoutRecent = recentConnectTimeouts[uuid]
            let timeoutBias = (timeoutRecent != nil && Date().timeIntervalSince(timeoutRecent!) < 60) ? 10 : 0
            let base = (c.isConnectable ? 1000 : 0) + (c.rssi + 100) * 2
            let rec = -Int(Date().timeIntervalSince(c.discoveredAt) * 10)
            return base + rec - penalty - timeoutBias
        }
        connectionCandidates.sort { score($0) > score($1) }
        let candidate = connectionCandidates.removeFirst()
        guard candidate.isConnectable else { return }
        let peripheral = candidate.peripheral
        let peripheralID = peripheral.identifier.uuidString

        if let lastTO = recentConnectTimeouts[peripheralID] {
            let elapsed = Date().timeIntervalSince(lastTO)
            if elapsed < TransportConfig.bleWeakLinkCooldownSeconds && candidate.rssi <= TransportConfig.bleWeakLinkRSSICutoff {

                connectionCandidates.append(candidate)
                let remaining = TransportConfig.bleWeakLinkCooldownSeconds - elapsed
                let delay = min(max(2.0, remaining), 15.0)
                bleQueue.asyncAfter(deadline: .now() + delay) { [weak self] in self?.tryConnectFromQueue() }
                return
            }
        }
        if peripherals[peripheralID]?.isConnected == true || peripherals[peripheralID]?.isConnecting == true {

            bleQueue.async { [weak self] in self?.tryConnectFromQueue() }
            return
        }

        peripherals[peripheralID] = PeripheralState(
            peripheral: peripheral,
            characteristic: nil,
            peerID: nil,
            isConnecting: true,
            isConnected: false,
            lastConnectionAttempt: Date(),
            assembler: NotificationStreamAssembler()
        )
        peripheral.delegate = self
        let options: [String: Any] = [
            CBConnectPeripheralOptionNotifyOnConnectionKey: true,
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
            CBConnectPeripheralOptionNotifyOnNotificationKey: true
        ]
        central.connect(peripheral, options: options)
        lastGlobalConnectAttempt = Date()
        SecureLogger.debug("⏩ Queue connect: \(candidate.name) [RSSI:\(candidate.rssi)]", category: .session)
    }
}

#if DEBUG

extension BLEService {
    func _test_handlePacket(_ packet: BitchatPacket, fromPeerID: PeerID, preseedPeer: Bool = true) {
        if preseedPeer {

            let normalizedID = PeerID(hexData: packet.senderID)
            collectionsQueue.sync(flags: .barrier) {
                if peers[normalizedID] == nil {
                    peers[normalizedID] = PeerInfo(
                        peerID: normalizedID,
                        nickname: "TestPeer_\(fromPeerID.id.prefix(4))",
                        isConnected: true,
                        noisePublicKey: packet.senderID,
                        signingPublicKey: nil,
                        isVerifiedNickname: true,
                        lastSeen: Date()
                    )
                } else {
                    var p = peers[normalizedID]!
                    p.isConnected = true
                    p.isVerifiedNickname = true
                    p.lastSeen = Date()
                    peers[normalizedID] = p
                }
            }
        }
        handleReceivedPacket(packet, from: fromPeerID)
    }
}
#endif

extension BLEService: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            SecureLogger.error("Error discovering services for \(peripheral.name ?? "Unknown"): \(error.localizedDescription)", category: .session)

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                guard peripheral.state == .connected else { return }
                peripheral.discoverServices([BLEService.serviceUUID])
            }
            return
        }

        guard let services = peripheral.services else {
            SecureLogger.warning("No services discovered for \(peripheral.name ?? "Unknown")", category: .session)
            return
        }

        guard let service = services.first(where: { $0.uuid == BLEService.serviceUUID }) else {

            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }

        peripheral.discoverCharacteristics([BLEService.characteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error {
            SecureLogger.error("Error discovering characteristics for \(peripheral.name ?? "Unknown"): \(error.localizedDescription)", category: .session)
            return
        }

        guard let characteristic = service.characteristics?.first(where: { $0.uuid == BLEService.characteristicUUID }) else {
            SecureLogger.warning("No matching characteristic found for \(peripheral.name ?? "Unknown")", category: .session)
            return
        }

        var properties: [String] = []
        if characteristic.properties.contains(.read) { properties.append("read") }
        if characteristic.properties.contains(.write) { properties.append("write") }
        if characteristic.properties.contains(.writeWithoutResponse) { properties.append("writeWithoutResponse") }
        if characteristic.properties.contains(.notify) { properties.append("notify") }
        if characteristic.properties.contains(.indicate) { properties.append("indicate") }

        if !characteristic.properties.contains(.write) {
            SecureLogger.warning("Characteristic doesn't support reliable writes (withResponse)!", category: .session)
        }

        let peripheralID = peripheral.identifier.uuidString
        if var state = peripherals[peripheralID] {
            state.characteristic = characteristic
            peripherals[peripheralID] = state
        }

        if characteristic.properties.contains(.notify) {
            peripheral.setNotifyValue(true, for: characteristic)
            SecureLogger.debug("Subscribed to notifications from \(peripheral.name ?? "Unknown")", category: .session)

            messageQueue.asyncAfter(deadline: .now() + TransportConfig.blePostSubscribeAnnounceDelaySeconds) { [weak self] in
                self?.sendAnnounce(forceSend: true)

                self?.flushDirectedSpool()
            }
        } else {
            SecureLogger.warning("Characteristic does not support notifications", category: .session)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            SecureLogger.error("Error receiving notification: \(error.localizedDescription)", category: .session)
            return
        }

        guard let data = characteristic.value, !data.isEmpty else {
            SecureLogger.warning("No data in notification", category: .session)
            return
        }

        bufferNotificationChunk(data, from: peripheral)
    }

    private func bufferNotificationChunk(_ chunk: Data, from peripheral: CBPeripheral) {
        let peripheralUUID = peripheral.identifier.uuidString

        var state = peripherals[peripheralUUID] ?? PeripheralState(
            peripheral: peripheral,
            characteristic: nil,
            peerID: nil,
            isConnecting: false,
            isConnected: peripheral.state == .connected,
            lastConnectionAttempt: nil,
            assembler: NotificationStreamAssembler()
        )

        var assembler = state.assembler
        let result = assembler.append(chunk)
        state.assembler = assembler
        peripherals[peripheralUUID] = state

        for byte in result.droppedPrefixes {
            SecureLogger.warning("Dropping byte from BLE stream (unexpected prefix \(String(format: "%02x", byte)))", category: .session)
        }

        if result.reset {
            SecureLogger.error("Invalid BLE frame length; reset notification stream", category: .session)
        }

        var boundPeerID: PeerID? = state.peerID

        for frame in result.frames {
            guard let packet = BinaryProtocol.decode(frame) else {
                let prefix = frame.prefix(16).map { String(format: "%02x", $0) }.joined(separator: " ")
                SecureLogger.error("Failed to decode assembled notification frame (len=\(frame.count), prefix=\(prefix))", category: .session)
                continue
            }

            let claimedSenderID = PeerID(hexData: packet.senderID)

            let trustedSenderID: PeerID?
            if let knownPeerID = boundPeerID {
                if knownPeerID != claimedSenderID {
                    SecureLogger.warning("SECURITY: Sender ID spoofing attempt detected! Peripheral \(peripheralUUID.prefix(8))… claimed to be \(claimedSenderID.id.prefix(8))… but is bound to \(knownPeerID.id.prefix(8))…", category: .security)
                    continue
                }
                trustedSenderID = knownPeerID
            } else {
                trustedSenderID = nil
            }

            if !validatePacket(packet, from: trustedSenderID ?? claimedSenderID, connectionSource: .peripheral(peripheralUUID)) {
                continue
            }

            if boundPeerID == nil,
               packet.type == MessageType.announce.rawValue,
               packet.ttl == messageTTL {
                boundPeerID = claimedSenderID
                state.peerID = claimedSenderID
                peripherals[peripheralUUID] = state
            }
            processNotificationPacket(packet, from: peripheral, peripheralUUID: peripheralUUID)
        }
    }

    private func processNotificationPacket(_ packet: BitchatPacket, from peripheral: CBPeripheral, peripheralUUID: String) {
        let senderID = PeerID(hexData: packet.senderID)

        if packet.type != MessageType.announce.rawValue {
            SecureLogger.debug("Decoded notification packet type: \(packet.type) from sender: \(senderID)", category: .session)
        }

        if packet.type == MessageType.announce.rawValue {
            if packet.ttl == messageTTL {
                if var state = peripherals[peripheralUUID] {
                    state.peerID = senderID
                    peripherals[peripheralUUID] = state
                }
                peerToPeripheralUUID[senderID] = peripheralUUID
                refreshLocalTopology()
            }

            let msgID = makeMessageID(for: packet)
            collectionsQueue.async(flags: .barrier) { [weak self] in
                self?.ingressByMessageID[msgID] = (.peripheral(peripheralUUID), Date())
            }
            handleReceivedPacket(packet, from: senderID)
        } else {
            let msgID = makeMessageID(for: packet)
            collectionsQueue.async(flags: .barrier) { [weak self] in
                self?.ingressByMessageID[msgID] = (.peripheral(peripheralUUID), Date())
            }
            handleReceivedPacket(packet, from: senderID)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            SecureLogger.error("Write failed to \(peripheral.name ?? peripheral.identifier.uuidString): \(error.localizedDescription)", category: .session)

        } else {
            SecureLogger.debug("Write confirmed to \(peripheral.name ?? peripheral.identifier.uuidString)", category: .session)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {

        SecureLogger.debug("Peripheral \(peripheral.name ?? peripheral.identifier.uuidString.prefix(8).description) ready for more writes", category: .session)
        drainPendingWrites(for: peripheral)
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        SecureLogger.warning("Services modified for \(peripheral.name ?? peripheral.identifier.uuidString)", category: .session)

        let hasOurService = peripheral.services?.contains { $0.uuid == BLEService.serviceUUID } ?? false

        if !hasOurService {

            SecureLogger.warning("BitChat service removed - disconnecting from \(peripheral.name ?? peripheral.identifier.uuidString)", category: .session)
            centralManager?.cancelPeripheralConnection(peripheral)
        } else {

            peripheral.discoverServices([BLEService.serviceUUID])
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            SecureLogger.error("Error updating notification state: \(error.localizedDescription)", category: .session)
        } else {
            SecureLogger.debug("Notification state updated for \(peripheral.name ?? peripheral.identifier.uuidString): \(characteristic.isNotifying ? "ON" : "OFF")", category: .session)

            if characteristic.isNotifying {

                self.sendAnnounce(forceSend: true)
            }
        }
    }

}

extension BLEService: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        SecureLogger.debug("Peripheral manager state: \(peripheral.state.rawValue)", category: .session)

        switch peripheral.state {
        case .poweredOn:

            peripheral.removeAllServices()

            characteristic = CBMutableCharacteristic(
                type: BLEService.characteristicUUID,
                properties: [.notify, .write, .writeWithoutResponse, .read],
                value: nil,
                permissions: [.readable, .writeable]
            )

            let service = CBMutableService(type: BLEService.serviceUUID, primary: true)
            service.characteristics = [characteristic!]

            SecureLogger.debug("Adding BLE service...", category: .session)
            peripheral.add(service)

        case .poweredOff:

            SecureLogger.info("Bluetooth powered off - cleaning up peripheral state", category: .session)
            peripheral.stopAdvertising()

            let centralPeerIDs = centralToPeerID.values.map { $0 }
            subscribedCentrals.removeAll()
            centralToPeerID.removeAll()
            centralSubscriptionRateLimits.removeAll()
            characteristic = nil

            for peerID in centralPeerIDs {
                notifyUI { [weak self] in
                    self?.notifyPeerDisconnectedDebounced(peerID)
                }
            }

        case .unauthorized:

            SecureLogger.warning("Bluetooth unauthorized for peripheral role", category: .session)
            peripheral.stopAdvertising()
            subscribedCentrals.removeAll()
            centralToPeerID.removeAll()
            centralSubscriptionRateLimits.removeAll()
            characteristic = nil

        case .unsupported:

            SecureLogger.error("Bluetooth LE peripheral role not supported", category: .session)

        case .resetting:

            SecureLogger.info("Bluetooth peripheral stack resetting...", category: .session)

        case .unknown:
            SecureLogger.debug("Peripheral Bluetooth state unknown (initializing)", category: .session)

        @unknown default:
            SecureLogger.warning("Unknown peripheral Bluetooth state: \(peripheral.state.rawValue)", category: .session)
        }
    }

    #if os(iOS)
    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String : Any]) {
        let restoredServices = (dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService]) ?? []
        let restoredAdvertisement = (dict[CBPeripheralManagerRestoredStateAdvertisementDataKey] as? [String: Any]) ?? [:]

        SecureLogger.info(
            "Peripheral restore: services=\(restoredServices.count) advertisingDataKeys=\(Array(restoredAdvertisement.keys))",
            category: .session
        )

        if characteristic == nil {
            if let service = restoredServices.first(where: { $0.uuid == BLEService.serviceUUID }),
               let restoredCharacteristic = service.characteristics?.first(where: { $0.uuid == BLEService.characteristicUUID }) as? CBMutableCharacteristic {
                characteristic = restoredCharacteristic
            }
        }

        captureBluetoothStatus(context: "peripheral-restore")

        if peripheral.state == .poweredOn && !peripheral.isAdvertising {
            peripheral.startAdvertising(buildAdvertisementData())
        }
    }
    #endif

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            SecureLogger.error("Failed to add service: \(error.localizedDescription)", category: .session)
            return
        }

        SecureLogger.debug("Service added successfully, starting advertising", category: .session)

        let adData = buildAdvertisementData()
        peripheral.startAdvertising(adData)

        SecureLogger.debug("Started advertising (LocalName: \((adData[CBAdvertisementDataLocalNameKey] as? String) != nil ? "on" : "off"), ID: \(myPeerID.id.prefix(8))…)", category: .session)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        let centralUUID = central.identifier.uuidString
        SecureLogger.debug("Central subscribed: \(centralUUID)", category: .session)
        subscribedCentrals.append(central)

        let now = Date()
        var state = centralSubscriptionRateLimits[centralUUID]

        cleanupStaleSubscriptionRateLimits()

        if let existingState = state {
            let timeSinceLastAnnounce = now.timeIntervalSince(existingState.lastAnnounceTime)

            if timeSinceLastAnnounce < existingState.currentBackoffSeconds {
                SecureLogger.warning("BCH-01-004: Rate-limited announce for central \(centralUUID.prefix(8))... (backoff: \(Int(existingState.currentBackoffSeconds))s, attempts: \(existingState.attemptCount))", category: .security)

                let newAttemptCount = existingState.attemptCount + 1
                let newBackoff = min(
                    existingState.currentBackoffSeconds * TransportConfig.bleSubscriptionRateLimitBackoffFactor,
                    TransportConfig.bleSubscriptionRateLimitMaxBackoffSeconds
                )
                centralSubscriptionRateLimits[centralUUID] = SubscriptionRateLimitState(
                    lastAnnounceTime: now,
                    attemptCount: newAttemptCount,
                    currentBackoffSeconds: newBackoff
                )

                if newAttemptCount >= TransportConfig.bleSubscriptionRateLimitMaxAttempts {
                    SecureLogger.warning("BCH-01-004: Possible enumeration attack from central \(centralUUID.prefix(8))... - suppressing announce", category: .security)
                    return
                }

                messageQueue.asyncAfter(deadline: .now() + TransportConfig.blePostAnnounceDelaySeconds) { [weak self] in
                    self?.flushDirectedSpool()
                }
                return
            }

            state = SubscriptionRateLimitState(
                lastAnnounceTime: now,
                attemptCount: 1,
                currentBackoffSeconds: TransportConfig.bleSubscriptionRateLimitMinSeconds
            )
        } else {

            state = SubscriptionRateLimitState(
                lastAnnounceTime: now,
                attemptCount: 1,
                currentBackoffSeconds: TransportConfig.bleSubscriptionRateLimitMinSeconds
            )
        }
        centralSubscriptionRateLimits[centralUUID] = state

        messageQueue.asyncAfter(deadline: .now() + TransportConfig.blePostAnnounceDelaySeconds) { [weak self] in
            self?.sendAnnounce(forceSend: true)

            self?.flushDirectedSpool()
        }
    }

    private func cleanupStaleSubscriptionRateLimits() {
        let now = Date()
        let windowSeconds = TransportConfig.bleSubscriptionRateLimitWindowSeconds
        centralSubscriptionRateLimits = centralSubscriptionRateLimits.filter { _, state in
            now.timeIntervalSince(state.lastAnnounceTime) < windowSeconds
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        SecureLogger.debug("Central unsubscribed: \(central.identifier.uuidString)", category: .session)
        subscribedCentrals.removeAll { $0.identifier == central.identifier }

        if peripheral.isAdvertising == false {
            SecureLogger.debug("Restarting advertising after central unsubscribed", category: .session)
            peripheral.startAdvertising(buildAdvertisementData())
        }

        let centralUUID = central.identifier.uuidString
        if let peerID = centralToPeerID[centralUUID] {

            collectionsQueue.sync(flags: .barrier) {
                if var info = peers[peerID] {
                    info.isConnected = false
                    peers[peerID] = info
                }
            }

            centralToPeerID.removeValue(forKey: centralUUID)
            refreshLocalTopology()

            notifyUI { [weak self] in
                guard let self = self else { return }

                let currentPeerIDs = self.collectionsQueue.sync { self.currentPeerIDs }

                self.notifyPeerDisconnectedDebounced(peerID)

                self.requestPeerDataPublish()
                self.delegate?.didUpdatePeerList(currentPeerIDs)
            }
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        SecureLogger.debug("Peripheral manager ready to send more notifications", category: .session)

        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self,
                  let characteristic = self.characteristic,
                  !self.pendingNotifications.isEmpty else { return }

            let pending = self.pendingNotifications
            self.pendingNotifications.removeAll()

            var sentCount = 0
            for (index, (data, centrals)) in pending.enumerated() {
                if let centrals = centrals {

                    let success = self.peripheralManager?.updateValue(data, for: characteristic, onSubscribedCentrals: centrals) ?? false
                    if !success {

                        let remaining = pending.dropFirst(index)
                        self.pendingNotifications.append(contentsOf: remaining)
                        SecureLogger.debug("Notification queue still full after \(sentCount) sent, re-queuing \(remaining.count) items", category: .session)
                        break
                    } else {
                        sentCount += 1
                    }
                } else {

                    let success = self.peripheralManager?.updateValue(data, for: characteristic, onSubscribedCentrals: nil) ?? false
                    if !success {

                        let remaining = pending.dropFirst(index)
                        self.pendingNotifications.append(contentsOf: remaining)
                        SecureLogger.debug("Notification queue still full after \(sentCount) sent, re-queuing \(remaining.count) items", category: .session)
                        break
                    } else {
                        sentCount += 1
                    }
                }
            }

            if sentCount > 0 {
                SecureLogger.debug("Sent \(sentCount) pending notifications from retry queue", category: .session)
            }

            if !self.pendingNotifications.isEmpty {
                SecureLogger.debug("Still have \(self.pendingNotifications.count) pending notifications", category: .session)
            }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {

        if requests.count > 1 {
            SecureLogger.debug("Received \(requests.count) write requests from central", category: .session)
        }

        for request in requests {
            peripheral.respond(to: request, withResult: .success)
        }

        let grouped = Dictionary(grouping: requests, by: { $0.central.identifier.uuidString })
        for (centralUUID, group) in grouped {

            let sorted = group.sorted { $0.offset < $1.offset }
            let hasMultiple = sorted.count > 1 || (sorted.first?.offset ?? 0) > 0

            var combined = pendingWriteBuffers[centralUUID] ?? Data()
            var appendedBytes = 0
            var offsets: [Int] = []
            for r in sorted {
                guard let chunk = r.value, !chunk.isEmpty else { continue }
                offsets.append(r.offset)
                let end = r.offset + chunk.count
                if combined.count < end {
                    combined.append(Data(repeating: 0, count: end - combined.count))
                }

                combined.replaceSubrange(r.offset..<end, with: chunk)
                appendedBytes += chunk.count
            }
            pendingWriteBuffers[centralUUID] = combined

            if combined.count >= 2 {
                let peekType = combined[1]
                if peekType != MessageType.announce.rawValue {
                    SecureLogger.debug("Accumulated write from central \(centralUUID): size=\(combined.count) (+\(appendedBytes)) bytes (type=\(peekType)), offsets=\(offsets)", category: .session)
                }
            }

            if let packet = BinaryProtocol.decode(combined) {

                pendingWriteBuffers.removeValue(forKey: centralUUID)

                let claimedSenderID = PeerID(hexData: packet.senderID)

                let trustedSenderID: PeerID?
                if let knownPeerID = centralToPeerID[centralUUID] {
                    if knownPeerID != claimedSenderID {
                        SecureLogger.warning("SECURITY: Sender ID spoofing attempt detected! Central \(centralUUID.prefix(8))… claimed to be \(claimedSenderID.id.prefix(8))… but is bound to \(knownPeerID.id.prefix(8))…", category: .security)
                        continue
                    }
                    trustedSenderID = knownPeerID
                } else {
                    trustedSenderID = nil
                }

                if !validatePacket(packet, from: trustedSenderID ?? claimedSenderID, connectionSource: .central(centralUUID)) {
                    continue
                }

                if packet.type != MessageType.announce.rawValue {
                    SecureLogger.debug("Decoded (combined) packet type: \(packet.type) from sender: \(claimedSenderID)", category: .session)
                }
                if !subscribedCentrals.contains(sorted[0].central) {
                    subscribedCentrals.append(sorted[0].central)
                }
                if packet.type == MessageType.announce.rawValue {
                    if packet.ttl == messageTTL {
                        centralToPeerID[centralUUID] = claimedSenderID
                        refreshLocalTopology()
                    }

                    let msgID = makeMessageID(for: packet)
                    collectionsQueue.async(flags: .barrier) { [weak self] in
                        self?.ingressByMessageID[msgID] = (.central(centralUUID), Date())
                    }
                    handleReceivedPacket(packet, from: claimedSenderID)
                } else {

                    let msgID = makeMessageID(for: packet)
                    collectionsQueue.async(flags: .barrier) { [weak self] in
                        self?.ingressByMessageID[msgID] = (.central(centralUUID), Date())
                    }
                    handleReceivedPacket(packet, from: claimedSenderID)
                }
            } else {

                if combined.count > TransportConfig.blePendingWriteBufferCapBytes {
                    pendingWriteBuffers.removeValue(forKey: centralUUID)
                    SecureLogger.warning("Dropping oversized pending write buffer (\(combined.count) bytes) for central \(centralUUID)", category: .session)
                }

                if !hasMultiple, let only = sorted.first, let raw = only.value {
                    let prefix = raw.prefix(16).map { String(format: "%02x", $0) }.joined(separator: " ")
                    SecureLogger.error("Failed to decode packet from central (len=\(raw.count), prefix=\(prefix))", category: .session)
                }
            }
        }
    }
}

extension BLEService {
    private func buildAdvertisementData() -> [String: Any] {
        let data: [String: Any] = [
            CBAdvertisementDataServiceUUIDsKey: [BLEService.serviceUUID]
        ]

        return data
    }

}

extension BLEService {

    private func notifyUI(_ block: @escaping () -> Void) {

        Task { @MainActor in
            block()
        }
    }

    private func logBluetoothStatus(_ context: String) {
        bleQueue.async { [weak self] in
            guard let self = self else { return }
            self.captureBluetoothStatus(context: context)
        }
    }

    private func scheduleBluetoothStatusSample(after delay: TimeInterval, context: String) {
        bleQueue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self else { return }
            self.captureBluetoothStatus(context: context)
        }
    }

    private func captureBluetoothStatus(context: String) {
        assert(DispatchQueue.getSpecific(key: bleQueueKey) != nil, "captureBluetoothStatus must run on bleQueue")

        let centralState = centralManager?.state ?? .unknown
        let isScanning = centralManager?.isScanning ?? false
        let peripheralState = peripheralManager?.state ?? .unknown
        let isAdvertising = peripheralManager?.isAdvertising ?? false

        let peerSummary = collectionsQueue.sync {
            (
                connected: peers.values.filter { $0.isConnected }.count,
                known: peers.count,
                candidates: connectionCandidates.count
            )
        }

        #if os(iOS)
        var backgroundDescriptor = ""
        var backgroundSeconds: TimeInterval = 0
        DispatchQueue.main.sync {
            backgroundSeconds = UIApplication.shared.backgroundTimeRemaining
        }
        if backgroundSeconds == .greatestFiniteMagnitude {
            backgroundDescriptor = " bgRemaining=∞"
        } else {
            backgroundDescriptor = String(format: " bgRemaining=%.1fs", backgroundSeconds)
        }
        let appPhase = isAppActive ? "foreground" : "background"
        #else
        let backgroundDescriptor = ""
        let appPhase = "foreground"
        #endif

        SecureLogger.info(
            "BLE status [\(context)]: phase=\(appPhase) central=\(centralState) scanning=\(isScanning) peripheral=\(peripheralState) advertising=\(isAdvertising) connected=\(peerSummary.connected) known=\(peerSummary.known) candidates=\(peerSummary.candidates)\(backgroundDescriptor)",
            category: .session
        )
    }

    private func routingData(for peerID: PeerID) -> Data? {
        peerID.toShort().routingData
    }

    private func refreshLocalTopology() {
        let neighbors: [Data] = collectionsQueue.sync {
            peers.values.filter { $0.isConnected }.compactMap { $0.peerID.routingData }
        }
        meshTopology.updateNeighbors(for: myPeerIDData, neighbors: neighbors)
    }

    private func computeRoute(to peerID: PeerID) -> [Data]? {
        meshTopology.computeRoute(from: myPeerIDData, to: routingData(for: peerID))
    }

    private func applyRouteIfAvailable(_ packet: BitchatPacket, to recipient: PeerID) -> BitchatPacket {
        guard let route = computeRoute(to: recipient), route.count >= 1 else {
            return packet
        }

        let routedPacket = BitchatPacket(
            type: packet.type,
            senderID: packet.senderID,
            recipientID: packet.recipientID,
            timestamp: packet.timestamp,
            payload: packet.payload,
            signature: nil,
            ttl: packet.ttl,
            version: 2,
            route: route
        )

        guard let signedPacket = noiseService.signPacket(routedPacket) else {
            SecureLogger.error("Failed to re-sign packet with route", category: .security)
            return packet
        }
        return signedPacket
    }

    private func routingPeer(from data: Data) -> PeerID? {
        PeerID(routingData: data)
    }

    private func forwardAlongRouteIfNeeded(_ packet: BitchatPacket) -> Bool {
        guard let route = packet.route, !route.isEmpty else { return false }
        let myRoutingData = routingData(for: myPeerID) ?? (myPeerIDData.isEmpty ? nil : myPeerIDData)
        guard let selfData = myRoutingData else { return false }

        guard let index = route.firstIndex(of: selfData) else {

            guard packet.ttl > 1 else { return true }
            let firstHopData = route[0]
            guard let nextPeer = routingPeer(from: firstHopData),
                  isPeerConnected(nextPeer) else {
                return false
            }
            var relayPacket = packet
            relayPacket.ttl = packet.ttl - 1
            sendPacketDirected(relayPacket, to: nextPeer)
            return true
        }

        if index == route.count - 1 {
            guard packet.ttl > 1 else { return true }
            guard let destinationPeer = PeerID(hexData: packet.recipientID),
                  isPeerConnected(destinationPeer) else {
                return false
            }
            var relayPacket = packet
            relayPacket.ttl = packet.ttl - 1
            sendPacketDirected(relayPacket, to: destinationPeer)
            return true
        }

        guard packet.ttl > 1 else { return true }
        let nextHopData = route[index + 1]
        guard let nextPeer = routingPeer(from: nextHopData),
              isPeerConnected(nextPeer) else {
            return false
        }

        var relayPacket = packet
        relayPacket.ttl = packet.ttl - 1
        sendPacketDirected(relayPacket, to: nextPeer)
        return true
    }

    private func linkState(for peerID: PeerID) -> (hasPeripheral: Bool, hasCentral: Bool) {
        let computeState = { () -> (Bool, Bool) in
            let peripheralUUID = self.peerToPeripheralUUID[peerID]
            let hasPeripheral = peripheralUUID.flatMap { self.peripherals[$0]?.isConnected } ?? false
            let hasCentral = self.centralToPeerID.values.contains(peerID)
            return (hasPeripheral, hasCentral)
        }

        if DispatchQueue.getSpecific(key: bleQueueKey) != nil {
            return computeState()
        } else {
            return bleQueue.sync { computeState() }
        }
    }

    private func configureNoiseServiceCallbacks(for service: NoiseEncryptionService) {
        service.onPeerAuthenticated = { [weak self] peerID, fingerprint in
            SecureLogger.debug("Noise session authenticated with \(peerID), fingerprint: \(fingerprint.prefix(16))...")
            self?.messageQueue.async { [weak self] in
                self?.sendPendingMessagesAfterHandshake(for: peerID)
                self?.sendPendingNoisePayloadsAfterHandshake(for: peerID)
            }
            self?.messageQueue.async { [weak self] in
                self?.sendAnnounce(forceSend: true)
            }
        }
    }

    private func refreshPeerIdentity() {
        let fingerprint = noiseService.getIdentityFingerprint()
        myPeerID = PeerID(str: fingerprint.prefix(16))
        myPeerIDData = Data(hexString: myPeerID.id) ?? Data()
        meshTopology.reset()
    }

    private func sendNoisePayload(_ typedPayload: Data, to peerID: PeerID) {
        guard noiseService.hasSession(with: peerID) else {

            collectionsQueue.sync(flags: .barrier) {
                if self.pendingNoisePayloadsAfterHandshake[peerID] == nil {
                    self.pendingNoisePayloadsAfterHandshake[peerID] = []
                }
                self.pendingNoisePayloadsAfterHandshake[peerID]?.append(typedPayload)
                SecureLogger.debug("Queued noise payload for \(peerID) pending handshake", category: .session)
            }
            initiateNoiseHandshake(with: peerID)
            return
        }
        do {
            let encrypted = try noiseService.encrypt(typedPayload, for: peerID)
            let packet = BitchatPacket(
                type: MessageType.noiseEncrypted.rawValue,
                senderID: myPeerIDData,
                recipientID: Data(hexString: peerID.id),
                timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                payload: encrypted,
                signature: nil,
                ttl: messageTTL
            )
            broadcastPacket(packet)
        } catch {
            SecureLogger.error("Failed to send verification payload: \(error)")
        }
    }

    private func snapshotPeripheralStates() -> [PeripheralState] {
        if DispatchQueue.getSpecific(key: bleQueueKey) != nil {
            return Array(peripherals.values)
        } else {
            return bleQueue.sync { Array(peripherals.values) }
        }
    }
    private func snapshotSubscribedCentrals() -> ([CBCentral], [String: PeerID]) {
        if DispatchQueue.getSpecific(key: bleQueueKey) != nil {
            return (self.subscribedCentrals, self.centralToPeerID)
        } else {
            return bleQueue.sync { (self.subscribedCentrals, self.centralToPeerID) }
        }
    }

    private func makeMessageID(for packet: BitchatPacket) -> String {
        let senderID = packet.senderID.hexEncodedString()
        let digestPrefix = packet.payload.sha256Hash().prefix(4).hexEncodedString()
        return "\(senderID)-\(packet.timestamp)-\(packet.type)-\(digestPrefix)"
    }

    private func subsetSizeForFanout(_ n: Int) -> Int {
        guard n > 0 else { return 0 }
        if n <= 2 { return n }

        var v = n - 1
        var bits = 0
        while v > 0 { v >>= 1; bits += 1 }
        return min(n, max(1, bits + 1))
    }

    private func selectDeterministicSubset(ids: [String], k: Int, seed: String) -> Set<String> {
        guard k > 0 && ids.count > k else { return Set(ids) }

        var scored: [(score: [UInt8], id: String)] = []
        for id in ids {
            let msg = (seed + "::" + id).data(using: .utf8) ?? Data()
            let digest = Array(SHA256.hash(data: msg))
            scored.append((digest, id))
        }
        scored.sort { a, b in
            for i in 0..<min(a.score.count, b.score.count) {
                if a.score[i] != b.score[i] { return a.score[i] < b.score[i] }
            }
            return a.id < b.id
        }
        return Set(scored.prefix(k).map { $0.id })
    }

    private func priority(for packet: BitchatPacket, data: Data) -> OutboundPriority {
        guard let messageType = MessageType(rawValue: packet.type) else { return .low }
        switch messageType {
        case .fragment:
            let total = fragmentTotalCount(from: packet.payload)
            return OutboundPriority.fragment(totalFragments: total)
        case .fileTransfer:
            return .fileTransfer
        default:
            return .high
        }
    }

    private func fragmentTotalCount(from payload: Data) -> Int {
        guard payload.count >= 12 else { return Int(UInt16.max) }
        let totalHigh = Int(payload[10])
        let totalLow = Int(payload[11])
        let total = (totalHigh << 8) | totalLow
        return max(total, 1)
    }

    private func writeOrEnqueue(_ data: Data, to peripheral: CBPeripheral, characteristic: CBCharacteristic, priority: OutboundPriority) {

        bleQueue.async { [weak self] in
            guard let self = self else { return }
            let uuid = peripheral.identifier.uuidString
            if peripheral.canSendWriteWithoutResponse {
                peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
            } else {
                self.collectionsQueue.async(flags: .barrier) {
                    var queue = self.pendingPeripheralWrites[uuid] ?? []
                    let capBytes = TransportConfig.blePendingWriteBufferCapBytes
                    let newSize = data.count

                    if newSize > capBytes {
                        SecureLogger.warning("Dropping oversized write chunk (\(newSize)B) for peripheral \(uuid)", category: .session)
                    } else {
                        let item = PendingWrite(priority: priority, data: data)
                        var total = queue.reduce(0) { $0 + $1.data.count } + newSize
                        let insertIndex = queue.firstIndex { item.priority < $0.priority } ?? queue.count
                        queue.insert(item, at: insertIndex)
                        if total > capBytes {
                            var removedBytes = 0
                            while total > capBytes && !queue.isEmpty {
                                let removed = queue.removeLast()
                                removedBytes += removed.data.count
                                total -= removed.data.count
                            }
                            if removedBytes > 0 {
                                SecureLogger.warning("Trimmed pending write buffer for \(uuid) by \(removedBytes)B to \(total)B", category: .session)
                            }
                        }
                        self.pendingPeripheralWrites[uuid] = queue.isEmpty ? nil : queue
                    }
                }
            }
        }
    }

    private func drainPendingWrites(for peripheral: CBPeripheral) {
        let uuid = peripheral.identifier.uuidString
        bleQueue.async { [weak self] in
            guard let self = self else { return }
            guard let state = self.peripherals[uuid], let ch = state.characteristic else { return }

            let itemsToSend: [PendingWrite] = self.collectionsQueue.sync(flags: .barrier) {
                let items = self.pendingPeripheralWrites[uuid] ?? []
                self.pendingPeripheralWrites[uuid] = nil
                return items
            }
            guard !itemsToSend.isEmpty else { return }

            var sent = 0
            for item in itemsToSend {
                if peripheral.canSendWriteWithoutResponse {
                    peripheral.writeValue(item.data, for: ch, type: .withoutResponse)
                    sent += 1
                } else {
                    break
                }
            }

            let unsent = Array(itemsToSend.dropFirst(sent))
            if !unsent.isEmpty {
                self.collectionsQueue.async(flags: .barrier) {
                    var existing = self.pendingPeripheralWrites[uuid] ?? []

                    existing.insert(contentsOf: unsent, at: 0)
                    self.pendingPeripheralWrites[uuid] = existing
                }
            }
        }
    }

    private func drainPendingNotificationsIfPossible() {
        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self,
                  let characteristic = self.characteristic,
                  !self.pendingNotifications.isEmpty else { return }

            let pending = self.pendingNotifications
            self.pendingNotifications.removeAll()

            var sentCount = 0
            for (index, (data, centrals)) in pending.enumerated() {
                let success: Bool
                if let centrals = centrals {
                    success = self.peripheralManager?.updateValue(data, for: characteristic, onSubscribedCentrals: centrals) ?? false
                } else {
                    success = self.peripheralManager?.updateValue(data, for: characteristic, onSubscribedCentrals: nil) ?? false
                }

                if !success {

                    let remaining = pending.dropFirst(index)
                    self.pendingNotifications.append(contentsOf: remaining)
                    break
                } else {
                    sentCount += 1
                }
            }

            if sentCount > 0 {
                SecureLogger.debug("Periodic drain: sent \(sentCount) pending notifications", category: .session)
            }
        }
    }

    private func drainAllPendingWrites() {
        let uuids = collectionsQueue.sync { Array(pendingPeripheralWrites.keys) }
        for uuid in uuids {
            guard let state = peripherals[uuid], state.isConnected else { continue }
            drainPendingWrites(for: state.peripheral)
        }
    }

    #if os(iOS)
    @objc private func appDidBecomeActive() {
        isAppActive = true

        if centralManager?.state == .poweredOn {
            centralManager?.stopScan()
            startScanning()
        }
        logBluetoothStatus("became-active")
        scheduleBluetoothStatusSample(after: 5.0, context: "active-5s")

    }

    @objc private func appDidEnterBackground() {
        isAppActive = false

        if centralManager?.state == .poweredOn {
            centralManager?.stopScan()
            startScanning()
        }
        logBluetoothStatus("entered-background")
        scheduleBluetoothStatusSample(after: 15.0, context: "background-15s")

    }
    #endif

    private func sendPrivateMessage(_ content: String, to recipientID: PeerID, messageID: String) {
        SecureLogger.debug("Sending PM to \(recipientID): \(content.prefix(30))...", category: .session)

        if noiseService.hasEstablishedSession(with: recipientID) {

            do {

                let privateMessage = PrivateMessagePacket(messageID: messageID, content: content)
                guard let tlvData = privateMessage.encode() else {
                    SecureLogger.error("Failed to encode private message with TLV")
                    return
                }

                var messagePayload = Data([NoisePayloadType.privateMessage.rawValue])
                messagePayload.append(tlvData)

                let encrypted = try noiseService.encrypt(messagePayload, for: recipientID)

                var recipientData = Data()
                var tempID = recipientID.id
                while tempID.count >= 2 {
                    let hexByte = String(tempID.prefix(2))
                    if let byte = UInt8(hexByte, radix: 16) {
                        recipientData.append(byte)
                    }
                    tempID = String(tempID.dropFirst(2))
                }
                if tempID.count == 1 {
                    if let byte = UInt8(tempID, radix: 16) {
                        recipientData.append(byte)
                    }
                }

                let packet = BitchatPacket(
                    type: MessageType.noiseEncrypted.rawValue,
                    senderID: myPeerIDData,
                    recipientID: recipientData,
                    timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                    payload: encrypted,
                    signature: nil,
                    ttl: messageTTL
                )

                broadcastPacket(packet)

                notifyUI { [weak self] in
                    self?.delegate?.didUpdateMessageDeliveryStatus(messageID, status: .sent)
                }
            } catch {
                SecureLogger.error("Failed to encrypt message: \(error)")
            }
        } else {

            SecureLogger.debug("No session with \(recipientID), initiating handshake and queueing message", category: .session)

            collectionsQueue.sync(flags: .barrier) {
                if pendingMessagesAfterHandshake[recipientID] == nil {
                    pendingMessagesAfterHandshake[recipientID] = []
                }
                pendingMessagesAfterHandshake[recipientID]?.append((content, messageID))
            }

            initiateNoiseHandshake(with: recipientID)

            notifyUI { [weak self] in
                self?.delegate?.didUpdateMessageDeliveryStatus(messageID, status: .sending)
            }
        }
    }

    private func initiateNoiseHandshake(with peerID: PeerID) {

        guard !noiseService.hasSession(with: peerID) else { return }

        do {
            let handshakeData = try noiseService.initiateHandshake(with: peerID)

            let packet = BitchatPacket(
                type: MessageType.noiseHandshake.rawValue,
                senderID: myPeerIDData,
                recipientID: Data(hexString: peerID.id),
                timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                payload: handshakeData,
                signature: nil,
                ttl: messageTTL
            )
            broadcastPacket(packet)
        } catch {
            SecureLogger.error("Failed to initiate handshake: \(error)")
        }
    }

    private func sendPendingMessagesAfterHandshake(for peerID: PeerID) {

        let pendingMessages = collectionsQueue.sync(flags: .barrier) { () -> [(content: String, messageID: String)]? in
            let messages = pendingMessagesAfterHandshake[peerID]
            pendingMessagesAfterHandshake.removeValue(forKey: peerID)
            return messages
        }

        guard let messages = pendingMessages, !messages.isEmpty else { return }

        SecureLogger.debug("Sending \(messages.count) pending messages after handshake to \(peerID)", category: .session)

        var failedMessages: [(content: String, messageID: String)] = []

        for (content, messageID) in messages {
            do {

                let privateMessage = PrivateMessagePacket(messageID: messageID, content: content)
                guard let tlvData = privateMessage.encode() else {
                    SecureLogger.error("Failed to encode pending private message TLV")
                    failedMessages.append((content, messageID))
                    continue
                }

                var messagePayload = Data([NoisePayloadType.privateMessage.rawValue])
                messagePayload.append(tlvData)

                let encrypted = try noiseService.encrypt(messagePayload, for: peerID)

                let packet = BitchatPacket(
                    type: MessageType.noiseEncrypted.rawValue,
                    senderID: myPeerIDData,
                    recipientID: Data(hexString: peerID.id),
                    timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                    payload: encrypted,
                    signature: nil,
                    ttl: messageTTL
                )

                broadcastPacket(packet)

                notifyUI { [weak self] in
                    self?.delegate?.didUpdateMessageDeliveryStatus(messageID, status: .sent)
                }

                SecureLogger.debug("Sent pending message \(messageID) to \(peerID) after handshake", category: .session)
            } catch {
                SecureLogger.error("Failed to send pending message after handshake: \(error)")
                failedMessages.append((content, messageID))

                notifyUI { [weak self] in
                    self?.delegate?.didUpdateMessageDeliveryStatus(messageID, status: .failed(reason: "Encryption failed"))
                }
            }
        }

        if !failedMessages.isEmpty {
            collectionsQueue.async(flags: .barrier) { [weak self] in
                guard let self = self else { return }
                if self.pendingMessagesAfterHandshake[peerID] == nil {
                    self.pendingMessagesAfterHandshake[peerID] = []
                }

                self.pendingMessagesAfterHandshake[peerID]?.insert(contentsOf: failedMessages, at: 0)
                SecureLogger.warning("Re-queued \(failedMessages.count) failed messages for \(peerID)", category: .session)
            }
        }
    }

    private func sendFragmentedPacket(_ packet: BitchatPacket, pad: Bool, maxChunk: Int? = nil, directedOnlyPeer: PeerID? = nil, transferId: String? = nil) {
        let context = PendingFragmentTransfer(packet: packet, pad: pad, maxChunk: maxChunk, directedPeer: directedOnlyPeer, transferId: transferId)
        if packet.type == MessageType.fileTransfer.rawValue {
            let shouldQueue = collectionsQueue.sync {
                self.activeTransfers.count >= TransportConfig.bleMaxConcurrentTransfers
            }
            if shouldQueue {
                queueFragmentTransfer(context, prioritizeFront: false)
                return
            }
        }
        startFragmentedPacket(context)
    }

    private func queueFragmentTransfer(_ context: PendingFragmentTransfer, prioritizeFront: Bool) {
        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            if prioritizeFront {
                self.pendingFragmentTransfers.insert(context, at: 0)
            } else {
                self.pendingFragmentTransfers.append(context)
            }
        }
        if let transferId = context.transferId {
            SecureLogger.debug("Queued media transfer \(transferId.prefix(8))… waiting for slot", category: .session)
        } else {
            SecureLogger.debug("Queued fragment transfer waiting for slot", category: .session)
        }
    }

    private func startFragmentedPacket(_ context: PendingFragmentTransfer) {
        let packet = context.packet
        let isFileTransfer = packet.type == MessageType.fileTransfer.rawValue
        var reservedTransferId: String?

        let releaseReservedSlot: (String) -> Void = { id in
            TransferProgressManager.shared.cancel(id: id)
            self.collectionsQueue.async(flags: .barrier) { [weak self] in
                self?.activeTransfers.removeValue(forKey: id)
            }
            self.messageQueue.async { [weak self] in
                self?.startNextPendingTransferIfNeeded()
            }
        }

        if isFileTransfer {
            let candidateId = context.transferId ?? packet.payload.sha256Hex()
            var didReserve = false
            collectionsQueue.sync(flags: .barrier) {
                if self.activeTransfers.count < TransportConfig.bleMaxConcurrentTransfers,
                   self.activeTransfers[candidateId] == nil {
                    self.activeTransfers[candidateId] = ActiveTransferState(totalFragments: 0, sentFragments: 0, workItems: [])
                    didReserve = true
                }
            }
            guard didReserve else {
                queueFragmentTransfer(context, prioritizeFront: true)
                return
            }
            reservedTransferId = candidateId
        }

        guard let fullData = packet.toBinaryData(padding: context.pad) else {
            if let id = reservedTransferId {
                releaseReservedSlot(id)
            }
            return
        }

        let fragmentID = Data((0..<8).map { _ in UInt8.random(in: 0...255) })

        var fragmentVersion: UInt8 = 1
        var calculatedChunk = defaultFragmentSize

        if let route = packet.route, !route.isEmpty {
            fragmentVersion = 2

            let routeSize = 1 + (route.count * 8)

            let overhead = 16 + 8 + 8 + routeSize + 13 + 16
            calculatedChunk = max(64, bleMaxMTU - overhead)
        }

        let chunk = context.maxChunk ?? calculatedChunk
        let safeChunk = max(64, chunk)
        let fragments = stride(from: 0, to: fullData.count, by: safeChunk).map { offset in
            Data(fullData[offset..<min(offset + safeChunk, fullData.count)])
        }
        guard !fragments.isEmpty else {
            if let id = reservedTransferId {
                releaseReservedSlot(id)
            }
            return
        }

        let totalFragments = fragments.count
        if totalFragments > 4 {
            bleQueue.async { [weak self] in
                guard let self = self, let c = self.centralManager, c.state == .poweredOn else { return }
                if c.isScanning { c.stopScan() }
                let expectedMs = min(TransportConfig.bleExpectedWriteMaxMs, totalFragments * TransportConfig.bleExpectedWritePerFragmentMs)
                self.bleQueue.asyncAfter(deadline: .now() + .milliseconds(expectedMs)) { [weak self] in
                    self?.startScanning()
                }
            }
        }
        let perFragMs = (context.directedPeer != nil || packet.recipientID != nil) ? TransportConfig.bleFragmentSpacingDirectedMs : TransportConfig.bleFragmentSpacingMs

        let transferIdentifier: String? = {
            guard let id = reservedTransferId else { return nil }
            collectionsQueue.sync(flags: .barrier) {
                self.activeTransfers[id] = ActiveTransferState(totalFragments: totalFragments, sentFragments: 0, workItems: [])
            }
            TransferProgressManager.shared.start(id: id, totalFragments: totalFragments)
            return id
        }()

        var scheduledItems: [(item: DispatchWorkItem, index: Int)] = []

        for (index, fragment) in fragments.enumerated() {
            var payload = Data()
            payload.append(fragmentID)
            payload.append(contentsOf: withUnsafeBytes(of: UInt16(index).bigEndian) { Data($0) })
            payload.append(contentsOf: withUnsafeBytes(of: UInt16(fragments.count).bigEndian) { Data($0) })
            payload.append(packet.type)
            payload.append(fragment)

            let fragmentRecipient: Data? = {
                if let only = context.directedPeer { return Data(hexString: only.id) }
                return packet.recipientID
            }()

            let fragmentPacket = BitchatPacket(
                type: MessageType.fragment.rawValue,
                senderID: packet.senderID,
                recipientID: fragmentRecipient,
                timestamp: packet.timestamp,
                payload: payload,
                signature: nil,
                ttl: packet.ttl,
                version: fragmentVersion,
                route: packet.route,
                isRSR: packet.isRSR
            )

            let workItem = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                if let transferId = transferIdentifier {
                    let isActive = self.collectionsQueue.sync { self.activeTransfers[transferId] != nil }
                    guard isActive else { return }
                }
                if fragmentRecipient == nil || fragmentRecipient?.allSatisfy({ $0 == 0xFF }) == true {
                    self.gossipSyncManager?.onPublicPacketSeen(fragmentPacket)
                }
                self.broadcastPacket(fragmentPacket)
                if let transferId = transferIdentifier {
                    self.markFragmentSent(transferId: transferId)
                }
            }

            scheduledItems.append((item: workItem, index: index))
        }

        if let transferId = transferIdentifier {
            let workItems = scheduledItems.map { $0.item }
            collectionsQueue.async(flags: .barrier) { [weak self] in
                guard let self = self, var state = self.activeTransfers[transferId] else { return }
                state.workItems = workItems
                self.activeTransfers[transferId] = state
            }
        }

        for (workItem, index) in scheduledItems {
            let delayMs = index * perFragMs
            messageQueue.asyncAfter(deadline: .now() + .milliseconds(delayMs), execute: workItem)
        }
    }

    private func markFragmentSent(transferId: String) {
        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self, var state = self.activeTransfers[transferId] else { return }
            state.sentFragments = min(state.sentFragments + 1, state.totalFragments)
            let isComplete = state.sentFragments >= state.totalFragments
            if isComplete {
                self.activeTransfers.removeValue(forKey: transferId)
            } else {
                self.activeTransfers[transferId] = state
            }
            TransferProgressManager.shared.recordFragmentSent(id: transferId)
            if isComplete {
                self.messageQueue.async { [weak self] in
                    self?.startNextPendingTransferIfNeeded()
                }
            }
        }
    }

    private func startNextPendingTransferIfNeeded() {
        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            let limit = TransportConfig.bleMaxConcurrentTransfers
            var availableSlots = max(0, limit - self.activeTransfers.count)
            guard availableSlots > 0, !self.pendingFragmentTransfers.isEmpty else { return }
            var toStart: [PendingFragmentTransfer] = []
            while availableSlots > 0, !self.pendingFragmentTransfers.isEmpty {
                toStart.append(self.pendingFragmentTransfers.removeFirst())
                availableSlots -= 1
            }
            for context in toStart {
                self.messageQueue.async { [weak self] in
                    self?.startFragmentedPacket(context)
                }
            }
        }
    }

    private func handleFragment(_ packet: BitchatPacket, from peerID: PeerID) {
        if DispatchQueue.getSpecific(key: messageQueueKey) != nil {
            _handleFragment(packet, from: peerID)
        } else {
            messageQueue.async(flags: .barrier) { [weak self] in
                self?._handleFragment(packet, from: peerID)
            }
        }
    }

    private func _handleFragment(_ packet: BitchatPacket, from peerID: PeerID) {

        if peerID == myPeerID {
            return
        }

        guard packet.payload.count >= 13 else { return }

        var senderU64: UInt64 = 0
        for b in packet.senderID.prefix(8) { senderU64 = (senderU64 << 8) | UInt64(b) }
        var fragU64: UInt64 = 0
        for b in packet.payload.prefix(8) { fragU64 = (fragU64 << 8) | UInt64(b) }

        let idxHi = UInt16(packet.payload[8])
        let idxLo = UInt16(packet.payload[9])
        let index = Int((idxHi << 8) | idxLo)
        let totHi = UInt16(packet.payload[10])
        let totLo = UInt16(packet.payload[11])
        let total = Int((totHi << 8) | totLo)
        let originalType = packet.payload[12]
        let fragmentData = packet.payload.suffix(from: 13)

        guard total > 0 && total <= 10000 && index >= 0 && index < total else { return }

        let isBroadcastFragment: Bool = {
            guard let recipient = packet.recipientID else { return true }
            return recipient.count == 8 && recipient.allSatisfy { $0 == 0xFF }
        }()
        if isBroadcastFragment {
            gossipSyncManager?.onPublicPacketSeen(packet)
        }

        let key = FragmentKey(sender: senderU64, id: fragU64)

        var shouldReassemble: Bool = false
        var fragmentsToReassemble: [Int: Data]? = nil

        collectionsQueue.sync(flags: .barrier) {
            if incomingFragments[key] == nil {

                if incomingFragments.count >= maxInFlightAssemblies {

                    if let oldest = fragmentMetadata.min(by: { $0.value.timestamp < $1.value.timestamp })?.key {
                        incomingFragments.removeValue(forKey: oldest)
                        fragmentMetadata.removeValue(forKey: oldest)
                    }
                }
                incomingFragments[key] = [:]
                fragmentMetadata[key] = (originalType, total, Date())
                SecureLogger.debug("Started fragment assembly id=\(String(format: "%016llx", fragU64)) total=\(total)", category: .session)
            }

            let currentSize = incomingFragments[key]?.values.reduce(0) { $0 + $1.count } ?? 0
            let assemblyLimit: Int = {
                if originalType == MessageType.fileTransfer.rawValue {

                    return FileTransferLimits.maxFramedFileBytes
                }
                return FileTransferLimits.maxPayloadBytes
            }()
            let projectedSize = currentSize + fragmentData.count
            guard projectedSize <= assemblyLimit else {

                SecureLogger.warning(
                    "Fragment assembly exceeds size limit (\(projectedSize) bytes > \(assemblyLimit)), evicting. Type=\(originalType) Index=\(index)/\(total)",
                    category: .security
                )
                incomingFragments.removeValue(forKey: key)
                fragmentMetadata.removeValue(forKey: key)
                shouldReassemble = false
                fragmentsToReassemble = nil
                return
            }

            incomingFragments[key]?[index] = Data(fragmentData)
            SecureLogger.debug("Fragment \(index + 1)/\(total) (len=\(fragmentData.count)) for id=\(String(format: "%016llx", fragU64))", category: .session)

            if let fragments = incomingFragments[key], fragments.count == total {
                shouldReassemble = true
                fragmentsToReassemble = fragments
            } else {
                shouldReassemble = false
                fragmentsToReassemble = nil
            }
        }

        guard shouldReassemble, let fragments = fragmentsToReassemble else { return }

        var reassembled = Data()
        for i in 0..<total {
            if let fragment = fragments[i] {
                reassembled.append(fragment)
            }
        }

        if var originalPacket = BinaryProtocol.decode(reassembled) {

            let innerSender = PeerID(hexData: originalPacket.senderID)
            if !validatePacket(originalPacket, from: innerSender) {

            } else {
                SecureLogger.debug("Reassembled packet id=\(String(format: "%016llx", fragU64)) type=\(originalPacket.type) bytes=\(reassembled.count)", category: .session)
                originalPacket.ttl = 0
                handleReceivedPacket(originalPacket, from: peerID)
            }
        } else {
            SecureLogger.error("Failed to decode reassembled packet (type=\(originalType), total=\(total))", category: .session)
        }

        collectionsQueue.sync(flags: .barrier) {
            incomingFragments.removeValue(forKey: key)
            fragmentMetadata.removeValue(forKey: key)
        }
    }

    private func handleReceivedPacket(_ packet: BitchatPacket, from peerID: PeerID) {

        if DispatchQueue.getSpecific(key: messageQueueKey) == nil {
            messageQueue.async { [weak self] in
                self?.handleReceivedPacket(packet, from: peerID)
            }
            return
        }

        let senderID = PeerID(hexData: packet.senderID)

        let messageID = "\(senderID)-\(packet.timestamp)-\(packet.type)"

        if packet.type != MessageType.announce.rawValue {

            SecureLogger.debug("Handling packet type \(packet.type) from \(senderID), messageID: \(messageID)", category: .session)
        }

        let allowSelfSyncReplay = (packet.ttl == 0) && (senderID == myPeerID)
        if packet.type != MessageType.fragment.rawValue && !allowSelfSyncReplay && messageDeduplicator.isDuplicate(messageID) {

            if packet.type != MessageType.announce.rawValue {
                SecureLogger.debug("Duplicate packet ignored: \(messageID)", category: .session)
            }

            let connectedCount = collectionsQueue.sync { peers.values.filter { $0.isConnected }.count }
            if connectedCount > 2 {
                collectionsQueue.async(flags: .barrier) { [weak self] in
                    if let task = self?.scheduledRelays.removeValue(forKey: messageID) {
                        task.cancel()
                    }
                }
            }
            return
        }

        updatePeerLastSeen(peerID)

        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            let now = Date()
            self.recentPacketTimestamps.append(now)

            let cutoff = now.addingTimeInterval(-TransportConfig.bleRecentPacketWindowSeconds)
            if self.recentPacketTimestamps.count > TransportConfig.bleRecentPacketWindowMaxCount {
                self.recentPacketTimestamps.removeFirst(self.recentPacketTimestamps.count - TransportConfig.bleRecentPacketWindowMaxCount)
            }
            self.recentPacketTimestamps.removeAll { $0 < cutoff }
        }

        switch MessageType(rawValue: packet.type) {
        case .announce:
            handleAnnounce(packet, from: senderID)

        case .message:
            handleMessage(packet, from: senderID)

        case .requestSync:
            handleRequestSync(packet, from: senderID)

        case .noiseHandshake:
            handleNoiseHandshake(packet, from: senderID)

        case .noiseEncrypted:
            handleNoiseEncrypted(packet, from: senderID)

        case .fragment:
            handleFragment(packet, from: senderID)

        case .fileTransfer:
            handleFileTransfer(packet, from: senderID)

        case .leave:
            handleLeave(packet, from: senderID)

        case .none:
            SecureLogger.warning("Unknown message type: \(packet.type)", category: .session)
            break
        }

        if forwardAlongRouteIfNeeded(packet) {
            return
        }

        do {
            let degree = collectionsQueue.sync { peers.values.filter { $0.isConnected }.count }
            let decision = RelayController.decide(
                ttl: packet.ttl,
                senderIsSelf: senderID == myPeerID,
                isEncrypted: packet.type == MessageType.noiseEncrypted.rawValue,
                isDirectedEncrypted: (packet.type == MessageType.noiseEncrypted.rawValue) && (packet.recipientID != nil),
                isFragment: packet.type == MessageType.fragment.rawValue,
                isDirectedFragment: packet.type == MessageType.fragment.rawValue && packet.recipientID != nil,
                isHandshake: packet.type == MessageType.noiseHandshake.rawValue,
                isAnnounce: packet.type == MessageType.announce.rawValue,
                degree: degree,
                highDegreeThreshold: highDegreeThreshold
            )
            guard decision.shouldRelay else { return }
            let work = DispatchWorkItem { [weak self] in
                guard let self = self else { return }

                self.collectionsQueue.async(flags: .barrier) { [weak self] in
                    _ = self?.scheduledRelays.removeValue(forKey: messageID)
                }
                var relayPacket = packet
                relayPacket.ttl = decision.newTTL
                self.broadcastPacket(relayPacket)
            }

            collectionsQueue.async(flags: .barrier) { [weak self] in
                self?.scheduledRelays[messageID] = work
            }
            messageQueue.asyncAfter(deadline: .now() + .milliseconds(decision.delayMs), execute: work)
        }
    }

    private func handleAnnounce(_ packet: BitchatPacket, from peerID: PeerID) {
        guard let announcement = AnnouncementPacket.decode(from: packet.payload) else {
            SecureLogger.error("Failed to decode announce packet from \(peerID)", category: .session)
            return
        }

        let derivedFromKey = PeerID(publicKey: announcement.noisePublicKey)
        if derivedFromKey != peerID {
            SecureLogger.warning("Announce sender mismatch: derived \(derivedFromKey.id.prefix(8))… vs packet \(peerID.id.prefix(8))…", category: .security)
            return
        }

        if peerID == myPeerID {
            return
        }

        let maxAnnounceAgeSeconds: TimeInterval = 900
        let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let ageThresholdMs = UInt64(maxAnnounceAgeSeconds * 1000)
        if nowMs >= ageThresholdMs {
            let cutoffMs = nowMs - ageThresholdMs
            if packet.timestamp < cutoffMs {
                SecureLogger.debug("⏰ Ignoring stale announce from \(peerID.id.prefix(8))… (age: \(Double(nowMs - packet.timestamp) / 1000.0)s)", category: .session)
                return
            }
        }

        let existingPeerForVerify = collectionsQueue.sync { peers[peerID] }
        var verifiedAnnounce = false
        if packet.signature != nil {
            verifiedAnnounce = noiseService.verifyPacketSignature(packet, publicKey: announcement.signingPublicKey)
            if !verifiedAnnounce {
                SecureLogger.warning("Signature verification for announce failed \(peerID.id.prefix(8))", category: .security)
            }
        }
        if let existingKey = existingPeerForVerify?.noisePublicKey, existingKey != announcement.noisePublicKey {
            SecureLogger.warning("Announce key mismatch for \(peerID.id.prefix(8))… — keeping unverified", category: .security)
            verifiedAnnounce = false
        }

        var isNewPeer = false
        var isReconnectedPeer = false
        let directLinkState = linkState(for: peerID)

        collectionsQueue.sync(flags: .barrier) {

            let hasPeripheralConnection = directLinkState.hasPeripheral

            let hasCentralSubscription = directLinkState.hasCentral

            let isDirectAnnounce = (packet.ttl == messageTTL)

            let existingPeer = peers[peerID]
            let wasDisconnected = existingPeer?.isConnected == false

            isNewPeer = (existingPeer == nil)
            isReconnectedPeer = wasDisconnected

            let verified = verifiedAnnounce

            if !verified {
                SecureLogger.warning("Ignoring unverified announce from \(peerID.id.prefix(8))…", category: .security)

                isNewPeer = false
                isReconnectedPeer = false
                return
            }

            if let existing = existingPeer, existing.isConnected {

                peers[peerID] = PeerInfo(
                    peerID: existing.peerID,
                    nickname: announcement.nickname,
                    isConnected: isDirectAnnounce || hasPeripheralConnection || hasCentralSubscription,
                    noisePublicKey: announcement.noisePublicKey,
                    signingPublicKey: announcement.signingPublicKey,
                    isVerifiedNickname: true,
                    lastSeen: Date()
                )
            } else {

                peers[peerID] = PeerInfo(
                    peerID: peerID,
                    nickname: announcement.nickname,
                    isConnected: isDirectAnnounce || hasPeripheralConnection || hasCentralSubscription,
                    noisePublicKey: announcement.noisePublicKey,
                    signingPublicKey: announcement.signingPublicKey,
                    isVerifiedNickname: true,
                    lastSeen: Date()
                )
            }

            if isDirectAnnounce || hasPeripheralConnection || hasCentralSubscription {
                let now = Date()
                if existingPeer == nil {
                    SecureLogger.debug("New peer: \(announcement.nickname)", category: .session)
                } else if wasDisconnected {

                    if let last = lastReconnectLogAt[peerID], now.timeIntervalSince(last) < TransportConfig.bleReconnectLogDebounceSeconds {

                    } else {
                        SecureLogger.debug("Peer \(announcement.nickname) reconnected", category: .session)
                        lastReconnectLogAt[peerID] = now
                    }
                } else if existingPeer?.nickname != announcement.nickname {
                    SecureLogger.debug("Peer \(peerID) changed nickname: \(existingPeer?.nickname ?? "Unknown") -> \(announcement.nickname)", category: .session)
                }
            }
        }

        if verifiedAnnounce, let neighbors = announcement.directNeighbors {
            meshTopology.updateNeighbors(for: peerID.routingData, neighbors: neighbors)
        }

        identityManager.upsertCryptographicIdentity(
            fingerprint: announcement.noisePublicKey.sha256Fingerprint(),
            noisePublicKey: announcement.noisePublicKey,
            signingPublicKey: announcement.signingPublicKey,
            claimedNickname: announcement.nickname
        )

        notifyUI { [weak self] in
            guard let self = self else { return }

            let currentPeerIDs = self.collectionsQueue.sync { self.currentPeerIDs }

            if (packet.ttl == self.messageTTL) && (isNewPeer || isReconnectedPeer) {
                self.delegate?.didConnectToPeer(peerID)

                self.gossipSyncManager?.scheduleInitialSyncToPeer(peerID, delaySeconds: 1.0)
            }

            self.requestPeerDataPublish()
            self.delegate?.didUpdatePeerList(currentPeerIDs)
        }

        gossipSyncManager?.onPublicPacketSeen(packet)

        let announceBackID = "announce-back-\(peerID)"
        let shouldSendBack = !messageDeduplicator.contains(announceBackID)
        if shouldSendBack {
            messageDeduplicator.markProcessed(announceBackID)
        }

        if shouldSendBack {

            sendAnnounce(forceSend: true)
        }

        if isNewPeer {
            let delay = Double.random(in: 0.3...0.6)
            messageQueue.asyncAfter(deadline: .now() + delay) { [weak self] in
                self?.sendAnnounce(forceSend: true)
            }
        }
    }

    private func handleRequestSync(_ packet: BitchatPacket, from peerID: PeerID) {
        guard let req = RequestSyncPacket.decode(from: packet.payload) else {
            SecureLogger.warning("Malformed REQUEST_SYNC from \(peerID)", category: .session)
            return
        }
        gossipSyncManager?.handleRequestSync(from: peerID, request: req)
    }

    private func handleMessage(_ packet: BitchatPacket, from peerID: PeerID) {

        if peerID == myPeerID && packet.ttl != 0 { return }

        let isBroadcast: Bool = {
            guard let r = packet.recipientID else { return true }
            return r.count == 8 && r.allSatisfy { $0 == 0xFF }
        }()
        if isBroadcast {
            let maxMessageAgeSeconds: TimeInterval = 900
            let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
            let ageThresholdMs = UInt64(maxMessageAgeSeconds * 1000)
            if nowMs >= ageThresholdMs {
                let cutoffMs = nowMs - ageThresholdMs
                if packet.timestamp < cutoffMs {
                    SecureLogger.debug("⏰ Ignoring stale broadcast message from \(peerID.id.prefix(8))… (age: \(Double(nowMs - packet.timestamp) / 1000.0)s)", category: .session)
                    return
                }
            }
        }

        var accepted = false
        var senderNickname: String = ""

        let peersSnapshot = collectionsQueue.sync { peers }

        if peerID == myPeerID {
            accepted = true
            senderNickname = myNickname
        }
        else if let info = peersSnapshot[peerID], info.isVerifiedNickname {

            accepted = true
            senderNickname = info.nickname

            let hasCollision = peersSnapshot.values.contains { $0.isConnected && $0.nickname == info.nickname && $0.peerID != peerID } || (myNickname == info.nickname)
            if hasCollision {
                senderNickname += "#" + String(peerID.id.prefix(4))
            }
        } else {

            if let signature = packet.signature, let packetData = packet.toBinaryDataForSigning() {

                let candidates = identityManager.getCryptoIdentitiesByPeerIDPrefix(peerID)
                for candidate in candidates {
                    if let signingKey = candidate.signingPublicKey,
                       noiseService.verifySignature(signature, for: packetData, publicKey: signingKey) {
                        accepted = true

                        if let social = identityManager.getSocialIdentity(for: candidate.fingerprint) {
                            senderNickname = social.localPetname ?? social.claimedNickname
                        } else {
                            senderNickname = "anon" + String(peerID.id.prefix(4))
                        }
                        break
                    }
                }
            }
        }

        guard accepted else {
            SecureLogger.warning("Dropping public message from unverified or unknown peer \(peerID.id.prefix(8))…", category: .security)
            return
        }

        let isBroadcastRecipient: Bool = {
            guard let r = packet.recipientID else { return true }
            return r.count == 8 && r.allSatisfy { $0 == 0xFF }
        }()
        if isBroadcastRecipient && packet.type == MessageType.message.rawValue {
            gossipSyncManager?.onPublicPacketSeen(packet)
        }

        guard let content = String(data: packet.payload, encoding: .utf8) else {
            SecureLogger.error("Failed to decode message payload as UTF-8", category: .session)
            return
        }

        let directLink = linkState(for: peerID)
        let hasDirectLink = directLink.hasPeripheral || directLink.hasCentral

        let pathTag = hasDirectLink ? "direct" : "mesh"
        SecureLogger.debug("[\(senderNickname)] TTL:\(packet.ttl) (\(pathTag)): \(String(content.prefix(50)))\(content.count > 50 ? "..." : "")", category: .session)

        let ts = Date(timeIntervalSince1970: Double(packet.timestamp) / 1000)
        var resolvedSelfMessageID: String? = nil
        if peerID == myPeerID {
            let senderHex = packet.senderID.hexEncodedString()
            let dedupID = "\(senderHex)-\(packet.timestamp)-\(packet.type)"
            resolvedSelfMessageID = selfBroadcastMessageIDs.removeValue(forKey: dedupID)?.id
        }
        notifyUI { [weak self] in
            self?.delegate?.didReceivePublicMessage(from: peerID,
                                                    nickname: senderNickname,
                                                    content: content,
                                                    timestamp: ts,
                                                    messageID: resolvedSelfMessageID)
        }
    }

    private func handleNoiseHandshake(_ packet: BitchatPacket, from peerID: PeerID) {

        if PeerID(hexData: packet.recipientID) == myPeerID {

            do {
                if let response = try noiseService.processHandshakeMessage(from: peerID, message: packet.payload) {

                    let responsePacket = BitchatPacket(
                        type: MessageType.noiseHandshake.rawValue,
                        senderID: myPeerIDData,
                        recipientID: Data(hexString: peerID.id),
                        timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                        payload: response,
                        signature: nil,
                        ttl: messageTTL
                    )

                    broadcastPacket(responsePacket)
                }

            } catch {
                SecureLogger.error("Failed to process handshake: \(error)")

                if !noiseService.hasSession(with: peerID) {
                    initiateNoiseHandshake(with: peerID)
                }
            }
        }
    }

    private func handleNoiseEncrypted(_ packet: BitchatPacket, from peerID: PeerID) {
        SecureLogger.debug("handleNoiseEncrypted called for packet from \(peerID)")

        guard let recipientID = PeerID(hexData: packet.recipientID) else {
            SecureLogger.warning("Encrypted message has no recipient ID", category: .session)
            return
        }

        if recipientID != myPeerID {
            SecureLogger.debug("Encrypted message not for me (for \(recipientID), I am \(myPeerID))", category: .session)
            return
        }

        updatePeerLastSeen(peerID)

        do {
            let decrypted = try noiseService.decrypt(packet.payload, from: peerID)
            guard decrypted.count > 0 else { return }

            let payloadType = decrypted[0]
            let payloadData = decrypted.dropFirst()

            switch NoisePayloadType(rawValue: payloadType) {
            case .privateMessage:
                let ts = Date(timeIntervalSince1970: Double(packet.timestamp) / 1000)
                notifyUI { [weak self] in
                    self?.delegate?.didReceiveNoisePayload(from: peerID, type: .privateMessage, payload: Data(payloadData), timestamp: ts)
                }
            case .delivered:
                let ts = Date(timeIntervalSince1970: Double(packet.timestamp) / 1000)
                notifyUI { [weak self] in
                    self?.delegate?.didReceiveNoisePayload(from: peerID, type: .delivered, payload: Data(payloadData), timestamp: ts)
                }
            case .readReceipt:
                let ts = Date(timeIntervalSince1970: Double(packet.timestamp) / 1000)
                notifyUI { [weak self] in
                    self?.delegate?.didReceiveNoisePayload(from: peerID, type: .readReceipt, payload: Data(payloadData), timestamp: ts)
                }
            case .verifyChallenge:
                let ts = Date(timeIntervalSince1970: Double(packet.timestamp) / 1000)
                notifyUI { [weak self] in
                    self?.delegate?.didReceiveNoisePayload(from: peerID, type: .verifyChallenge, payload: Data(payloadData), timestamp: ts)
                }
            case .verifyResponse:
                let ts = Date(timeIntervalSince1970: Double(packet.timestamp) / 1000)
                notifyUI { [weak self] in
                    self?.delegate?.didReceiveNoisePayload(from: peerID, type: .verifyResponse, payload: Data(payloadData), timestamp: ts)
                }
            case .none:
                SecureLogger.warning("Unknown noise payload type: \(payloadType)")
            }
        } catch NoiseEncryptionError.sessionNotEstablished {

            SecureLogger.debug("Encrypted message from \(peerID) without session; initiating handshake")
            if !noiseService.hasSession(with: peerID) {
                initiateNoiseHandshake(with: peerID)
            }
        } catch {

            SecureLogger.error("Failed to decrypt message from \(peerID): \(error) - clearing session and re-initiating handshake")
            noiseService.clearSession(for: peerID)
            initiateNoiseHandshake(with: peerID)
        }
    }

    private func sendPendingNoisePayloadsAfterHandshake(for peerID: PeerID) {
        let payloads = collectionsQueue.sync(flags: .barrier) { () -> [Data] in
            let list = pendingNoisePayloadsAfterHandshake[peerID] ?? []
            pendingNoisePayloadsAfterHandshake.removeValue(forKey: peerID)
            return list
        }
        guard !payloads.isEmpty else { return }
        SecureLogger.debug("Sending \(payloads.count) pending noise payloads to \(peerID) after handshake", category: .session)
        for payload in payloads {
            do {
                let encrypted = try noiseService.encrypt(payload, for: peerID)
                let packet = BitchatPacket(
                    type: MessageType.noiseEncrypted.rawValue,
                    senderID: myPeerIDData,
                    recipientID: Data(hexString: peerID.id),
                    timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
                    payload: encrypted,
                    signature: nil,
                    ttl: messageTTL
                )
                broadcastPacket(packet)
            } catch {
                SecureLogger.error("Failed to send pending noise payload to \(peerID): \(error)")
            }
        }
    }

    private func updatePeerLastSeen(_ peerID: PeerID) {

        collectionsQueue.async(flags: .barrier) {
            if var peer = self.peers[peerID] {
                peer.lastSeen = Date()
                self.peers[peerID] = peer
            }
        }
    }

    private func notifyPeerDisconnectedDebounced(_ peerID: PeerID) {
        let now = Date()
        let last = recentDisconnectNotifies[peerID]
        if last == nil || now.timeIntervalSince(last!) >= TransportConfig.bleDisconnectNotifyDebounceSeconds {
            delegate?.didDisconnectFromPeer(peerID)
            recentDisconnectNotifies[peerID] = now
        } else {

        }
    }

    private func publishFullPeerData() {
        let transportPeers: [TransportPeerSnapshot] = collectionsQueue.sync {

            let connected = peers.values.filter { $0.isConnected }
            var counts: [String: Int] = [:]
            for p in connected { counts[p.nickname, default: 0] += 1 }
            counts[myNickname, default: 0] += 1
            return peers.values.map { info in
                var display = info.nickname
                if info.isConnected, (counts[info.nickname] ?? 0) > 1 {
                    display += "#" + String(info.peerID.id.prefix(4))
                }
                return TransportPeerSnapshot(
                    peerID: info.peerID,
                    nickname: display,
                    isConnected: info.isConnected,
                    noisePublicKey: info.noisePublicKey,
                    lastSeen: info.lastSeen
                )
            }
        }

        peerSnapshotSubject.send(transportPeers)

        Task { @MainActor [weak self] in
            self?.peerEventsDelegate?.didUpdatePeerSnapshots(transportPeers)
        }
    }

    private func performMaintenance() {
        maintenanceCounter += 1

        let now = Date()
        let connectedCount = collectionsQueue.sync { peers.values.filter { $0.isConnected }.count }
        let elapsed = now.timeIntervalSince(lastAnnounceSent)
        if connectedCount == 0 {

            if elapsed >= TransportConfig.bleAnnounceIntervalSeconds { sendAnnounce(forceSend: true) }
        } else {

            let base = connectedCount >= TransportConfig.bleHighDegreeThreshold ?
                TransportConfig.bleConnectedAnnounceBaseSecondsDense : TransportConfig.bleConnectedAnnounceBaseSecondsSparse
            let jitter = connectedCount >= TransportConfig.bleHighDegreeThreshold ?
                TransportConfig.bleConnectedAnnounceJitterDense : TransportConfig.bleConnectedAnnounceJitterSparse
            let target = base + Double.random(in: -jitter...jitter)
            if elapsed >= target { sendAnnounce(forceSend: true) }
        }

        let recentSeen = collectionsQueue.sync { () -> Bool in
            let cutoff = now.addingTimeInterval(-5.0)
            return recentPacketTimestamps.contains(where: { $0 >= cutoff })
        }
        if recentSeen && elapsed >= 10.0 {
            sendAnnounce(forceSend: true)
        }

        if peers.isEmpty {

            if let pm = peripheralManager, pm.state == .poweredOn && !pm.isAdvertising {
                pm.startAdvertising(buildAdvertisementData())
            }
        }

        updateScanningDutyCycle(connectedCount: connectedCount)
        updateRSSIThreshold(connectedCount: connectedCount)

        checkPeerConnectivity()

        if maintenanceCounter % 3 == 0 {
            performCleanup()
        }

        if maintenanceCounter % 2 == 1 {
            flushDirectedSpool()
        }

        drainPendingNotificationsIfPossible()
        drainAllPendingWrites()

        if maintenanceCounter >= 6 {
            maintenanceCounter = 0
        }
    }

    private func checkPeerConnectivity() {
        let now = Date()
        var disconnectedPeers: [PeerID] = []
        let peerIDsForLinkState: [PeerID] = collectionsQueue.sync { Array(peers.keys) }
        var cachedLinkStates: [PeerID: (hasPeripheral: Bool, hasCentral: Bool)] = [:]
        for peerID in peerIDsForLinkState {
            cachedLinkStates[peerID] = linkState(for: peerID)
        }

        var removedOfflineCount = 0
        collectionsQueue.sync(flags: .barrier) {
            for (peerID, peer) in peers {
                let age = now.timeIntervalSince(peer.lastSeen)
                let retention: TimeInterval = peer.isVerifiedNickname ? TransportConfig.bleReachabilityRetentionVerifiedSeconds : TransportConfig.bleReachabilityRetentionUnverifiedSeconds
                if peer.isConnected && age > TransportConfig.blePeerInactivityTimeoutSeconds {

                    let state = cachedLinkStates[peerID] ?? (hasPeripheral: false, hasCentral: false)
                    let hasPeripheralConnection = state.hasPeripheral
                    let hasCentralConnection = state.hasCentral

                    if !hasPeripheralConnection && !hasCentralConnection {
                        var updated = peer
                        updated.isConnected = false
                        peers[peerID] = updated
                        disconnectedPeers.append(peerID)
                    }
                }

                if !peer.isConnected {
                    if age > retention {
                        SecureLogger.debug("Removing stale peer after reachability window: \(peerID) (\(peer.nickname))", category: .session)

                        gossipSyncManager?.removeAnnouncementForPeer(peerID)
                        peers.removeValue(forKey: peerID)
                        removedOfflineCount += 1
                    }
                }
            }
        }

        if !disconnectedPeers.isEmpty || removedOfflineCount > 0 {
            notifyUI { [weak self] in
                guard let self else { return }

                let currentPeerIDs = self.collectionsQueue.sync { self.currentPeerIDs }

                for peerID in disconnectedPeers {
                    self.delegate?.didDisconnectFromPeer(peerID)
                }

                self.requestPeerDataPublish()
                self.delegate?.didUpdatePeerList(currentPeerIDs)
            }
        }

        refreshLocalTopology()

        meshTopology.prune(olderThan: 60.0)
    }

    private func performCleanup() {
        let now = Date()

        messageDeduplicator.cleanup()

        collectionsQueue.sync(flags: .barrier) {
            let cutoff = now.addingTimeInterval(-TransportConfig.bleFragmentLifetimeSeconds)
            let oldFragments = fragmentMetadata.filter { $0.value.timestamp < cutoff }.map { $0.key }
            for fragmentID in oldFragments {
                incomingFragments.removeValue(forKey: fragmentID)
                fragmentMetadata.removeValue(forKey: fragmentID)
            }
        }

        let timeoutCutoff = now.addingTimeInterval(-TransportConfig.bleConnectTimeoutBackoffWindowSeconds)
        recentConnectTimeouts = recentConnectTimeouts.filter { $0.value >= timeoutCutoff }

        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            if !self.scheduledRelays.isEmpty {

                if self.scheduledRelays.count > 512 {
                    self.scheduledRelays.removeAll()
                }
            }
        }

        collectionsQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            let cutoff = now.addingTimeInterval(-TransportConfig.bleIngressRecordLifetimeSeconds)
            if !self.ingressByMessageID.isEmpty {
                self.ingressByMessageID = self.ingressByMessageID.filter { $0.value.timestamp >= cutoff }
            }

            if !self.pendingDirectedRelays.isEmpty {
                var cleaned: [PeerID: [String: (packet: BitchatPacket, enqueuedAt: Date)]] = [:]
                for (recipient, dict) in self.pendingDirectedRelays {
                    let pruned = dict.filter { now.timeIntervalSince($0.value.enqueuedAt) <= TransportConfig.bleDirectedSpoolWindowSeconds }
                    if !pruned.isEmpty { cleaned[recipient] = pruned }
                }
                self.pendingDirectedRelays = cleaned
            }
        }

        messageQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }
            guard !self.selfBroadcastMessageIDs.isEmpty else { return }
            let cutoff = now.addingTimeInterval(-TransportConfig.messageDedupMaxAgeSeconds)
            self.selfBroadcastMessageIDs = self.selfBroadcastMessageIDs.filter { cutoff <= $0.value.timestamp }
        }
    }

    private func updateScanningDutyCycle(connectedCount: Int) {
        guard let central = centralManager, central.state == .poweredOn else { return }

        #if os(iOS)
        let active = isAppActive
        #else
        let active = true
        #endif

        let hasRecentTraffic: Bool = collectionsQueue.sync {
            let cutoff = Date().addingTimeInterval(-TransportConfig.bleRecentTrafficForceScanSeconds)
            return recentPacketTimestamps.contains(where: { $0 >= cutoff })
        }
        let forceScanOn = (connectedCount <= 2) || hasRecentTraffic
        let shouldDuty = dutyEnabled && active && connectedCount > 0 && !forceScanOn
        if shouldDuty {
            if scanDutyTimer == nil {

                let t = DispatchSource.makeTimerSource(queue: bleQueue)

                if !central.isScanning { startScanning() }
                dutyActive = true

                if connectedCount >= TransportConfig.bleHighDegreeThreshold {
                    dutyOnDuration = TransportConfig.bleDutyOnDurationDense
                    dutyOffDuration = TransportConfig.bleDutyOffDurationDense
                } else {
                    dutyOnDuration = TransportConfig.bleDutyOnDuration
                    dutyOffDuration = TransportConfig.bleDutyOffDuration
                }
                t.schedule(deadline: .now() + dutyOnDuration, repeating: dutyOnDuration + dutyOffDuration)
                t.setEventHandler { [weak self] in
                    guard let self = self, let c = self.centralManager else { return }
                    if self.dutyActive {

                        if c.isScanning { c.stopScan() }
                        self.dutyActive = false

                        self.bleQueue.asyncAfter(deadline: .now() + self.dutyOffDuration) {
                            if self.centralManager?.state == .poweredOn { self.startScanning() }
                            self.dutyActive = true
                        }
                    }
                }
                t.resume()
                scanDutyTimer = t
            }
        } else {

            scanDutyTimer?.cancel()
            scanDutyTimer = nil
            if !central.isScanning { startScanning() }
        }
    }

    private func updateRSSIThreshold(connectedCount: Int) {

        if connectedCount == 0 {

            if lastIsolatedAt == nil { lastIsolatedAt = Date() }
            let iso = lastIsolatedAt ?? Date()
            let elapsed = Date().timeIntervalSince(iso)
            if elapsed > TransportConfig.bleIsolationRelaxThresholdSeconds {
                dynamicRSSIThreshold = TransportConfig.bleRSSIIsolatedRelaxed
            } else {
                dynamicRSSIThreshold = TransportConfig.bleRSSIIsolatedBase
            }
            return
        }
        lastIsolatedAt = nil

        var threshold = TransportConfig.bleDynamicRSSIThresholdDefault

        let linkCount = peripherals.values.filter { $0.isConnected || $0.isConnecting }.count
        if linkCount >= maxCentralLinks || connectionCandidates.count > TransportConfig.bleConnectionCandidatesMax {
            threshold = TransportConfig.bleRSSIConnectedThreshold
        }

        let recentTimeouts = recentConnectTimeouts.filter { Date().timeIntervalSince($0.value) < TransportConfig.bleRecentTimeoutWindowSeconds }.count
        if recentTimeouts >= TransportConfig.bleRecentTimeoutCountThreshold {
            threshold = max(threshold, TransportConfig.bleRSSIHighTimeoutThreshold)
        }
        dynamicRSSIThreshold = threshold
    }
}
