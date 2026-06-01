import Foundation

@MainActor
final class LRUDeduplicationCache<Value> {
    private var map: [String: Value] = [:]
    private var order: [String] = []
    private var head: Int = 0
    private let capacity: Int

    init(capacity: Int) {
        precondition(capacity > 0, "LRU cache capacity must be positive")
        self.capacity = capacity
    }

    var count: Int {
        order.count - head
    }

    func contains(_ key: String) -> Bool {
        map[key] != nil
    }

    func value(for key: String) -> Value? {
        map[key]
    }

    func record(_ key: String, value: Value) {
        if map[key] == nil {
            order.append(key)
        }
        map[key] = value
        trimIfNeeded()
    }

    func remove(_ key: String) {
        map.removeValue(forKey: key)

    }

    func clear() {
        map.removeAll()
        order.removeAll()
        head = 0
    }

    private func trimIfNeeded() {
        let activeCount = order.count - head
        guard activeCount > capacity else { return }

        let overflow = activeCount - capacity
        for _ in 0..<overflow {
            guard let victim = popOldest() else { break }
            map.removeValue(forKey: victim)
        }
    }

    private func popOldest() -> String? {

        while head < order.count {
            let key = order[head]
            head += 1

            if head >= 32 && head * 2 >= order.count {
                order.removeFirst(head)
                head = 0
            }

            if map[key] != nil {
                return key
            }
        }
        return nil
    }
}

enum ContentNormalizer {

    private static let simplifyHTTPURL: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: "https?://[^\\s?#]+(?:[?#][^\\s]*)?",
            options: [.caseInsensitive]
        )
    }()

    static func normalizedKey(
        _ content: String,
        prefixLength: Int = TransportConfig.contentKeyPrefixLength
    ) -> String {

        let lowered = content.lowercased()
        let ns = lowered as NSString
        let range = NSRange(location: 0, length: ns.length)

        var simplified = ""
        var last = 0
        for match in simplifyHTTPURL.matches(in: lowered, options: [], range: range) {
            if match.range.location > last {
                simplified += ns.substring(with: NSRange(location: last, length: match.range.location - last))
            }
            let url = ns.substring(with: match.range)
            if let queryIndex = url.firstIndex(where: { $0 == "?" || $0 == "#" }) {
                simplified += String(url[..<queryIndex])
            } else {
                simplified += url
            }
            last = match.range.location + match.range.length
        }
        if last < ns.length {
            simplified += ns.substring(with: NSRange(location: last, length: ns.length - last))
        }

        let trimmed = simplified.trimmed
        let collapsed = trimmed.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)

        let prefix = String(collapsed.prefix(prefixLength))
        let hash = prefix.djb2()
        return String(format: "h:%016llx", hash)
    }
}

@MainActor
final class MessageDeduplicationService {

    private let contentCache: LRUDeduplicationCache<Date>

    private let nostrEventCache: LRUDeduplicationCache<Bool>

    private let nostrAckCache: LRUDeduplicationCache<Bool>

    init(
        contentCapacity: Int = TransportConfig.contentLRUCap,
        nostrEventCapacity: Int = TransportConfig.uiProcessedNostrEventsCap
    ) {
        self.contentCache = LRUDeduplicationCache(capacity: contentCapacity)
        self.nostrEventCache = LRUDeduplicationCache(capacity: nostrEventCapacity)
        self.nostrAckCache = LRUDeduplicationCache(capacity: nostrEventCapacity)
    }

    func recordContent(_ content: String, timestamp: Date) {
        let key = ContentNormalizer.normalizedKey(content)
        contentCache.record(key, value: timestamp)
    }

    func recordContentKey(_ key: String, timestamp: Date) {
        contentCache.record(key, value: timestamp)
    }

    func contentTimestamp(for content: String) -> Date? {
        let key = ContentNormalizer.normalizedKey(content)
        return contentCache.value(for: key)
    }

    func contentTimestamp(forKey key: String) -> Date? {
        contentCache.value(for: key)
    }

    func normalizedContentKey(_ content: String) -> String {
        ContentNormalizer.normalizedKey(content)
    }

    func hasProcessedNostrEvent(_ eventId: String) -> Bool {
        nostrEventCache.contains(eventId)
    }

    func recordNostrEvent(_ eventId: String) {
        nostrEventCache.record(eventId, value: true)
    }

    func hasProcessedNostrAck(_ ackKey: String) -> Bool {
        nostrAckCache.contains(ackKey)
    }

    func recordNostrAck(_ ackKey: String) {
        nostrAckCache.record(ackKey, value: true)
    }

    static func ackKey(messageId: String, ackType: String, senderPubkey: String) -> String {
        "\(messageId):\(ackType):\(senderPubkey)"
    }

    func clearAll() {
        contentCache.clear()
        nostrEventCache.clear()
        nostrAckCache.clear()
    }

    func clearNostrCaches() {
        nostrEventCache.clear()
        nostrAckCache.clear()
    }
}
