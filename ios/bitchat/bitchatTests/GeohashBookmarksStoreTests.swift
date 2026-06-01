import Testing
import Foundation
@testable import bitchat

struct GeohashBookmarksStoreTests {
    private let storeKey = "locationChannel.bookmarks"
    private let storage = UserDefaults(suiteName: UUID().uuidString)!
    private let store: GeohashBookmarksStore

    init() {
        store = GeohashBookmarksStore(storage: storage)
    }

    @Test func toggleAndNormalize() {

        #expect(store.bookmarks.isEmpty)

        store.toggle("#U4PRUY")
        #expect(store.isBookmarked("u4pruy"))
        #expect(store.bookmarks.first == "u4pruy")

        store.toggle("u4pruy")
        #expect(!store.isBookmarked("u4pruy"))
        #expect(store.bookmarks.isEmpty)
    }

    @Test func persistenceWritten() throws {
        store.toggle("ezs42")
        store.toggle("u4pruy")

        let data = try #require(storage.data(forKey: storeKey), "No persisted data found")
        let arr = try JSONDecoder().decode([String].self, from: data)
        #expect(arr.contains("ezs42"))
        #expect(arr.contains("u4pruy"))
    }
}
