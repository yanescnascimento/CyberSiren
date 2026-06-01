import struct Foundation.Data
import struct Foundation.Date

public struct BitchatPacket: Codable {
    let version: UInt8
    public let type: UInt8
    public let senderID: Data
    public let recipientID: Data?
    public let timestamp: UInt64
    public let payload: Data
    public var signature: Data?
    public var ttl: UInt8
    public var route: [Data]?
    public var isRSR: Bool

    public init(type: UInt8, senderID: Data, recipientID: Data?, timestamp: UInt64, payload: Data, signature: Data?, ttl: UInt8, version: UInt8 = 1, route: [Data]? = nil, isRSR: Bool = false) {
        self.version = version
        self.type = type
        self.senderID = senderID
        self.recipientID = recipientID
        self.timestamp = timestamp
        self.payload = payload
        self.signature = signature
        self.ttl = ttl
        self.route = route
        self.isRSR = isRSR
    }

    init(type: UInt8, ttl: UInt8, senderID: PeerID, payload: Data, isRSR: Bool = false) {
        self.version = 1
        self.type = type

        var senderData = Data()
        var tempID = senderID.id
        while tempID.count >= 2 {
            let hexByte = String(tempID.prefix(2))
            if let byte = UInt8(hexByte, radix: 16) {
                senderData.append(byte)
            }
            tempID = String(tempID.dropFirst(2))
        }
        self.senderID = senderData
        self.recipientID = nil
        self.timestamp = UInt64(Date().timeIntervalSince1970 * 1000)
        self.payload = payload
        self.signature = nil
        self.ttl = ttl
        self.route = nil
        self.isRSR = isRSR
    }

    var data: Data? {
        BinaryProtocol.encode(self)
    }

    public func toBinaryData(padding: Bool = true) -> Data? {
        BinaryProtocol.encode(self, padding: padding)
    }

    public func toBinaryData() -> Data? {
        toBinaryData(padding: true)
    }

    public func toBinaryDataForSigning() -> Data? {

        let unsignedPacket = BitchatPacket(
            type: type,
            senderID: senderID,
            recipientID: recipientID,
            timestamp: timestamp,
            payload: payload,
            signature: nil,
            ttl: 0,
            version: version,
            route: route,
            isRSR: false
        )
        return BinaryProtocol.encode(unsignedPacket)
    }

    public static func from(_ data: Data) -> BitchatPacket? {
        BinaryProtocol.decode(data)
    }
}
