import Foundation

extension String {

    func sanitized() -> String {
        let key = self as NSString

        if let cached = Self.queue.sync(execute: { Self.cache.object(forKey: key) }) {
            return cached as String
        }

        var sanitized = self

        let fingerprintPattern = #/[a-fA-F0-9]{64}/#
        sanitized = sanitized.replacing(fingerprintPattern) { match in
            let fingerprint = String(match.output)
            return String(fingerprint.prefix(8)) + "..."
        }

        let base64Pattern = #/[A-Za-z0-9+/]{40,}={0,2}/#
        sanitized = sanitized.replacing(base64Pattern) { _ in
            "<base64-data>"
        }

        let passwordPattern = #/password["\s:=]+["']?[^"'\s]+["']?/#
        sanitized = sanitized.replacing(passwordPattern) { _ in
            "password: <redacted>"
        }

        let peerIDPattern = #/peerID: ([a-zA-Z0-9]{8})[a-zA-Z0-9]+/#
        sanitized = sanitized.replacing(peerIDPattern) { match in
            "peerID: \(match.1)..."
        }

        Self.queue.sync {
            Self.cache.setObject(sanitized as NSString, forKey: key)
        }

        return sanitized
    }
}

private extension String {
    static let queue = DispatchQueue(label: "com.cybersiren.ios.securelogger.cache", attributes: .concurrent)

    static let cache: NSCache<NSString, NSString> = {
        let cache = NSCache<NSString, NSString>()
        cache.countLimit = 100
        return cache
    }()
}
