import Foundation
import BitLogger
import BitFoundation

final class RequestSyncManager {

    private let queue = DispatchQueue(label: "request.sync.manager", attributes: .concurrent)
    private var pendingRequests: [PeerID: TimeInterval] = [:]
    private let responseWindow: TimeInterval
    private let now: () -> TimeInterval

    init(
        responseWindow: TimeInterval = 30.0,
        now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }
    ) {
        self.responseWindow = responseWindow
        self.now = now
    }

    func registerRequest(to peerID: PeerID) {
        let now = self.now()
        queue.async(flags: .barrier) {
            SecureLogger.debug("Registering sync request to \(peerID.id.prefix(8))…", category: .sync)
            self.pendingRequests[peerID] = now
        }
    }

    func isValidResponse(from peerID: PeerID, isRSR: Bool) -> Bool {
        guard isRSR else { return false }

        return queue.sync {
            guard let requestTime = pendingRequests[peerID] else {
                SecureLogger.warning("Received unsolicited RSR packet from \(peerID.id.prefix(8))…", category: .security)
                return false
            }

            let now = self.now()
            if now - requestTime > responseWindow {
                SecureLogger.warning("Received RSR packet from \(peerID.id.prefix(8))… outside of response window", category: .security)

                return false
            }

            return true
        }
    }

    func cleanup() {
        let now = self.now()
        queue.async(flags: .barrier) {
            let originalCount = self.pendingRequests.count
            self.pendingRequests = self.pendingRequests.filter { _, timestamp in
                now - timestamp <= self.responseWindow
            }
            let removed = originalCount - self.pendingRequests.count
            if removed > 0 {
                SecureLogger.debug("Cleaned up \(removed) expired sync requests", category: .sync)
            }
        }
    }

    var debugPendingRequestCount: Int {
        queue.sync { pendingRequests.count }
    }
}
