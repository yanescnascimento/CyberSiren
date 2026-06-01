import Testing
import Foundation
@testable import bitchat

@Suite("LRU Deduplication Cache")
@MainActor
struct LRUDeduplicationCacheTests {

    @Test func emptyCache_containsReturnsFalse() {
        let cache = LRUDeduplicationCache<Int>(capacity: 10)
        #expect(!cache.contains("key"))
        #expect(cache.value(for: "key") == nil)
        #expect(cache.count == 0)
    }

    @Test func record_addsEntry() {
        let cache = LRUDeduplicationCache<Int>(capacity: 10)
        cache.record("key1", value: 42)

        #expect(cache.contains("key1"))
        #expect(cache.value(for: "key1") == 42)
        #expect(cache.count == 1)
    }

    @Test func record_updatesExistingEntry() {
        let cache = LRUDeduplicationCache<Int>(capacity: 10)
        cache.record("key1", value: 42)
        cache.record("key1", value: 100)

        #expect(cache.value(for: "key1") == 100)
        #expect(cache.count == 1)
    }

    @Test func record_multipleEntries() {
        let cache = LRUDeduplicationCache<String>(capacity: 10)
        cache.record("a", value: "alpha")
        cache.record("b", value: "beta")
        cache.record("c", value: "gamma")

        #expect(cache.count == 3)
        #expect(cache.value(for: "a") == "alpha")
        #expect(cache.value(for: "b") == "beta")
        #expect(cache.value(for: "c") == "gamma")
    }

    @Test func remove_removesEntry() {
        let cache = LRUDeduplicationCache<Int>(capacity: 10)
        cache.record("key1", value: 42)
        cache.record("key2", value: 100)

        cache.remove("key1")

        #expect(!cache.contains("key1"))
        #expect(cache.contains("key2"))
    }

    @Test func clear_removesAllEntries() {
        let cache = LRUDeduplicationCache<Int>(capacity: 10)
        cache.record("a", value: 1)
        cache.record("b", value: 2)
        cache.record("c", value: 3)

        cache.clear()

        #expect(cache.count == 0)
        #expect(!cache.contains("a"))
        #expect(!cache.contains("b"))
        #expect(!cache.contains("c"))
    }

    @Test func eviction_removesOldestWhenOverCapacity() {
        let cache = LRUDeduplicationCache<Int>(capacity: 3)
        cache.record("a", value: 1)
        cache.record("b", value: 2)
        cache.record("c", value: 3)
        cache.record("d", value: 4)

        #expect(cache.count == 3)
        #expect(!cache.contains("a"))
        #expect(cache.contains("b"))
        #expect(cache.contains("c"))
        #expect(cache.contains("d"))
    }

    @Test func eviction_maintainsCapacity() {
        let cache = LRUDeduplicationCache<Int>(capacity: 2)

        for i in 0..<100 {
            cache.record("key\(i)", value: i)
        }

        #expect(cache.count == 2)

        #expect(cache.contains("key99"))
        #expect(cache.contains("key98"))

        #expect(!cache.contains("key0"))
        #expect(!cache.contains("key50"))
    }

    @Test func eviction_capacityOfOne() {
        let cache = LRUDeduplicationCache<Int>(capacity: 1)
        cache.record("a", value: 1)
        cache.record("b", value: 2)

        #expect(cache.count == 1)
        #expect(!cache.contains("a"))
        #expect(cache.contains("b"))
    }

    @Test func eviction_skipsRemovedKeys() {
        let cache = LRUDeduplicationCache<Int>(capacity: 3)
        cache.record("a", value: 1)
        cache.record("b", value: 2)
        cache.record("c", value: 3)

        cache.remove("a")

        cache.record("d", value: 4)

        #expect(!cache.contains("a"))
        #expect(cache.count <= 3)
    }

    @Test func emptyKey_works() {
        let cache = LRUDeduplicationCache<Int>(capacity: 10)
        cache.record("", value: 42)

        #expect(cache.contains(""))
        #expect(cache.value(for: "") == 42)
    }

    @Test func largeCapacity_works() {
        let cache = LRUDeduplicationCache<Int>(capacity: 10000)

        for i in 0..<5000 {
            cache.record("key\(i)", value: i)
        }

        #expect(cache.count == 5000)
        #expect(cache.contains("key0"))
        #expect(cache.contains("key4999"))
    }
}

struct ContentNormalizerTests {

    @Test func normalizedKey_basicContent() {
        let key1 = ContentNormalizer.normalizedKey("Hello World")
        let key2 = ContentNormalizer.normalizedKey("Hello World")
        #expect(key1 == key2)
    }

    @Test func normalizedKey_caseInsensitive() {
        let key1 = ContentNormalizer.normalizedKey("Hello World")
        let key2 = ContentNormalizer.normalizedKey("hello world")
        let key3 = ContentNormalizer.normalizedKey("HELLO WORLD")
        #expect(key1 == key2)
        #expect(key2 == key3)
    }

    @Test func normalizedKey_whitespaceCollapsed() {
        let key1 = ContentNormalizer.normalizedKey("Hello World")
        let key2 = ContentNormalizer.normalizedKey("Hello    World")
        let key3 = ContentNormalizer.normalizedKey("Hello\t\nWorld")
        #expect(key1 == key2)
        #expect(key2 == key3)
    }

    @Test func normalizedKey_trimmed() {
        let key1 = ContentNormalizer.normalizedKey("Hello")
        let key2 = ContentNormalizer.normalizedKey("  Hello  ")
        let key3 = ContentNormalizer.normalizedKey("\nHello\n")
        #expect(key1 == key2)
        #expect(key2 == key3)
    }

    @Test func normalizedKey_urlQueryStripped() {
        let key1 = ContentNormalizer.normalizedKey("Check https://example.com/page")
        let key2 = ContentNormalizer.normalizedKey("Check https://example.com/page?query=value")
        let key3 = ContentNormalizer.normalizedKey("Check https://example.com/page#anchor")
        #expect(key1 == key2)
        #expect(key2 == key3)
    }

    @Test func normalizedKey_httpAndHttpsDistinct() {

        let key1 = ContentNormalizer.normalizedKey("http://example.com/page")
        let key2 = ContentNormalizer.normalizedKey("https://example.com/page")
        #expect(key1 != key2)
    }

    @Test func normalizedKey_differentContent() {
        let key1 = ContentNormalizer.normalizedKey("Hello")
        let key2 = ContentNormalizer.normalizedKey("Goodbye")
        #expect(key1 != key2)
    }

    @Test func normalizedKey_returnsHashFormat() {
        let key = ContentNormalizer.normalizedKey("Test content")
        #expect(key.hasPrefix("h:"))
        #expect(key.count == 18)
    }

    @Test func normalizedKey_emptyContent() {
        let key = ContentNormalizer.normalizedKey("")
        #expect(key.hasPrefix("h:"))
    }

    @Test func normalizedKey_longContentTruncated() {
        let longContent = String(repeating: "a", count: 10000)
        let key1 = ContentNormalizer.normalizedKey(longContent)
        let key2 = ContentNormalizer.normalizedKey(longContent + "extra")

        #expect(key1 == key2)
    }

    @Test func normalizedKey_prefixLengthRespected() {
        let content = "Short"
        let key1 = ContentNormalizer.normalizedKey(content, prefixLength: 3)
        let key2 = ContentNormalizer.normalizedKey(content, prefixLength: 100)

        #expect(key1 != key2)
    }

    @Test func normalizedKey_urlsInMiddleOfContent() {
        let content1 = "Check out https://example.com/path?query=1 for more info"
        let content2 = "Check out https://example.com/path for more info"
        let key1 = ContentNormalizer.normalizedKey(content1)
        let key2 = ContentNormalizer.normalizedKey(content2)
        #expect(key1 == key2)
    }

    @Test func normalizedKey_multipleUrls() {
        let content1 = "Links: https://a.com?x=1 and http://b.com#y"
        let content2 = "Links: https://a.com and http://b.com"
        let key1 = ContentNormalizer.normalizedKey(content1)
        let key2 = ContentNormalizer.normalizedKey(content2)
        #expect(key1 == key2)
    }
}

@Suite("Message Deduplication Service")
@MainActor
struct MessageDeduplicationServiceTests {

    @Test func recordContent_storesTimestamp() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let now = Date()

        service.recordContent("Hello World", timestamp: now)

        let retrieved = service.contentTimestamp(for: "Hello World")
        #expect(retrieved == now)
    }

    @Test func recordContent_updatesTimestamp() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let early = Date(timeIntervalSince1970: 1000)
        let late = Date(timeIntervalSince1970: 2000)

        service.recordContent("Hello World", timestamp: early)
        service.recordContent("Hello World", timestamp: late)

        let retrieved = service.contentTimestamp(for: "Hello World")
        #expect(retrieved == late)
    }

    @Test func contentTimestamp_nilForUnseen() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)

        let timestamp = service.contentTimestamp(for: "Never seen")
        #expect(timestamp == nil)
    }

    @Test func recordContentKey_directKeyAccess() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let now = Date()
        let key = service.normalizedContentKey("Test")

        service.recordContentKey(key, timestamp: now)

        #expect(service.contentTimestamp(forKey: key) == now)
    }

    @Test func normalizedContentKey_consistentWithNormalizer() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let content = "Hello World"

        let serviceKey = service.normalizedContentKey(content)
        let normalizerKey = ContentNormalizer.normalizedKey(content)

        #expect(serviceKey == normalizerKey)
    }

    @Test func recordNostrEvent_marksAsProcessed() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)

        #expect(!service.hasProcessedNostrEvent("event123"))

        service.recordNostrEvent("event123")

        #expect(service.hasProcessedNostrEvent("event123"))
    }

    @Test func hasProcessedNostrEvent_falseForUnseen() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)

        #expect(!service.hasProcessedNostrEvent("never-seen"))
    }

    @Test func nostrEvent_multipleEvents() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)

        service.recordNostrEvent("event1")
        service.recordNostrEvent("event2")
        service.recordNostrEvent("event3")

        #expect(service.hasProcessedNostrEvent("event1"))
        #expect(service.hasProcessedNostrEvent("event2"))
        #expect(service.hasProcessedNostrEvent("event3"))
        #expect(!service.hasProcessedNostrEvent("event4"))
    }

    @Test func recordNostrAck_marksAsProcessed() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let ackKey = MessageDeduplicationService.ackKey(
            messageId: "msg123",
            ackType: "delivered",
            senderPubkey: "pubkey456"
        )

        #expect(!service.hasProcessedNostrAck(ackKey))

        service.recordNostrAck(ackKey)

        #expect(service.hasProcessedNostrAck(ackKey))
    }

    @Test func ackKey_format() {
        let key = MessageDeduplicationService.ackKey(
            messageId: "msg",
            ackType: "read",
            senderPubkey: "pub"
        )
        #expect(key == "msg:read:pub")
    }

    @Test func ackKey_differentComponents() {
        let key1 = MessageDeduplicationService.ackKey(messageId: "a", ackType: "delivered", senderPubkey: "x")
        let key2 = MessageDeduplicationService.ackKey(messageId: "a", ackType: "read", senderPubkey: "x")
        let key3 = MessageDeduplicationService.ackKey(messageId: "b", ackType: "delivered", senderPubkey: "x")

        #expect(key1 != key2)
        #expect(key1 != key3)
    }

    @Test func clearAll_clearsEverything() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let now = Date()

        service.recordContent("Hello", timestamp: now)
        service.recordNostrEvent("event1")
        service.recordNostrAck("ack1")

        service.clearAll()

        #expect(service.contentTimestamp(for: "Hello") == nil)
        #expect(!service.hasProcessedNostrEvent("event1"))
        #expect(!service.hasProcessedNostrAck("ack1"))
    }

    @Test func clearNostrCaches_preservesContent() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let now = Date()

        service.recordContent("Hello", timestamp: now)
        service.recordNostrEvent("event1")
        service.recordNostrAck("ack1")

        service.clearNostrCaches()

        #expect(service.contentTimestamp(for: "Hello") == now)
        #expect(!service.hasProcessedNostrEvent("event1"))
        #expect(!service.hasProcessedNostrAck("ack1"))
    }

    @Test func contentCache_respectsCapacity() {
        let service = MessageDeduplicationService(contentCapacity: 3, nostrEventCapacity: 100)

        service.recordContent("a", timestamp: Date())
        service.recordContent("b", timestamp: Date())
        service.recordContent("c", timestamp: Date())
        service.recordContent("d", timestamp: Date())

        #expect(service.contentTimestamp(for: "a") == nil)
        #expect(service.contentTimestamp(for: "d") != nil)
    }

    @Test func nostrEventCache_respectsCapacity() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 3)

        service.recordNostrEvent("e1")
        service.recordNostrEvent("e2")
        service.recordNostrEvent("e3")
        service.recordNostrEvent("e4")

        #expect(!service.hasProcessedNostrEvent("e1"))
        #expect(service.hasProcessedNostrEvent("e4"))
    }

    @Test func realWorldDeduplication_similarMessages() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let now = Date()

        service.recordContent("Check out https://example.com/page?ref=abc", timestamp: now)

        let timestamp = service.contentTimestamp(for: "Check out https://example.com/page?ref=xyz")
        #expect(timestamp == now)
    }

    @Test func realWorldDeduplication_caseVariations() {
        let service = MessageDeduplicationService(contentCapacity: 100, nostrEventCapacity: 100)
        let now = Date()

        service.recordContent("HELLO WORLD", timestamp: now)

        #expect(service.contentTimestamp(for: "hello world") == now)
        #expect(service.contentTimestamp(for: "Hello World") == now)
    }

    @Test("Concurrent content recording is safe via MainActor")
    func concurrentContentRecording() async {
        let service = MessageDeduplicationService(contentCapacity: 1000, nostrEventCapacity: 1000)
        let iterations = 100

        await withTaskGroup(of: Void.self) { group in
            for i in 0..<iterations {
                group.addTask { @MainActor in
                    service.recordContent("Message \(i)", timestamp: Date())
                }
            }
        }

        #expect(service.contentTimestamp(for: "Message 0") != nil)
        #expect(service.contentTimestamp(for: "Message 99") != nil)
    }

    @Test("Concurrent Nostr event recording is safe via MainActor")
    func concurrentNostrEventRecording() async {
        let service = MessageDeduplicationService(contentCapacity: 1000, nostrEventCapacity: 1000)
        let iterations = 100

        await withTaskGroup(of: Void.self) { group in
            for i in 0..<iterations {
                group.addTask { @MainActor in
                    service.recordNostrEvent("event_\(i)")
                }
            }
        }

        #expect(service.hasProcessedNostrEvent("event_0"))
        #expect(service.hasProcessedNostrEvent("event_99"))
    }

    @Test("Mixed concurrent operations are safe via MainActor")
    func concurrentMixedOperations() async {
        let service = MessageDeduplicationService(contentCapacity: 1000, nostrEventCapacity: 1000)
        let iterations = 50

        await withTaskGroup(of: Void.self) { group in

            for i in 0..<iterations {
                group.addTask { @MainActor in
                    service.recordContent("Content \(i)", timestamp: Date())
                }
            }

            for i in 0..<iterations {
                group.addTask { @MainActor in
                    service.recordNostrEvent("event_\(i)")
                }
            }

            for i in 0..<iterations {
                group.addTask { @MainActor in
                    service.recordNostrAck("ack_\(i)")
                }
            }

            for i in 0..<iterations {
                group.addTask { @MainActor in
                    _ = service.contentTimestamp(for: "Content \(i)")
                    _ = service.hasProcessedNostrEvent("event_\(i)")
                    _ = service.hasProcessedNostrAck("ack_\(i)")
                }
            }
        }

    }
}

@Suite("LRU Cache Thread Safety")
@MainActor
struct LRUCacheThreadSafetyTests {

    @Test("Concurrent cache access is safe via MainActor")
    func concurrentCacheAccess() async {
        let cache = LRUDeduplicationCache<Int>(capacity: 500)
        let iterations = 100

        await withTaskGroup(of: Void.self) { group in

            for i in 0..<iterations {
                group.addTask { @MainActor in
                    cache.record("key_\(i)", value: i)
                }
            }

            for i in 0..<iterations {
                group.addTask { @MainActor in
                    _ = cache.contains("key_\(i)")
                    _ = cache.value(for: "key_\(i)")
                }
            }
        }

        #expect(cache.count <= 500)
    }

    @Test("Cache eviction under concurrent load is safe")
    func cacheEvictionUnderLoad() async {
        let cache = LRUDeduplicationCache<Int>(capacity: 10)
        let iterations = 100

        await withTaskGroup(of: Void.self) { group in
            for i in 0..<iterations {
                group.addTask { @MainActor in
                    cache.record("key_\(i)", value: i)
                }
            }
        }

        #expect(cache.count == 10)
    }
}
