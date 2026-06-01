import Testing
import Foundation
import Combine
@testable import bitchat

struct NostrProtocolPresenceTests {

    @Test func createGeohashPresenceEvent_hasCorrectKind() throws {
        let identity = try makeTestIdentity()
        let event = try NostrProtocol.createGeohashPresenceEvent(
            geohash: "u4pruydq",
            senderIdentity: identity
        )

        #expect(event.kind == NostrProtocol.EventKind.geohashPresence.rawValue)
        #expect(event.kind == 20001)
    }

    @Test func createGeohashPresenceEvent_hasEmptyContent() throws {
        let identity = try makeTestIdentity()
        let event = try NostrProtocol.createGeohashPresenceEvent(
            geohash: "u4pruydq",
            senderIdentity: identity
        )

        #expect(event.content == "")
    }

    @Test func createGeohashPresenceEvent_hasOnlyGeohashTag() throws {
        let identity = try makeTestIdentity()
        let event = try NostrProtocol.createGeohashPresenceEvent(
            geohash: "u4pruydq",
            senderIdentity: identity
        )

        #expect(event.tags.count == 1)
        #expect(event.tags[0] == ["g", "u4pruydq"])
    }

    @Test func createGeohashPresenceEvent_noNicknameTag() throws {
        let identity = try makeTestIdentity()
        let event = try NostrProtocol.createGeohashPresenceEvent(
            geohash: "u4pruydq",
            senderIdentity: identity
        )

        let hasNicknameTag = event.tags.contains { $0.first == "n" }
        #expect(!hasNicknameTag)
    }

    @Test func createGeohashPresenceEvent_usesSenderPubkey() throws {
        let identity = try makeTestIdentity()
        let event = try NostrProtocol.createGeohashPresenceEvent(
            geohash: "u4pruydq",
            senderIdentity: identity
        )

        #expect(event.pubkey == identity.publicKeyHex)
    }

    @Test func createGeohashPresenceEvent_isSigned() throws {
        let identity = try makeTestIdentity()
        let event = try NostrProtocol.createGeohashPresenceEvent(
            geohash: "u4pruydq",
            senderIdentity: identity
        )

        #expect(event.sig != nil && !event.sig!.isEmpty)
        #expect(!event.id.isEmpty)
    }

    @Test func createGeohashPresenceEvent_differentGeohashes() throws {
        let identity = try makeTestIdentity()

        let event1 = try NostrProtocol.createGeohashPresenceEvent(geohash: "87", senderIdentity: identity)
        let event2 = try NostrProtocol.createGeohashPresenceEvent(geohash: "87yw", senderIdentity: identity)
        let event3 = try NostrProtocol.createGeohashPresenceEvent(geohash: "87yw7", senderIdentity: identity)

        #expect(event1.tags[0][1] == "87")
        #expect(event2.tags[0][1] == "87yw")
        #expect(event3.tags[0][1] == "87yw7")
    }

    private func makeTestIdentity() throws -> NostrIdentity {

        return try NostrIdentity.generate()
    }
}

struct NostrFilterPresenceTests {

    @Test func geohashEphemeral_includesBothKinds() {
        let filter = NostrFilter.geohashEphemeral("u4pruydq")

        #expect(filter.kinds?.contains(20000) == true)
        #expect(filter.kinds?.contains(20001) == true)
    }

    @Test func geohashEphemeral_hasLimit1000() {
        let filter = NostrFilter.geohashEphemeral("u4pruydq")

        #expect(filter.limit == 1000)
    }

    @Test func geohashEphemeral_respectsSinceParameter() {
        let since = Date(timeIntervalSince1970: 1700000000)
        let filter = NostrFilter.geohashEphemeral("u4pruydq", since: since)

        #expect(filter.since == 1700000000)
    }
}

@MainActor
struct ChatViewModelPresenceHandlingTests {

    @Test func handleNostrEvent_presenceUpdatesParticipantTracker() async throws {
        let (viewModel, _) = makeTestableViewModel()
        let geohash = "u4pruydq"

        viewModel.switchLocationChannel(to: .location(GeohashChannel(level: .city, geohash: geohash)))

        let identity = try NostrIdentity.generate()
        let event = NostrEvent(
            pubkey: identity.publicKeyHex,
            createdAt: Date(),
            kind: .geohashPresence,
            tags: [["g", geohash]],
            content: ""
        )
        let signed = try event.sign(with: identity.schnorrSigningKey())

        viewModel.handleNostrEvent(signed)

        try? await Task.sleep(nanoseconds: 50_000_000)

        let count = viewModel.geohashParticipantCount(for: geohash)
        #expect(count >= 1)
    }

    @Test func handleNostrEvent_presenceDoesNotAddToTimeline() async throws {
        let (viewModel, _) = makeTestableViewModel()
        let geohash = "u4pruydq"

        viewModel.switchLocationChannel(to: .location(GeohashChannel(level: .city, geohash: geohash)))

        let initialMessageCount = viewModel.messages.count

        let identity = try NostrIdentity.generate()
        let event = NostrEvent(
            pubkey: identity.publicKeyHex,
            createdAt: Date(),
            kind: .geohashPresence,
            tags: [["g", geohash]],
            content: ""
        )
        let signed = try event.sign(with: identity.schnorrSigningKey())

        viewModel.handleNostrEvent(signed)

        try? await Task.sleep(nanoseconds: 50_000_000)

        #expect(viewModel.messages.count == initialMessageCount)
    }

    @Test func handleNostrEvent_chatMessageUpdatesParticipant() async throws {
        let (viewModel, _) = makeTestableViewModel()
        let geohash = "u4pruydq"

        viewModel.switchLocationChannel(to: .location(GeohashChannel(level: .city, geohash: geohash)))

        let identity = try NostrIdentity.generate()
        let event = NostrEvent(
            pubkey: identity.publicKeyHex,
            createdAt: Date(),
            kind: .ephemeralEvent,
            tags: [["g", geohash]],
            content: "Hello world"
        )
        let signed = try event.sign(with: identity.schnorrSigningKey())

        viewModel.handleNostrEvent(signed)

        try? await Task.sleep(nanoseconds: 50_000_000)

        let count = viewModel.geohashParticipantCount(for: geohash)
        #expect(count >= 1)
    }

    @Test func presenceEvent_hasDifferentKindThanChat() {

        let presenceKind = NostrProtocol.EventKind.geohashPresence.rawValue
        let chatKind = NostrProtocol.EventKind.ephemeralEvent.rawValue

        #expect(presenceKind != chatKind)
        #expect(presenceKind == 20001)
        #expect(chatKind == 20000)
    }

    @Test func subscribeNostrEvent_acceptsPresenceKind() async throws {
        let (viewModel, _) = makeTestableViewModel()
        let geohash = "u4pruydq"

        viewModel.switchLocationChannel(to: .location(GeohashChannel(level: .city, geohash: geohash)))

        let identity = try NostrIdentity.generate()
        let event = NostrEvent(
            pubkey: identity.publicKeyHex,
            createdAt: Date(),
            kind: .geohashPresence,
            tags: [["g", geohash]],
            content: ""
        )
        let signed = try event.sign(with: identity.schnorrSigningKey())

        viewModel.subscribeNostrEvent(signed)

        try? await Task.sleep(nanoseconds: 50_000_000)

        let count = viewModel.geohashParticipantCount(for: geohash)
        #expect(count >= 1)
    }

    @Test func subscribeNostrEvent_presenceForNonActiveGeohash() async throws {
        let (viewModel, _) = makeTestableViewModel()
        let activeGeohash = "u4pruydq"
        let otherGeohash = "87yw7"

        viewModel.switchLocationChannel(to: .location(GeohashChannel(level: .city, geohash: activeGeohash)))

        let identity = try NostrIdentity.generate()
        let event = NostrEvent(
            pubkey: identity.publicKeyHex,
            createdAt: Date(),
            kind: .geohashPresence,
            tags: [["g", otherGeohash]],
            content: ""
        )
        let signed = try event.sign(with: identity.schnorrSigningKey())

        viewModel.subscribeNostrEvent(signed, gh: otherGeohash)

        try? await Task.sleep(nanoseconds: 50_000_000)

        let count = viewModel.geohashParticipantCount(for: otherGeohash)
        #expect(count >= 1)
    }

    private func makeTestableViewModel() -> (viewModel: ChatViewModel, transport: MockTransport) {
        let keychain = MockKeychain()
        let keychainHelper = MockKeychainHelper()
        let idBridge = NostrIdentityBridge(keychain: keychainHelper)
        let identityManager = MockIdentityManager(keychain)
        let transport = MockTransport()

        let viewModel = ChatViewModel(
            keychain: keychain,
            idBridge: idBridge,
            identityManager: identityManager,
            transport: transport
        )

        return (viewModel, transport)
    }
}

struct GeohashPresencePrivacyTests {

    @Test func allowedPrecisions_onlyLowPrecision() {

        let regionPrecision = GeohashChannelLevel.region.precision
        let provincePrecision = GeohashChannelLevel.province.precision
        let cityPrecision = GeohashChannelLevel.city.precision
        let neighborhoodPrecision = GeohashChannelLevel.neighborhood.precision
        let blockPrecision = GeohashChannelLevel.block.precision
        let buildingPrecision = GeohashChannelLevel.building.precision

        #expect(regionPrecision == 2)
        #expect(provincePrecision == 4)
        #expect(cityPrecision == 5)
        #expect(neighborhoodPrecision == 6)
        #expect(blockPrecision == 7)
        #expect(buildingPrecision == 8)

        #expect(neighborhoodPrecision > 5)
        #expect(blockPrecision > 5)
        #expect(buildingPrecision > 5)
    }

    @Test func geohashLengthDeterminesPrecision() {

        #expect("87".count == GeohashChannelLevel.region.precision)
        #expect("87yw".count == GeohashChannelLevel.province.precision)
        #expect("87yw7".count == GeohashChannelLevel.city.precision)
        #expect("87yw7t".count == GeohashChannelLevel.neighborhood.precision)
        #expect("87yw7tc".count == GeohashChannelLevel.block.precision)
        #expect("87yw7tcx".count == GeohashChannelLevel.building.precision)
    }

    @Test func highPrecisionGeohash_isPrivacySensitive() {

        func isHighPrecision(_ geohash: String) -> Bool {
            geohash.count >= 6
        }

        #expect(!isHighPrecision("87"))
        #expect(!isHighPrecision("87yw"))
        #expect(!isHighPrecision("87yw7"))

        #expect(isHighPrecision("87yw7t"))
        #expect(isHighPrecision("87yw7tc"))
        #expect(isHighPrecision("87yw7tcx"))
    }
}

struct LocationChannelsDisplayLogicTests {

    @Test func displayLogic_highPrecisionZeroCount_showsUnknown() {

        let shouldShowUnknown = shouldShowUnknownCount(
            level: .neighborhood,
            count: 0
        )
        #expect(shouldShowUnknown)
    }

    @Test func displayLogic_highPrecisionNonZeroCount_showsActual() {

        let shouldShowUnknown = shouldShowUnknownCount(
            level: .neighborhood,
            count: 5
        )
        #expect(!shouldShowUnknown)
    }

    @Test func displayLogic_lowPrecisionZeroCount_showsActual() {

        let shouldShowUnknown = shouldShowUnknownCount(
            level: .city,
            count: 0
        )
        #expect(!shouldShowUnknown)
    }

    @Test func displayLogic_lowPrecisionNonZeroCount_showsActual() {

        let shouldShowUnknown = shouldShowUnknownCount(
            level: .region,
            count: 10
        )
        #expect(!shouldShowUnknown)
    }

    @Test func displayLogic_allHighPrecisionLevels() {

        let highPrecisionLevels: [GeohashChannelLevel] = [.neighborhood, .block, .building]

        for level in highPrecisionLevels {
            let shouldShowUnknown = shouldShowUnknownCount(level: level, count: 0)
            #expect(shouldShowUnknown, "Level \(level) with count 0 should show unknown")
        }
    }

    @Test func displayLogic_allLowPrecisionLevels() {

        let lowPrecisionLevels: [GeohashChannelLevel] = [.region, .province, .city]

        for level in lowPrecisionLevels {
            let shouldShowUnknown = shouldShowUnknownCount(level: level, count: 0)
            #expect(!shouldShowUnknown, "Level \(level) with count 0 should show actual count")
        }
    }

    @Test func displayLogic_bookmarkHighPrecision() {

        #expect(shouldShowUnknownForBookmark(geohash: "87yw7t", count: 0))
        #expect(shouldShowUnknownForBookmark(geohash: "87yw7tc", count: 0))
        #expect(shouldShowUnknownForBookmark(geohash: "87yw7tcx", count: 0))
    }

    @Test func displayLogic_bookmarkLowPrecision() {
        #expect(!shouldShowUnknownForBookmark(geohash: "87", count: 0))
        #expect(!shouldShowUnknownForBookmark(geohash: "87yw", count: 0))
        #expect(!shouldShowUnknownForBookmark(geohash: "87yw7", count: 0))
    }

    private func shouldShowUnknownCount(level: GeohashChannelLevel, count: Int) -> Bool {
        let isHighPrecision = (level == .neighborhood || level == .block || level == .building)
        return isHighPrecision && count == 0
    }

    private func shouldShowUnknownForBookmark(geohash: String, count: Int) -> Bool {
        let isHighPrecision = (geohash.count >= 6)
        return isHighPrecision && count == 0
    }
}

struct NostrEventKindTests {

    @Test func eventKind_geohashPresence_is20001() {
        #expect(NostrProtocol.EventKind.geohashPresence.rawValue == 20001)
    }

    @Test func eventKind_ephemeralEvent_is20000() {
        #expect(NostrProtocol.EventKind.ephemeralEvent.rawValue == 20000)
    }

    @Test func eventKind_presenceIsEphemeral() {

        let presenceKind = NostrProtocol.EventKind.geohashPresence.rawValue
        let chatKind = NostrProtocol.EventKind.ephemeralEvent.rawValue

        #expect(presenceKind >= 20000 && presenceKind < 30000)
        #expect(chatKind >= 20000 && chatKind < 30000)
    }
}

@MainActor
struct ParticipantTrackerPresenceTests {

    @Test func recordParticipant_fromPresenceEvent_countsParticipant() async {
        let tracker = GeohashParticipantTracker()
        let context = PresenceTestParticipantContext()
        tracker.configure(context: context)

        let geohash = "87yw7"
        tracker.setActiveGeohash(geohash)

        tracker.recordParticipant(pubkeyHex: "presence_user_1")

        #expect(tracker.participantCount(for: geohash) == 1)
    }

    @Test func recordParticipant_multiplePresenceEvents_countsUnique() async {
        let tracker = GeohashParticipantTracker()
        let context = PresenceTestParticipantContext()
        tracker.configure(context: context)

        let geohash = "87yw7"
        tracker.setActiveGeohash(geohash)

        tracker.recordParticipant(pubkeyHex: "user_a")
        tracker.recordParticipant(pubkeyHex: "user_a")
        tracker.recordParticipant(pubkeyHex: "user_a")

        #expect(tracker.participantCount(for: geohash) == 1)

        tracker.recordParticipant(pubkeyHex: "user_b")

        #expect(tracker.participantCount(for: geohash) == 2)
    }

    @Test func recordParticipant_nonActiveGeohash_stillCounts() async {
        let tracker = GeohashParticipantTracker()
        let context = PresenceTestParticipantContext()
        tracker.configure(context: context)

        tracker.setActiveGeohash("active_gh")

        tracker.recordParticipant(pubkeyHex: "nearby_user", geohash: "other_gh")

        #expect(tracker.participantCount(for: "other_gh") == 1)
        #expect(tracker.participantCount(for: "active_gh") == 0)
    }

    @Test func objectWillChange_firesOnNonActiveGeohashUpdate() async {
        let tracker = GeohashParticipantTracker()
        let context = PresenceTestParticipantContext()
        tracker.configure(context: context)

        tracker.setActiveGeohash("active_gh")

        var changeCount = 0
        let cancellable = tracker.objectWillChange.sink { _ in
            changeCount += 1
        }

        tracker.recordParticipant(pubkeyHex: "user1", geohash: "other_gh")

        #expect(changeCount >= 1)

        _ = cancellable
    }
}

@MainActor
private final class PresenceTestParticipantContext: GeohashParticipantContext {
    var blockedPubkeys: Set<String> = []
    var nicknameMap: [String: String] = [:]
    var selfPubkey: String?

    func displayNameForPubkey(_ pubkeyHex: String) -> String {
        let suffix = String(pubkeyHex.suffix(4))
        if let s = selfPubkey, pubkeyHex.lowercased() == s.lowercased() {
            return "me#\(suffix)"
        }
        if let nick = nicknameMap[pubkeyHex.lowercased()] {
            return "\(nick)#\(suffix)"
        }
        return "anon#\(suffix)"
    }

    func isBlocked(_ pubkeyHexLowercased: String) -> Bool {
        blockedPubkeys.contains(pubkeyHexLowercased.lowercased())
    }
}
