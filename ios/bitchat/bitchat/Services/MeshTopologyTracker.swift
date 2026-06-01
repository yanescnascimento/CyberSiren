import Foundation

final class MeshTopologyTracker {
    private typealias RoutingID = Data

    private let queue = DispatchQueue(label: "mesh.topology", attributes: .concurrent)
    private let hopSize = 8

    private var claims: [RoutingID: Set<RoutingID>] = [:]

    private var lastSeen: [RoutingID: Date] = [:]

    private static let routeFreshnessThreshold: TimeInterval = 60

    func reset() {
        queue.sync(flags: .barrier) {
            self.claims.removeAll()
            self.lastSeen.removeAll()
        }
    }

    func updateNeighbors(for sourceData: Data?, neighbors: [Data]) {
        guard let source = sanitize(sourceData) else { return }

        let validNeighbors = Set(neighbors.compactMap { sanitize($0) }).subtracting([source])

        queue.sync(flags: .barrier) {
            self.claims[source] = validNeighbors
            self.lastSeen[source] = Date()
        }
    }

    func removePeer(_ data: Data?) {
        guard let peer = sanitize(data) else { return }
        queue.sync(flags: .barrier) {
            self.claims.removeValue(forKey: peer)
            self.lastSeen.removeValue(forKey: peer)
        }
    }

    func prune(olderThan age: TimeInterval) {
        let deadline = Date().addingTimeInterval(-age)
        queue.sync(flags: .barrier) {
            let stale = self.lastSeen.filter { $0.value < deadline }
            for (peer, _) in stale {
                self.claims.removeValue(forKey: peer)
                self.lastSeen.removeValue(forKey: peer)
            }
        }
    }

    func computeRoute(from start: Data?, to goal: Data?, maxHops: Int = 10) -> [Data]? {
        guard let source = sanitize(start), let target = sanitize(goal) else { return nil }
        if source == target { return [] }

        return queue.sync {
            let now = Date()
            let freshnessDeadline = now.addingTimeInterval(-Self.routeFreshnessThreshold)

            var visited: Set<RoutingID> = [source]

            var queuePaths: [[RoutingID]] = [[source]]

            while !queuePaths.isEmpty {
                let path = queuePaths.removeFirst()

                if path.count > maxHops + 1 { continue }

                guard let last = path.last else { continue }

                guard let neighbors = claims[last] else { continue }

                guard let lastSeenTime = lastSeen[last], lastSeenTime > freshnessDeadline else {
                    continue
                }

                for neighbor in neighbors {
                    if visited.contains(neighbor) { continue }

                    guard let neighborClaims = claims[neighbor],
                          neighborClaims.contains(last) else {
                        continue
                    }

                    guard let neighborSeenTime = lastSeen[neighbor], neighborSeenTime > freshnessDeadline else {
                        continue
                    }

                    var nextPath = path
                    nextPath.append(neighbor)

                    if neighbor == target {

                        return Array(nextPath.dropFirst().dropLast())
                    }

                    visited.insert(neighbor)
                    queuePaths.append(nextPath)
                }
            }
            return nil
        }
    }

    private func sanitize(_ data: Data?) -> Data? {
        guard var value = data, !value.isEmpty else { return nil }
        if value.count > hopSize {
            value = Data(value.prefix(hopSize))
        } else if value.count < hopSize {
            value.append(Data(repeating: 0, count: hopSize - value.count))
        }
        return value
    }
}
