import Foundation
import Combine

public final class TransportLogRepository {

    public static let shared = TransportLogRepository()
    private init() {}

    private static let maxEntries = 500

    @Published public private(set) var logs: [TransportLogEntry] = []

    @Published public private(set) var bleAvgLatency: Int64 = 0
    @Published public private(set) var firebaseAvgLatency: Int64 = 0
    @Published public private(set) var bleLossPercent: Float = 0
    @Published public private(set) var firebaseLossPercent: Float = 0
    @Published public private(set) var bleSendCount: Int = 0
    @Published public private(set) var bleRecvCount: Int = 0
    @Published public private(set) var firebaseSendCount: Int = 0
    @Published public private(set) var firebaseRecvCount: Int = 0

    private let csvQueue = DispatchQueue(label: "v2v.transport.csv")
    private var fileHandle: FileHandle?
    public private(set) var currentSessionLogPath: String?

    private static let timestampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return f
    }()

    private static let fileSuffixFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd-HHmmss"
        return f
    }()

    @discardableResult
    public func startSessionLog() -> String? {
        var path: String?
        csvQueue.sync {
            closeSessionLogInternal()
            guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
                return
            }
            let dir = docs.appendingPathComponent("transport_logs", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

            let stamp = Self.fileSuffixFormatter.string(from: Date())
            let file = dir.appendingPathComponent("v2v-transport-\(stamp).csv")
            FileManager.default.createFile(atPath: file.path, contents: nil)
            guard let handle = try? FileHandle(forWritingTo: file) else { return }

            let header = "timestamp_iso,timestamp_ms,transport,direction,success,latency_ms,payload_bytes,message_id,details\n"
            try? handle.write(contentsOf: Data(header.utf8))

            self.fileHandle = handle
            self.currentSessionLogPath = file.path
            path = file.path
        }
        return path
    }

    public func closeSessionLog() {
        csvQueue.sync { closeSessionLogInternal() }
    }

    private func closeSessionLogInternal() {
        try? fileHandle?.synchronize()
        try? fileHandle?.close()
        fileHandle = nil
        currentSessionLogPath = nil
    }

    private func appendToSessionLog(_ entry: TransportLogEntry) {
        csvQueue.async {
            guard let handle = self.fileHandle else { return }
            let iso = Self.timestampFormatter.string(from: entry.timestamp)
            let details = entry.details
                .replacingOccurrences(of: ",", with: ";")
                .replacingOccurrences(of: "\n", with: " ")
            let latency = entry.latencyMs.map(String.init) ?? ""
            let ms = Int64(entry.timestamp.timeIntervalSince1970 * 1000)
            let line = "\(iso),\(ms),\(entry.transport.rawValue),\(entry.direction.rawValue),"
                + "\(entry.success),\(latency),\(entry.payloadBytes),"
                + "\(entry.messageId),\(details)\n"
            try? handle.write(contentsOf: Data(line.utf8))

            try? handle.synchronize()
        }
    }

    public func logSend(
        transport: TransportType,
        messageId: String,
        latencyMs: Int64,
        payloadBytes: Int = 0,
        details: String = ""
    ) {
        addEntry(TransportLogEntry(
            transport: transport,
            direction: .send,
            messageId: messageId,
            latencyMs: latencyMs,
            success: true,
            payloadBytes: payloadBytes,
            details: details
        ))
    }

    public func logReceive(
        transport: TransportType,
        messageId: String,
        latencyMs: Int64,
        payloadBytes: Int = 0,
        details: String = ""
    ) {
        addEntry(TransportLogEntry(
            transport: transport,
            direction: .receive,
            messageId: messageId,
            latencyMs: latencyMs,
            success: true,
            payloadBytes: payloadBytes,
            details: details
        ))
    }

    public func logFailure(
        transport: TransportType,
        direction: TransportDirection,
        messageId: String,
        details: String = ""
    ) {
        addEntry(TransportLogEntry(
            transport: transport,
            direction: direction,
            messageId: messageId,
            latencyMs: nil,
            success: false,
            payloadBytes: 0,
            details: details
        ))
    }

    public func clearLogs() {
        DispatchQueue.main.async {
            self.logs = []
            self.recomputeMetrics(self.logs)
        }
    }

    private let mutationQueue = DispatchQueue(label: "v2v.transport.mutate")

    private func addEntry(_ entry: TransportLogEntry) {
        mutationQueue.async {
            var current = self.logs
            current.insert(entry, at: 0)
            if current.count > Self.maxEntries {
                current.removeSubrange(Self.maxEntries..<current.count)
            }
            let snapshot = current
            DispatchQueue.main.async {
                self.logs = snapshot
                self.recomputeMetrics(snapshot)
            }
            self.appendToSessionLog(entry)
        }
    }

    private func recomputeMetrics(_ entries: [TransportLogEntry]) {

        let bleAll = entries.filter { $0.transport == .ble }
        let bleSends = bleAll.filter { $0.direction == .send }
        let bleRecvs = bleAll.filter { $0.direction == .receive }
        bleSendCount = bleSends.count
        bleRecvCount = bleRecvs.count

        let bleLatencies = bleAll.compactMap { ($0.success ? $0.latencyMs : nil) }
        bleAvgLatency = bleLatencies.isEmpty
            ? 0
            : Int64(bleLatencies.reduce(0, +) / Int64(bleLatencies.count))
        bleLossPercent = bleAll.isEmpty
            ? 0
            : Float(bleAll.filter { !$0.success }.count) / Float(bleAll.count) * 100

        let fbAll = entries.filter { $0.transport == .firebase }
        let fbSends = fbAll.filter { $0.direction == .send }
        let fbRecvs = fbAll.filter { $0.direction == .receive }
        firebaseSendCount = fbSends.count
        firebaseRecvCount = fbRecvs.count

        let fbLatencies = fbAll.compactMap { ($0.success ? $0.latencyMs : nil) }
        firebaseAvgLatency = fbLatencies.isEmpty
            ? 0
            : Int64(fbLatencies.reduce(0, +) / Int64(fbLatencies.count))
        firebaseLossPercent = fbAll.isEmpty
            ? 0
            : Float(fbAll.filter { !$0.success }.count) / Float(fbAll.count) * 100
    }
}
