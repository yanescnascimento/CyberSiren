import Foundation
#if os(macOS)
import CFNetwork
#endif

public final class TorURLSession {
    public static let shared = TorURLSession()

    private var defaultSession: URLSession = TorURLSession.makeDefaultSession()

    private var torSession: URLSession = TorURLSession.makeTorSession()
    private var useTorProxy: Bool = true

    public var session: URLSession {
        useTorProxy ? torSession : defaultSession
    }

    public func rebuild() {
        defaultSession = TorURLSession.makeDefaultSession()
        torSession = TorURLSession.makeTorSession()
    }

    public func setProxyMode(useTor: Bool) {
        guard useTorProxy != useTor else { return }
        useTorProxy = useTor
        rebuild()
    }

    private static func makeTorSession() -> URLSession {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.waitsForConnectivity = true

        let host = "127.0.0.1"
        let port = 39050
        #if os(macOS)
        cfg.connectionProxyDictionary = [
            kCFNetworkProxiesSOCKSEnable as String: 1,
            kCFNetworkProxiesSOCKSProxy as String: host,
            kCFNetworkProxiesSOCKSPort as String: port
        ]
        #else

        cfg.connectionProxyDictionary = [
            "SOCKSEnable": 1,
            "SOCKSProxy": host,
            "SOCKSPort": port
        ]
        #endif
        return URLSession(configuration: cfg)
    }

    private static func makeDefaultSession() -> URLSession {
        let cfg = URLSessionConfiguration.default
        cfg.waitsForConnectivity = true
        return URLSession(configuration: cfg)
    }
}
