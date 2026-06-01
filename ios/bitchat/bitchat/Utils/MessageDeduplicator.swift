import Foundation

final class MessageDeduplicator {
    private struct Entry: Equatable {
        let id: String
        let timestamp: Date
    }

    private var entries: [Entry] = []
    private var head: Int = 0
    private var lookup: [String: Date] = [:]
    private let lock = NSLock()
    private let maxAge: TimeInterval
    private let maxCount: Int

    convenience init() {
        self.init(
            maxAge: TransportConfig.messageDedupMaxAgeSeconds,
            maxCount: TransportConfig.messageDedupMaxCount
        )
    }

    init(maxAge: TimeInterval, maxCount: Int) {
        self.maxAge = maxAge
        self.maxCount = maxCount
    }

    func isDuplicate(_ id: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        let now = Date()
        cleanupOldEntries(before: now.addingTimeInterval(-maxAge))

        if lookup[id] != nil {
            return true
        }

        entries.append(Entry(id: id, timestamp: now))
        lookup[id] = now
        trimIfNeeded()

        return false
    }

    func record(_ id: String, timestamp: Date) {
        lock.lock()
        defer { lock.unlock() }

        if lookup[id] == nil {
            entries.append(Entry(id: id, timestamp: timestamp))
        }
        lookup[id] = timestamp
        trimIfNeeded()
    }

    func markProcessed(_ id: String) {
        lock.lock()
        defer { lock.unlock() }

        if lookup[id] == nil {
            let now = Date()
            entries.append(Entry(id: id, timestamp: now))
            lookup[id] = now
        }
    }

    func contains(_ id: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return lookup[id] != nil
    }

    func timestampFor(_ id: String) -> Date? {
        lock.lock()
        defer { lock.unlock() }
        return lookup[id]
    }

    private func trimIfNeeded() {
        let activeCount = entries.count - head
        guard activeCount > maxCount else { return }

        let targetCount = (maxCount * 3) / 4
        let removeCount = activeCount - targetCount

        for i in head..<(head + removeCount) {
            lookup.removeValue(forKey: entries[i].id)
        }
        head += removeCount

        if head > entries.count / 2 {
            entries.removeFirst(head)
            head = 0
        }
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }

        entries.removeAll()
        head = 0
        lookup.removeAll()
    }

    func cleanup() {
        lock.lock()
        defer { lock.unlock() }

        cleanupOldEntries(before: Date().addingTimeInterval(-maxAge))

        if entries.capacity > maxCount * 2 && entries.count < maxCount {
            entries.reserveCapacity(maxCount)
        }
    }

    private func cleanupOldEntries(before cutoff: Date) {
        while head < entries.count, entries[head].timestamp < cutoff {
            lookup.removeValue(forKey: entries[head].id)
            head += 1
        }

        if head > 0 && head > entries.count / 2 {
            entries.removeFirst(head)
            head = 0
        }
    }
}
