import struct Foundation.Data
private import struct CryptoKit.SHA256

public extension Data {

    func sha256Fingerprint() -> String {

        sha256Hash().hexEncodedString()
    }

    func sha256Hash() -> Data {
        Data(sha256Digest)
    }

    func sha256Hex() -> String {
        sha256Digest.map { String(format: "%02x", $0) }.joined()
    }
}

private extension Data {
    var sha256Digest: SHA256.Digest {
        SHA256.hash(data: self)
    }
}
