import struct Foundation.Data
import struct Foundation.CharacterSet

public struct PeerID: Equatable, Hashable, Sendable {
    enum Constants {

        static let nostrConvKeyPrefixLength = 16

        static let nostrShortKeyDisplayLength = 8

        fileprivate static let maxIDLength = 64

        fileprivate static let hexIDLength = 16
    }

    public enum Prefix: String, CaseIterable, Sendable {

        case empty = ""

        case mesh = "mesh:"

        case name = "name:"

        case noise = "noise:"

        case geoDM = "nostr_"

        case geoChat = "nostr:"
    }

    public let prefix: Prefix

    public let bare: String

    public var id: String { prefix.rawValue + bare }

    private init(prefix: Prefix, bare: any StringProtocol) {
        self.prefix = prefix
        self.bare = String(bare).lowercased()
    }
}

public extension PeerID {

    init(nostr_ pubKey: String) {
        self.init(prefix: .geoDM, bare: pubKey.prefix(Constants.nostrConvKeyPrefixLength))
    }

    init(nostr pubKey: String) {
        self.init(prefix: .geoChat, bare: pubKey.prefix(Constants.nostrShortKeyDisplayLength))
    }

    init(str: any StringProtocol) {
        if let prefix = Prefix.allCases.first(where: { $0 != .empty && str.hasPrefix($0.rawValue) }) {
            self.init(prefix: prefix, bare: String(str).dropFirst(prefix.rawValue.count))
        } else {
            self.init(prefix: .empty, bare: str)
        }
    }

    init?(str: (any StringProtocol)?) {
        guard let str else { return nil }
        self.init(str: str)
    }

    init?(data: Data) {
        self.init(str: String(data: data, encoding: .utf8))
    }

    init(hexData: Data) {
        self.init(str: hexData.hexEncodedString())
    }

    init?(hexData: Data?) {
        guard let hexData else { return nil }
        self.init(hexData: hexData)
    }
}

public extension PeerID {

    init(publicKey: Data) {
        self.init(str: publicKey.sha256Fingerprint().prefix(16))
    }

    func toShort() -> PeerID {
        if let noiseKey {
            return PeerID(publicKey: noiseKey)
        }
        return self
    }
}

extension PeerID: Codable {
    public init(from decoder: any Decoder) throws {
        self.init(str: try decoder.singleValueContainer().decode(String.self))
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(id)
    }
}

public extension PeerID {
    var isEmpty: Bool {
        id.isEmpty
    }

    var isGeoChat: Bool {
        prefix == .geoChat
    }

    var isGeoDM: Bool {
        prefix == .geoDM
    }

    func toPercentEncoded() -> String {
        id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
    }
}

public extension PeerID {
    var routingData: Data? {
        if let direct = Data(hexString: id), direct.count == 8 { return direct }
        if let bareData = Data(hexString: bare), bareData.count == 8 { return bareData }
        let short = toShort()
        return Data(hexString: short.id)
    }

    init?(routingData: Data) {
        guard routingData.count == 8 else { return nil }
        self.init(hexData: routingData)
    }
}

public extension PeerID {

    var isValid: Bool {
        if prefix != .empty {
            return PeerID(str: bare).isValid
        }

        if isShort || isNoiseKeyHex {
            return true
        }

        if id.count == Constants.hexIDLength || id.count == Constants.maxIDLength {
            return false
        }

        let validCharset = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        return !id.isEmpty &&
                id.count < Constants.maxIDLength &&
                id.rangeOfCharacter(from: validCharset.inverted) == nil
    }

    var isHex: Bool {
        bare.allSatisfy { $0.isHexDigit }
    }

    var isShort: Bool {
        bare.count == Constants.hexIDLength && isHex
    }

    var isNoiseKeyHex: Bool {
        noiseKey != nil
    }

    var noiseKey: Data? {
        guard bare.count == Constants.maxIDLength else { return nil }
        return Data(hexString: bare)
    }
}

extension PeerID: Comparable {
    public static func < (lhs: PeerID, rhs: PeerID) -> Bool {
        lhs.id < rhs.id
    }
}

extension PeerID: CustomStringConvertible {

    public var description: String {
        id
    }
}
