import Foundation
import CommonCrypto

public final class MessageOrchestrator {

    public static let shared = MessageOrchestrator()

    private init() {
        for ch in [TransportChannel.bleMesh, .firebaseCloud, .nostrRelay] {
            channelStats[ch] = ChannelStats()
        }
        startCleanupTimer()
    }

    deinit {
        cleanupTimer?.cancel()
    }

    private static let cacheTtlMs: Int64 = 5 * 60 * 1000
    private static let maxCacheSize = 1000
    private static let cleanupInterval: TimeInterval = 60

    public struct ProcessedMessage {
        public let messageId: String
        public let channel: TransportChannel
        public let processedAtMs: Int64
        public let packetHash: String
    }

    public struct ChannelStats {
        public var firstArrivalCount = 0
        public var duplicateCount = 0
        public var totalLatencyMs: Int64 = 0
        public var minLatencyMs: Int64 = Int64.max
        public var maxLatencyMs: Int64 = 0

        public var averageLatencyMs: Double {
            firstArrivalCount > 0 ? Double(totalLatencyMs) / Double(firstArrivalCount) : 0
        }
    }

    private let queue = DispatchQueue(label: "v2v.orchestrator", attributes: .concurrent)
    private var processedMessages: [String: ProcessedMessage] = [:]
    private var channelStats: [TransportChannel: ChannelStats] = [:]
    private var transports: [MessageTransport] = []
    private var cleanupTimer: DispatchSourceTimer?
    private var isRunning = false

    public var onProcessed: ((IncomingPacket, MessageProcessResult) -> Void)?

    public func registerTransport(_ transport: MessageTransport) {
        queue.async(flags: .barrier) {
            self.transports.append(transport)
        }
    }

    public func start() {
        guard !isRunning else { return }
        isRunning = true

        queue.async(flags: .barrier) {
            for transport in self.transports {
                transport.onIncoming = { [weak self] packet in
                    Task { await self?.processIncomingPacket(packet) }
                }
                Task { await transport.start() }
            }
        }
    }

    public func stop() {
        isRunning = false
        queue.async(flags: .barrier) {
            for transport in self.transports {
                Task { await transport.stop() }
            }
        }
    }

    @discardableResult
    public func processIncomingPacket(_ packet: IncomingPacket) async -> MessageProcessResult {
        let receiveTime = Int64(Date().timeIntervalSince1970 * 1000)

        guard let messageId = extractMessageId(from: packet.data) else {
            return .invalid(messageId: nil, reason: "Missing or invalid message_id")
        }

        var result: MessageProcessResult!
        queue.sync(flags: .barrier) {
            if let existing = processedMessages[messageId] {
                channelStats[packet.channel]?.duplicateCount += 1
                result = .duplicate(
                    messageId: messageId,
                    originalChannel: existing.channel,
                    duplicateChannel: packet.channel
                )
                return
            }
            let hash = sha256(packet.data)
            processedMessages[messageId] = ProcessedMessage(
                messageId: messageId,
                channel: packet.channel,
                processedAtMs: receiveTime,
                packetHash: hash
            )

            let latency = max(0, receiveTime - packet.receivedAtMs)
            var stats = channelStats[packet.channel] ?? ChannelStats()
            stats.firstArrivalCount += 1
            stats.totalLatencyMs += latency
            stats.minLatencyMs = min(stats.minLatencyMs, latency)
            stats.maxLatencyMs = max(stats.maxLatencyMs, latency)
            channelStats[packet.channel] = stats

            if processedMessages.count > Self.maxCacheSize {
                cleanupExpired()
            }
            result = .processed(messageId: messageId, channel: packet.channel, latencyMs: latency)
        }

        onProcessed?(packet, result)
        return result
    }

    public func broadcast(packet: Data, targetGeohash: String? = nil) async {
        let snapshot: [MessageTransport] = await withCheckedContinuation { cont in
            queue.async { cont.resume(returning: self.transports.filter { $0.isAvailable }) }
        }
        await withTaskGroup(of: Void.self) { group in
            for transport in snapshot {
                group.addTask {
                    try? await transport.send(packet: packet, targetGeohash: targetGeohash)
                }
            }
        }
    }

    public func isMessageProcessed(_ messageId: String) -> Bool {
        var processed = false
        queue.sync { processed = processedMessages[messageId] != nil }
        return processed
    }

    public func markAsProcessed(messageId: String, channel: TransportChannel) {
        queue.async(flags: .barrier) {
            if self.processedMessages[messageId] == nil {
                self.processedMessages[messageId] = ProcessedMessage(
                    messageId: messageId,
                    channel: channel,
                    processedAtMs: Int64(Date().timeIntervalSince1970 * 1000),
                    packetHash: ""
                )
            }
        }
    }

    public func getChannelStatistics() -> [TransportChannel: ChannelStats] {
        var snapshot: [TransportChannel: ChannelStats] = [:]
        queue.sync { snapshot = channelStats }
        return snapshot
    }

    public func resetStatistics() {
        queue.async(flags: .barrier) {
            for ch in self.channelStats.keys {
                self.channelStats[ch] = ChannelStats()
            }
        }
    }

    private func startCleanupTimer() {
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + Self.cleanupInterval, repeating: Self.cleanupInterval)
        timer.setEventHandler { [weak self] in
            self?.queue.async(flags: .barrier) { self?.cleanupExpired() }
        }
        timer.resume()
        cleanupTimer = timer
    }

    private func cleanupExpired() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        processedMessages = processedMessages.filter { now - $0.value.processedAtMs <= Self.cacheTtlMs }
    }

    private func extractMessageId(from data: Data) -> String? {

        if let string = String(data: data, encoding: .utf8), string.hasPrefix("{") {
            for key in ["message_id", "id", "messageId"] {
                if let match = regexMatch(in: string, pattern: "\"\(key)\"\\s*:\\s*\"([^\"]+)\"") {
                    return match
                }
            }
        }

        let hex = data.map { String(format: "%02x", $0) }.joined()
        let pattern = "([0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12})"
        guard let raw = regexMatch(in: hex, pattern: pattern, caseInsensitive: true) else {
            return nil
        }
        if raw.contains("-") { return raw }

        return [
            raw.prefix(8),
            raw.dropFirst(8).prefix(4),
            raw.dropFirst(12).prefix(4),
            raw.dropFirst(16).prefix(4),
            raw.dropFirst(20).prefix(12)
        ].map(String.init).joined(separator: "-")
    }

    private func regexMatch(in text: String, pattern: String, caseInsensitive: Bool = false) -> String? {
        let options: NSRegularExpression.Options = caseInsensitive ? [.caseInsensitive] : []
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: range),
              match.numberOfRanges >= 2,
              let r = Range(match.range(at: 1), in: text) else {
            return nil
        }
        return String(text[r])
    }

    private func sha256(_ data: Data) -> String {
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &digest)
        }
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
