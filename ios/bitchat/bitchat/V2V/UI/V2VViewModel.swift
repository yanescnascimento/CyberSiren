import Foundation
import Combine
import CoreLocation
import UIKit
import AudioToolbox

@MainActor
public final class V2VViewModel: ObservableObject {

    private static let alertCleanupIntervalMs: Int64 = 5_000

    private static let alertReactionThrottleMs: Int64 = 5_000

    @Published public var alertMode: AlertMode = .receiver
    @Published public var isEmergencyActive = false
    @Published public var selectedVehicleType: VehicleType = .ambulance

    @Published public var currentLocation: CLLocation?
    @Published public var currentSpeed: Float = 0
    @Published public var currentHeading: Float = 0

    @Published public var activeAlerts: [ReceivedAlert] = []
    @Published public var connectedPeers: Int = 0
    @Published public var connectedDevices: Int = 0
    @Published public var isServiceRunning = false
    @Published public var errorMessage: String?

    @Published public var mockEnabled = false
    @Published public var silentMode = false

    @Published public var transportLogs: [TransportLogEntry] = []
    @Published public var bleAvgLatency: Int64 = 0
    @Published public var firebaseAvgLatency: Int64 = 0
    @Published public var bleLossPercent: Float = 0
    @Published public var firebaseLossPercent: Float = 0
    @Published public var bleSendCount: Int = 0
    @Published public var bleRecvCount: Int = 0
    @Published public var firebaseSendCount: Int = 0
    @Published public var firebaseRecvCount: Int = 0

    public var sessionLogPath: String? {
        TransportLogRepository.shared.currentSessionLogPath
    }

    private let service: V2VEmergencyService
    private var cancellables = Set<AnyCancellable>()
    private var cleanupTimer: Timer?

    private var lastReactionAtBySender: [String: Int64] = [:]
    private var lastUrgencyBySender: [String: UrgencyLevel] = [:]

    private let prefs = UserDefaults.standard

    public init(meshBroadcaster: V2VMeshBroadcaster, firebaseTransport: FirebaseTransport = .shared) {
        self.service = V2VEmergencyService(meshBroadcaster: meshBroadcaster, firebaseTransport: firebaseTransport)
        self.service.delegate = self
        self.service.onLocationUpdate = { [weak self] location in
            Task { @MainActor in self?.applyLocation(location) }
        }

        self.mockEnabled = prefs.bool(forKey: "v2v.mock_enabled")
        self.silentMode = prefs.bool(forKey: "v2v.silent_mode")

        bindTransportLogPublishers()
        startAlertCleanup()
        TransportLogRepository.shared.startSessionLog()
        isServiceRunning = true
    }

    deinit {
        cleanupTimer?.invalidate()
    }

    public func setMode(_ mode: AlertMode) {
        if alertMode == .sender && mode == .receiver && isEmergencyActive {
            stopEmergencyBroadcast()
        }
        alertMode = mode
    }

    public func toggleMode() {
        setMode(alertMode == .sender ? .receiver : .sender)
    }

    public func selectVehicleType(_ type: VehicleType) {
        selectedVehicleType = type
        service.setVehicleType(type)
    }

    public func startEmergencyBroadcast() {
        guard alertMode == .sender else {
            errorMessage = "Switch to SENDER mode first"
            return
        }
        service.startEmergencyBroadcast(vehicleType: selectedVehicleType)
        triggerHaptic(.broadcastStart)
    }

    public func stopEmergencyBroadcast() {
        service.stopEmergencyBroadcast()
        triggerHaptic(.broadcastStop)
    }

    public func toggleEmergencyBroadcast() {
        if isEmergencyActive { stopEmergencyBroadcast() } else { startEmergencyBroadcast() }
    }

    public func processIncomingPayload(_ payload: Data, fromPeerId: String, sentAtMs: Int64? = nil, transport: TransportType = .ble) {
        service.processIncomingPayload(payload, fromPeerId: fromPeerId, sentAtMs: sentAtMs, transport: transport)
    }

    public func updateConnectedPeers(count: Int) { connectedPeers = count }

    public func clearError() { errorMessage = nil }
    public func clearTransportLogs() { TransportLogRepository.shared.clearLogs() }

    public func setSilentMode(_ enabled: Bool) {
        silentMode = enabled
        prefs.set(enabled, forKey: "v2v.silent_mode")
        if enabled {
            V2VCarNotifier.shared.cancelAll()
        }
    }

    public func setMockEnabled(_ enabled: Bool) {
        mockEnabled = enabled
        prefs.set(enabled, forKey: "v2v.mock_enabled")

    }

    private func bindTransportLogPublishers() {
        let repo = TransportLogRepository.shared
        repo.$logs.receive(on: DispatchQueue.main).assign(to: &$transportLogs)
        repo.$bleAvgLatency.receive(on: DispatchQueue.main).assign(to: &$bleAvgLatency)
        repo.$firebaseAvgLatency.receive(on: DispatchQueue.main).assign(to: &$firebaseAvgLatency)
        repo.$bleLossPercent.receive(on: DispatchQueue.main).assign(to: &$bleLossPercent)
        repo.$firebaseLossPercent.receive(on: DispatchQueue.main).assign(to: &$firebaseLossPercent)
        repo.$bleSendCount.receive(on: DispatchQueue.main).assign(to: &$bleSendCount)
        repo.$bleRecvCount.receive(on: DispatchQueue.main).assign(to: &$bleRecvCount)
        repo.$firebaseSendCount.receive(on: DispatchQueue.main).assign(to: &$firebaseSendCount)
        repo.$firebaseRecvCount.receive(on: DispatchQueue.main).assign(to: &$firebaseRecvCount)
    }

    private func applyLocation(_ location: CLLocation) {
        guard !mockEnabled else { return }
        currentLocation = location
        currentSpeed = Float(max(location.speed, 0))
        if location.course >= 0 { currentHeading = Float(location.course) }
    }

    private func startAlertCleanup() {
        cleanupTimer?.invalidate()
        cleanupTimer = Timer.scheduledTimer(
            withTimeInterval: Double(Self.alertCleanupIntervalMs) / 1000,
            repeats: true
        ) { [weak self] _ in
            Task { @MainActor in self?.handleAlertCleanupTick() }
        }
    }

    private func handleAlertCleanupTick() {
        let before = activeAlerts.count
        activeAlerts = activeAlerts.filter { $0.isValid }
        service.cleanupExpiredAlerts()
        let activeIds = Set(activeAlerts.map { $0.alert.senderPeerId })
        lastReactionAtBySender = lastReactionAtBySender.filter { activeIds.contains($0.key) }
        lastUrgencyBySender = lastUrgencyBySender.filter { activeIds.contains($0.key) }
        if before != activeAlerts.count {
            V2VCarNotifier.shared.syncWithActive(activeAlerts)
        }
    }

    private enum HapticType {
        case criticalAlert, highAlert, mediumAlert, lowAlert, broadcastStart, broadcastStop
    }

    private enum AlertSoundType {
        case critical, high, medium
    }

    private func triggerHaptic(_ type: HapticType) {
        let style: UIImpactFeedbackGenerator.FeedbackStyle
        switch type {
        case .criticalAlert: style = .heavy
        case .highAlert:     style = .medium
        case .mediumAlert:   style = .light
        case .lowAlert:      style = .light
        case .broadcastStart: style = .medium
        case .broadcastStop:  style = .light
        }
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    private func playAlertSound(_ type: AlertSoundType) {

        let soundId: SystemSoundID
        switch type {
        case .critical: soundId = 1304
        case .high:     soundId = 1005
        case .medium:   soundId = 1009
        }
        AudioServicesPlaySystemSound(soundId)
    }
}

extension V2VViewModel: V2VEmergencyDelegate {
    nonisolated public func onEmergencyAlertReceived(_ alert: ReceivedAlert) {
        Task { @MainActor in handleAlertReceived(alert) }
    }

    nonisolated public func onEmergencyBroadcastStarted(vehicleType: VehicleType) {
        Task { @MainActor in self.isEmergencyActive = true }
    }

    nonisolated public func onEmergencyBroadcastStopped() {
        Task { @MainActor in self.isEmergencyActive = false }
    }

    private func handleAlertReceived(_ alert: ReceivedAlert) {
        upsertAlert(alert)
        if silentMode {
            lastUrgencyBySender[alert.alert.senderPeerId] = alert.urgencyLevel
            return
        }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let senderId = alert.alert.senderPeerId
        let lastAt = lastReactionAtBySender[senderId] ?? 0
        let lastUrgency = lastUrgencyBySender[senderId]
        let urgencyEscalated = lastUrgency.map { alert.urgencyLevel.rawValue < $0.rawValue } ?? false
        let firstContact = lastUrgency == nil
        let shouldAlert = firstContact || urgencyEscalated || (now - lastAt) >= Self.alertReactionThrottleMs

        V2VCarNotifier.shared.notifyAlert(alert, alertUser: shouldAlert)

        if shouldAlert {
            switch alert.urgencyLevel {
            case .critical:
                triggerHaptic(.criticalAlert)
                playAlertSound(.critical)
            case .high:
                triggerHaptic(.highAlert)
                playAlertSound(.high)
            case .medium:
                triggerHaptic(.mediumAlert)
                playAlertSound(.medium)
            case .low:
                triggerHaptic(.lowAlert)
            }
            lastReactionAtBySender[senderId] = now
        }
        lastUrgencyBySender[senderId] = alert.urgencyLevel
    }

    private func upsertAlert(_ alert: ReceivedAlert) {
        var current = activeAlerts.filter { $0.isValid && $0.alert.senderPeerId != alert.alert.senderPeerId }
        current.append(alert)
        current.sort { $0.distanceMeters < $1.distanceMeters }
        activeAlerts = current
    }
}
