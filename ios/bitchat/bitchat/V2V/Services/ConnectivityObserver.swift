import Foundation
import Network
import Combine

public final class ConnectivityObserver {

    public static let shared = ConnectivityObserver()

    public enum ConnectionState: Equatable {
        case available
        case unavailable
        case losing
        case lost

        public var isOnline: Bool { self == .available }
    }

    public enum NetworkKind: String {
        case wifi
        case cellular
        case wired
        case unknown
    }

    @Published public private(set) var connectionState: ConnectionState = .unavailable
    @Published public private(set) var networkKind: NetworkKind = .unknown

    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "v2v.connectivity")

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let state: ConnectionState = (path.status == .satisfied) ? .available : .unavailable
            let kind: NetworkKind
            if path.usesInterfaceType(.wifi) { kind = .wifi }
            else if path.usesInterfaceType(.cellular) { kind = .cellular }
            else if path.usesInterfaceType(.wiredEthernet) { kind = .wired }
            else { kind = .unknown }

            DispatchQueue.main.async {
                self.connectionState = state
                self.networkKind = kind
            }
        }
        monitor.start(queue: monitorQueue)
    }

    deinit {
        monitor.cancel()
    }

    public var isOnline: Bool { connectionState.isOnline }

    public var hasFastConnection: Bool { networkKind == .wifi || networkKind == .wired }
}
