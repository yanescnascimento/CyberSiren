import Foundation

#if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
import FirebaseDatabase
import FirebaseAuth
#endif

public final class FirebaseTransport: MessageTransport {

    public static let shared = FirebaseTransport()

    private init() {}

    public let channel: TransportChannel = .firebaseCloud

    public var isAvailable: Bool {
        #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
        return isAuthenticated
        #else
        return false
        #endif
    }

    public var onIncoming: ((IncomingPacket) -> Void)?

    private static let defaultTtlSeconds: Int = 300

    private static let freshnessWindowMs: Int64 = 60_000

    private var subscribedGeohashes = Set<String>()
    private var isRunning = false
    private var isAuthenticated = false

    #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
    private lazy var database: Database = Database.database()
    private lazy var auth: Auth = Auth.auth()

    private var activeListeners: [String: DatabaseHandle] = [:]
    #endif

    public func start() async {
        #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
        guard !(isRunning && isAuthenticated) else { return }
        isRunning = true

        if auth.currentUser == nil {
            do {
                _ = try await auth.signInAnonymously()
            } catch {
                isAuthenticated = false
                return
            }
        }
        isAuthenticated = true

        for gh in subscribedGeohashes {
            startListeningToChannel(gh)
        }
        #endif
    }

    public func stop() async {
        #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
        isRunning = false
        for (gh, handle) in activeListeners {
            database.reference(withPath: "relay/\(gh)").removeObserver(withHandle: handle)
        }
        activeListeners.removeAll()
        #endif
    }

    public static func configure(databaseUrl: String) {
        #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)

        _ = databaseUrl
        #endif
    }

    public func send(packet: Data, targetGeohash: String?) async throws {
        #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
        guard isAuthenticated else { return }
        let geohash = targetGeohash ?? "emergency"
        let messageId = UUID().uuidString
        let base64 = packet.base64EncodedString()

        let ref = database.reference(withPath: "relay/\(geohash)/\(messageId)")
        let payload: [String: Any] = [
            "data": base64,
            "ts": Int64(Date().timeIntervalSince1970 * 1000),
            "ttl": Self.defaultTtlSeconds,
            "sender": auth.currentUser?.uid ?? "unknown"
        ]

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            ref.setValue(payload) { error, _ in
                if let error = error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
        #endif
    }

    public func sendEmergencyAlert(_ alert: FirebaseEmergencyAlert) async throws {
        try await send(packet: alert.toBytes(), targetGeohash: "emergency")
    }

    public func subscribe(toGeohash geohash: String) {
        subscribedGeohashes.insert(geohash)
        #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
        if isRunning && isAuthenticated {
            startListeningToChannel(geohash)
        }
        #endif
    }

    public func unsubscribe(fromGeohash geohash: String) {
        subscribedGeohashes.remove(geohash)
        #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
        if let handle = activeListeners[geohash] {
            database.reference(withPath: "relay/\(geohash)").removeObserver(withHandle: handle)
            activeListeners.removeValue(forKey: geohash)
        }
        #endif
    }

    #if canImport(FirebaseDatabase) && canImport(FirebaseAuth)
    private func startListeningToChannel(_ geohash: String) {
        guard activeListeners[geohash] == nil else { return }
        let ref = database.reference(withPath: "relay/\(geohash)")
        let handle = ref.observe(.childAdded) { [weak self] snapshot in
            self?.handleIncoming(snapshot: snapshot, channel: geohash)
        }
        activeListeners[geohash] = handle
    }

    private func handleIncoming(snapshot: DataSnapshot, channel: String) {
        guard let dict = snapshot.value as? [String: Any],
              let base64 = dict["data"] as? String else { return }
        let messageId = snapshot.key
        let timestamp = (dict["ts"] as? NSNumber)?.int64Value
            ?? Int64(Date().timeIntervalSince1970 * 1000)
        let sender = dict["sender"] as? String ?? "unknown"

        if sender == auth.currentUser?.uid { return }

        let ageMs = Int64(Date().timeIntervalSince1970 * 1000) - timestamp
        if ageMs > Self.freshnessWindowMs { return }

        guard let data = Data(base64Encoded: base64) else { return }
        let packet = IncomingPacket(
            data: data,
            channel: .firebaseCloud,
            receivedAtMs: timestamp,
            metadata: [
                "source": channel,
                "messageId": messageId,
                "sender": sender
            ]
        )
        onIncoming?(packet)
    }
    #endif
}
