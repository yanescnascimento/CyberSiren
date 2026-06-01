import Foundation
import Combine
import BitLogger
import Tor

protocol GeohashPresenceTimerProtocol: AnyObject {
    var isValid: Bool { get }
    func invalidate()
}

private final class GeohashPresenceTimerAdapter: GeohashPresenceTimerProtocol {
    private let base: Timer

    init(base: Timer) {
        self.base = base
    }

    var isValid: Bool { base.isValid }

    func invalidate() {
        base.invalidate()
    }
}

@MainActor
final class GeohashPresenceService: ObservableObject {
    static let shared = GeohashPresenceService()

    private var subscriptions = Set<AnyCancellable>()
    private var heartbeatTimer: GeohashPresenceTimerProtocol?
    private let availableChannelsProvider: () -> [GeohashChannel]
    private let locationChanges: AnyPublisher<[GeohashChannel], Never>
    private let torReadyPublisher: AnyPublisher<Void, Never>
    private let torIsReady: () -> Bool
    private let torIsForeground: () -> Bool
    private let deriveIdentity: (String) throws -> NostrIdentity
    private let relayLookup: (String, Int) -> [String]
    private let relaySender: (NostrEvent, [String]) -> Void
    private let sleeper: (UInt64) async -> Void
    private let scheduleTimer: (TimeInterval, @escaping () -> Void) -> GeohashPresenceTimerProtocol

    private let loopMinInterval: TimeInterval
    private let loopMaxInterval: TimeInterval

    private let burstMinDelay: TimeInterval
    private let burstMaxDelay: TimeInterval

    private let allowedPrecisions: Set<Int> = [
        GeohashChannelLevel.region.precision,
        GeohashChannelLevel.province.precision,
        GeohashChannelLevel.city.precision
    ]

    private init() {
        let idBridge = NostrIdentityBridge()
        self.availableChannelsProvider = { LocationStateManager.shared.availableChannels }
        self.locationChanges = LocationStateManager.shared.$availableChannels.eraseToAnyPublisher()
        self.torReadyPublisher = NotificationCenter.default.publisher(for: .TorDidBecomeReady)
            .map { _ in () }
            .eraseToAnyPublisher()
        self.torIsReady = { TorManager.shared.isReady }
        self.torIsForeground = { TorManager.shared.isForeground() }
        self.deriveIdentity = { try idBridge.deriveIdentity(forGeohash: $0) }
        self.relayLookup = { geohash, count in
            GeoRelayDirectory.shared.closestRelays(toGeohash: geohash, count: count)
        }
        self.relaySender = { event, relays in
            NostrRelayManager.shared.sendEvent(event, to: relays)
        }
        self.sleeper = { nanoseconds in
            try? await Task.sleep(nanoseconds: nanoseconds)
        }
        self.scheduleTimer = { interval, action in
            GeohashPresenceTimerAdapter(
                base: Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { _ in
                    action()
                }
            )
        }
        self.loopMinInterval = 40.0
        self.loopMaxInterval = 80.0
        self.burstMinDelay = 2.0
        self.burstMaxDelay = 5.0
        setupObservers()
    }

    internal init(
        availableChannelsProvider: @escaping () -> [GeohashChannel],
        locationChanges: AnyPublisher<[GeohashChannel], Never>,
        torReadyPublisher: AnyPublisher<Void, Never>,
        torIsReady: @escaping () -> Bool,
        torIsForeground: @escaping () -> Bool,
        deriveIdentity: @escaping (String) throws -> NostrIdentity,
        relayLookup: @escaping (String, Int) -> [String],
        relaySender: @escaping (NostrEvent, [String]) -> Void,
        sleeper: @escaping (UInt64) async -> Void = { nanoseconds in try? await Task.sleep(nanoseconds: nanoseconds) },
        scheduleTimer: @escaping (TimeInterval, @escaping () -> Void) -> GeohashPresenceTimerProtocol = { interval, action in
            GeohashPresenceTimerAdapter(
                base: Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { _ in
                    action()
                }
            )
        },
        loopMinInterval: TimeInterval = 40.0,
        loopMaxInterval: TimeInterval = 80.0,
        burstMinDelay: TimeInterval = 2.0,
        burstMaxDelay: TimeInterval = 5.0
    ) {
        self.availableChannelsProvider = availableChannelsProvider
        self.locationChanges = locationChanges
        self.torReadyPublisher = torReadyPublisher
        self.torIsReady = torIsReady
        self.torIsForeground = torIsForeground
        self.deriveIdentity = deriveIdentity
        self.relayLookup = relayLookup
        self.relaySender = relaySender
        self.sleeper = sleeper
        self.scheduleTimer = scheduleTimer
        self.loopMinInterval = loopMinInterval
        self.loopMaxInterval = loopMaxInterval
        self.burstMinDelay = burstMinDelay
        self.burstMaxDelay = burstMaxDelay
        setupObservers()
    }

    func start() {
        SecureLogger.info("Presence: service starting...", category: .session)
        scheduleNextHeartbeat()
    }

    private func setupObservers() {

        locationChanges
            .dropFirst()
            .sink { [weak self] _ in
                self?.handleLocationChange()
            }
            .store(in: &subscriptions)

        torReadyPublisher
            .sink { [weak self] _ in
                self?.handleConnectivityChange()
            }
            .store(in: &subscriptions)
    }

    func handleLocationChange() {

        SecureLogger.debug("Presence: location changed, scheduling update", category: .session)
        heartbeatTimer?.invalidate()

        heartbeatTimer = scheduleTimer(5.0) { [weak self] in
            Task { @MainActor [weak self] in
                self?.performHeartbeat()
            }
        }
    }

    func handleConnectivityChange() {
        SecureLogger.debug("Presence: connectivity restored, triggering heartbeat", category: .session)

        if heartbeatTimer == nil || !heartbeatTimer!.isValid {
            scheduleNextHeartbeat()
        }
    }

    func scheduleNextHeartbeat() {
        heartbeatTimer?.invalidate()
        let interval = TimeInterval.random(in: loopMinInterval...loopMaxInterval)
        heartbeatTimer = scheduleTimer(interval) { [weak self] in
            Task { @MainActor [weak self] in
                self?.performHeartbeat()
            }
        }
    }

    func performHeartbeat() {

        defer { scheduleNextHeartbeat() }

        guard torIsReady() else {
            SecureLogger.debug("Presence: skipping heartbeat (Tor not ready)", category: .session)
            return
        }

        if !torIsForeground() {
            return
        }

        let channels = availableChannelsProvider()
        guard !channels.isEmpty else { return }

        for channel in channels {

            if !self.allowedPrecisions.contains(channel.geohash.count) {
                continue
            }

            Task { @MainActor in

                let delay = TimeInterval.random(in: self.burstMinDelay...self.burstMaxDelay)
                let nanoseconds = UInt64(delay * 1_000_000_000)
                await self.sleeper(nanoseconds)

                self.broadcastPresence(for: channel.geohash)
            }
        }
    }

    func broadcastPresence(for geohash: String) {
        do {
            guard let identity = try? deriveIdentity(geohash) else {
                return
            }

            let event = try NostrProtocol.createGeohashPresenceEvent(
                geohash: geohash,
                senderIdentity: identity
            )

            let targetRelays = relayLookup(geohash, TransportConfig.nostrGeoRelayCount)

            if !targetRelays.isEmpty {
                relaySender(event, targetRelays)
                SecureLogger.debug("Presence: sent heartbeat for \(geohash) (pub=\(identity.publicKeyHex.prefix(6))...)", category: .session)
            }
        } catch {
            SecureLogger.error("Presence: failed to create event for \(geohash): \(error)", category: .session)
        }
    }
}
