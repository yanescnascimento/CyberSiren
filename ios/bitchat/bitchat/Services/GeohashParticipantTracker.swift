import Foundation

public struct GeoPerson: Identifiable, Equatable, Sendable {
    public let id: String
    public let displayName: String
    public let lastSeen: Date

    public init(id: String, displayName: String, lastSeen: Date) {
        self.id = id
        self.displayName = displayName
        self.lastSeen = lastSeen
    }
}

@MainActor
public protocol GeohashParticipantContext: AnyObject {

    func displayNameForPubkey(_ pubkeyHex: String) -> String

    func isBlocked(_ pubkeyHexLowercased: String) -> Bool
}

@MainActor
public final class GeohashParticipantTracker: ObservableObject {

    public let activityCutoff: TimeInterval

    private var participants: [String: [String: Date]] = [:]

    @Published public private(set) var visiblePeople: [GeoPerson] = []

    private var activeGeohash: String?

    private weak var context: GeohashParticipantContext?

    private var refreshTimer: Timer?

    public init(activityCutoff: TimeInterval = -300) {
        self.activityCutoff = activityCutoff
    }

    public func configure(context: GeohashParticipantContext) {
        self.context = context
    }

    public func setActiveGeohash(_ geohash: String?) {
        activeGeohash = geohash
        if geohash == nil {
            visiblePeople = []
        } else {
            refresh()
        }
    }

    public func recordParticipant(pubkeyHex: String) {
        guard let gh = activeGeohash else { return }
        recordParticipant(pubkeyHex: pubkeyHex, geohash: gh)
    }

    public func recordParticipant(pubkeyHex: String, geohash: String) {
        let key = pubkeyHex.lowercased()
        var map = participants[geohash] ?? [:]
        map[key] = Date()
        participants[geohash] = map

        objectWillChange.send()

        if activeGeohash == geohash {
            refresh()
        }
    }

    public func removeParticipant(pubkeyHex: String) {
        let key = pubkeyHex.lowercased()
        for (gh, var map) in participants {
            map.removeValue(forKey: key)
            participants[gh] = map
        }
        refresh()
    }

    public func participantCount(for geohash: String) -> Int {
        let cutoff = Date().addingTimeInterval(activityCutoff)
        let map = participants[geohash] ?? [:]
        return map.values.filter { $0 >= cutoff }.count
    }

    public func getVisiblePeople() -> [GeoPerson] {
        guard let gh = activeGeohash, let context = context else { return [] }
        let cutoff = Date().addingTimeInterval(activityCutoff)
        let map = (participants[gh] ?? [:])
            .filter { $0.value >= cutoff }
            .filter { !context.isBlocked($0.key) }

        return map
            .map { (pub, seen) in
                GeoPerson(id: pub, displayName: context.displayNameForPubkey(pub), lastSeen: seen)
            }
            .sorted { $0.lastSeen > $1.lastSeen }
    }

    public func refresh() {
        visiblePeople = getVisiblePeople()
    }

    public func startRefreshTimer(interval: TimeInterval = 30.0) {
        stopRefreshTimer()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.refresh()
            }
        }
    }

    public func stopRefreshTimer() {
        refreshTimer?.invalidate()
        refreshTimer = nil
    }

    public func clear() {
        participants.removeAll()
        visiblePeople = []
    }

    public func clear(geohash: String) {
        participants.removeValue(forKey: geohash)
        if activeGeohash == geohash {
            visiblePeople = []
        }
    }
}
