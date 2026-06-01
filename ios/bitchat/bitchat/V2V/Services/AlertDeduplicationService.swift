import Foundation

public final class AlertDeduplicationService {

    private static let expiry: TimeInterval = 5 * 60
    private static let cleanupInterval: TimeInterval = 60

    private var processedAlerts: [String: Date] = [:]
    private let queue = DispatchQueue(label: "v2v.dedup", attributes: .concurrent)
    private var cleanupTimer: DispatchSourceTimer?

    public init() {
        startCleanupTask()
    }

    deinit {
        cleanupTimer?.cancel()
    }

    public func isDuplicate(_ messageId: String) -> Bool {
        var duplicate = false
        queue.sync {
            if let seen = processedAlerts[messageId],
               Date().timeIntervalSince(seen) < AlertDeduplicationService.expiry {
                duplicate = true
            }
        }
        return duplicate
    }

    public func markProcessed(_ messageId: String) {
        queue.async(flags: .barrier) {
            self.processedAlerts[messageId] = Date()
        }
    }

    public func checkAndMark(_ messageId: String) -> Bool {
        var isNew = true
        queue.sync(flags: .barrier) {
            let now = Date()
            if let seen = self.processedAlerts[messageId],
               now.timeIntervalSince(seen) < AlertDeduplicationService.expiry {
                isNew = false
            } else {
                self.processedAlerts[messageId] = now
            }
        }
        return isNew
    }

    public func cleanup() {
        queue.async(flags: .barrier) {
            let now = Date()
            self.processedAlerts = self.processedAlerts.filter {
                now.timeIntervalSince($0.value) <= AlertDeduplicationService.expiry
            }
        }
    }

    public func cacheSize() -> Int {
        var size = 0
        queue.sync { size = processedAlerts.count }
        return size
    }

    public func clear() {
        queue.async(flags: .barrier) {
            self.processedAlerts.removeAll()
        }
    }

    private func startCleanupTask() {
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + AlertDeduplicationService.cleanupInterval,
                       repeating: AlertDeduplicationService.cleanupInterval)
        timer.setEventHandler { [weak self] in self?.cleanup() }
        timer.resume()
        cleanupTimer = timer
    }
}
