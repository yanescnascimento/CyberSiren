import struct Foundation.Data
import class Foundation.NSData
private import BitLogger

public struct BinaryProtocol {
    public static let v1HeaderSize = 14
    static let v2HeaderSize = 16
    public static let senderIDSize = 8
    public static let recipientIDSize = 8
    public static let signatureSize = 64

    public struct Offsets {
        static let version = 0
        static let type = 1
        static let ttl = 2
        static let timestamp = 3
        public static let flags = 11
    }

    public static func headerSize(for version: UInt8) -> Int? {
        switch version {
        case 1: return v1HeaderSize
        case 2: return v2HeaderSize
        default: return nil
        }
    }

    private static func lengthFieldSize(for version: UInt8) -> Int {
        return version == 2 ? 4 : 2
    }

    public struct Flags {
        public static let hasRecipient: UInt8 = 0x01
        public static let hasSignature: UInt8 = 0x02
        public static let isCompressed: UInt8 = 0x04
        public static let hasRoute: UInt8 = 0x08
        static let isRSR: UInt8 = 0x10
    }

    static func encode(_ packet: BitchatPacket, padding: Bool = true) -> Data? {
        let version = packet.version
        guard version == 1 || version == 2 else { return nil }

        var payload = packet.payload
        var isCompressed = false
        var originalPayloadSize: Int?
        if CompressionUtil.shouldCompress(payload) {

            let maxRepresentable = version == 2 ? Int(UInt32.max) : Int(UInt16.max)
            if payload.count <= maxRepresentable,
               let compressedPayload = CompressionUtil.compress(payload) {
                originalPayloadSize = payload.count
                payload = compressedPayload
                isCompressed = true
            }
        }

        let lengthFieldBytes = lengthFieldSize(for: version)

        let originalRoute = (version >= 2) ? (packet.route ?? []) : []
        if originalRoute.contains(where: { $0.isEmpty }) { return nil }
        let sanitizedRoute: [Data] = originalRoute.map { hop in
            if hop.count == senderIDSize { return hop }
            if hop.count > senderIDSize { return Data(hop.prefix(senderIDSize)) }
            var padded = hop
            padded.append(Data(repeating: 0, count: senderIDSize - hop.count))
            return padded
        }
        guard sanitizedRoute.count <= 255 else { return nil }

        let hasRoute = !sanitizedRoute.isEmpty
        let routeLength = hasRoute ? 1 + sanitizedRoute.count * senderIDSize : 0
        let originalSizeFieldBytes = isCompressed ? lengthFieldBytes : 0

        let payloadDataSize = payload.count + originalSizeFieldBytes

        if version == 1 && payloadDataSize > Int(UInt16.max) { return nil }
        if version == 2 && payloadDataSize > Int(UInt32.max) { return nil }

        guard let headerSize = headerSize(for: version) else { return nil }
        let estimatedHeader = headerSize + senderIDSize + (packet.recipientID == nil ? 0 : recipientIDSize) + routeLength
        let estimatedPayload = payloadDataSize
        let estimatedSignature = (packet.signature == nil ? 0 : signatureSize)
        var data = Data()
        data.reserveCapacity(estimatedHeader + estimatedPayload + estimatedSignature + 255)

        data.append(version)
        data.append(packet.type)
        data.append(packet.ttl)

        for shift in stride(from: 56, through: 0, by: -8) {
            data.append(UInt8((packet.timestamp >> UInt64(shift)) & 0xFF))
        }

        var flags: UInt8 = 0
        if packet.recipientID != nil { flags |= Flags.hasRecipient }
        if packet.signature != nil { flags |= Flags.hasSignature }
        if isCompressed { flags |= Flags.isCompressed }

        if hasRoute && version >= 2 { flags |= Flags.hasRoute }
        if packet.isRSR { flags |= Flags.isRSR }
        data.append(flags)

        if version == 2 {
            let length = UInt32(payloadDataSize)
            for shift in stride(from: 24, through: 0, by: -8) {
                data.append(UInt8((length >> UInt32(shift)) & 0xFF))
            }
        } else {
            let length = UInt16(payloadDataSize)
            data.append(UInt8((length >> 8) & 0xFF))
            data.append(UInt8(length & 0xFF))
        }

        let senderBytes = packet.senderID.prefix(senderIDSize)
        data.append(senderBytes)
        if senderBytes.count < senderIDSize {
            data.append(Data(repeating: 0, count: senderIDSize - senderBytes.count))
        }

        if let recipientID = packet.recipientID {
            let recipientBytes = recipientID.prefix(recipientIDSize)
            data.append(recipientBytes)
            if recipientBytes.count < recipientIDSize {
                data.append(Data(repeating: 0, count: recipientIDSize - recipientBytes.count))
            }
        }

        if hasRoute {
            data.append(UInt8(sanitizedRoute.count))
            for hop in sanitizedRoute {
                data.append(hop)
            }
        }

        if isCompressed, let originalSize = originalPayloadSize {
            if version == 2 {
                let value = UInt32(originalSize)
                for shift in stride(from: 24, through: 0, by: -8) {
                    data.append(UInt8((value >> UInt32(shift)) & 0xFF))
                }
            } else {
                let value = UInt16(originalSize)
                data.append(UInt8((value >> 8) & 0xFF))
                data.append(UInt8(value & 0xFF))
            }
        }
        data.append(payload)

        if let signature = packet.signature {
            data.append(signature.prefix(signatureSize))
        }

        if padding {
            let optimalSize = MessagePadding.optimalBlockSize(for: data.count)
            return MessagePadding.pad(data, toSize: optimalSize)
        }
        return data
    }

    public static func decode(_ data: Data) -> BitchatPacket? {

        if let pkt = decodeCore(data) { return pkt }

        let unpadded = MessagePadding.unpad(data)
        if unpadded as NSData === data as NSData { return nil }
        return decodeCore(unpadded)
    }

    private static func decodeCore(_ raw: Data) -> BitchatPacket? {
        guard raw.count >= v1HeaderSize + senderIDSize else { return nil }

        return raw.withUnsafeBytes { (buf: UnsafeRawBufferPointer) -> BitchatPacket? in
            guard let base = buf.baseAddress else { return nil }
            var offset = 0
            func require(_ n: Int) -> Bool { offset + n <= buf.count }
            func read8() -> UInt8? {
                guard require(1) else { return nil }
                let value = base.advanced(by: offset).assumingMemoryBound(to: UInt8.self).pointee
                offset += 1
                return value
            }
            func read16() -> UInt16? {
                guard require(2) else { return nil }
                let ptr = base.advanced(by: offset).assumingMemoryBound(to: UInt8.self)
                let value = (UInt16(ptr[0]) << 8) | UInt16(ptr[1])
                offset += 2
                return value
            }
            func read32() -> UInt32? {
                guard require(4) else { return nil }
                let ptr = base.advanced(by: offset).assumingMemoryBound(to: UInt8.self)
                let value = (UInt32(ptr[0]) << 24) | (UInt32(ptr[1]) << 16) | (UInt32(ptr[2]) << 8) | UInt32(ptr[3])
                offset += 4
                return value
            }
            func readData(_ n: Int) -> Data? {
                guard require(n) else { return nil }
                let ptr = base.advanced(by: offset)
                let data = Data(bytes: ptr, count: n)
                offset += n
                return data
            }

            guard let version = read8(), version == 1 || version == 2 else { return nil }
            let lengthFieldBytes = lengthFieldSize(for: version)
            guard let headerSize = headerSize(for: version) else { return nil }
            let minimumRequired = headerSize + senderIDSize
            guard raw.count >= minimumRequired else { return nil }

            guard let type = read8(), let ttl = read8() else { return nil }

            var timestamp: UInt64 = 0
            for _ in 0..<8 {
                guard let byte = read8() else { return nil }
                timestamp = (timestamp << 8) | UInt64(byte)
            }

            guard let flags = read8() else { return nil }
            let hasRecipient = (flags & Flags.hasRecipient) != 0
            let hasSignature = (flags & Flags.hasSignature) != 0
            let isCompressed = (flags & Flags.isCompressed) != 0

            let hasRoute = (version >= 2) && (flags & Flags.hasRoute) != 0
            let isRSR = (flags & Flags.isRSR) != 0

            let payloadLength: Int
            if version == 2 {
                guard let len = read32() else { return nil }
                payloadLength = Int(len)
            } else {
                guard let len = read16() else { return nil }
                payloadLength = Int(len)
            }

            guard payloadLength >= 0 else { return nil }
            guard payloadLength <= FileTransferLimits.maxFramedFileBytes else { return nil }

            guard let senderID = readData(senderIDSize) else { return nil }

            var recipientID: Data? = nil
            if hasRecipient {
                recipientID = readData(recipientIDSize)
                if recipientID == nil { return nil }
            }

            var route: [Data]? = nil
            if hasRoute {
                guard let routeCount = read8() else { return nil }
                if routeCount > 0 {
                    var hops: [Data] = []
                    for _ in 0..<Int(routeCount) {
                        guard let hop = readData(senderIDSize) else { return nil }
                        hops.append(hop)
                    }
                    route = hops
                }
            }

            let payload: Data
            if isCompressed {
                guard payloadLength >= lengthFieldBytes else { return nil }
                let originalSize: Int
                if version == 2 {
                    guard let rawSize = read32() else { return nil }
                    originalSize = Int(rawSize)
                } else {
                    guard let rawSize = read16() else { return nil }
                    originalSize = Int(rawSize)
                }
                guard originalSize >= 0 && originalSize <= FileTransferLimits.maxFramedFileBytes else { return nil }
                let compressedSize = payloadLength - lengthFieldBytes
                guard compressedSize > 0, let compressed = readData(compressedSize) else { return nil }

                let compressionRatio = Double(originalSize) / Double(compressedSize)
                guard compressionRatio <= 50_000.0 else {
                    SecureLogger.warning("Suspicious compression ratio: \(String(format: "%.0f", compressionRatio)):1", category: .security)
                    return nil
                }

                guard let decompressed = CompressionUtil.decompress(compressed, originalSize: originalSize),
                      decompressed.count == originalSize else { return nil }
                payload = decompressed
            } else {
                guard let rawPayload = readData(payloadLength) else { return nil }
                payload = rawPayload
            }

            var signature: Data? = nil
            if hasSignature {
                signature = readData(signatureSize)
                if signature == nil { return nil }
            }

            guard offset <= buf.count else { return nil }

            return BitchatPacket(
                type: type,
                senderID: senderID,
                recipientID: recipientID,
                timestamp: timestamp,
                payload: payload,
                signature: signature,
                ttl: ttl,
                version: version,
                route: route,
                isRSR: isRSR
            )
        }
    }
}
