import struct Foundation.Data
private import Compression

struct CompressionUtil {

    static let compressionThreshold = Constants.compressionThresholdBytes

    static func compress(_ data: Data) -> Data? {

        guard data.count >= compressionThreshold else { return nil }

        let maxCompressedSize = data.count + (data.count / 255) + 16
        let destinationBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: maxCompressedSize)
        defer { destinationBuffer.deallocate() }

        let compressedSize = data.withUnsafeBytes { sourceBuffer in
            guard let sourcePtr = sourceBuffer.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return compression_encode_buffer(
                destinationBuffer, data.count,
                sourcePtr, data.count,
                nil, COMPRESSION_ZLIB
            )
        }

        guard compressedSize > 0 && compressedSize < data.count else { return nil }

        return Data(bytes: destinationBuffer, count: compressedSize)
    }

    static func decompress(_ compressedData: Data, originalSize: Int) -> Data? {
        let destinationBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: originalSize)
        defer { destinationBuffer.deallocate() }

        let decompressedSize = compressedData.withUnsafeBytes { sourceBuffer in
            guard let sourcePtr = sourceBuffer.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return compression_decode_buffer(
                destinationBuffer, originalSize,
                sourcePtr, compressedData.count,
                nil, COMPRESSION_ZLIB
            )
        }

        guard decompressedSize > 0 else { return nil }

        return Data(bytes: destinationBuffer, count: decompressedSize)
    }

    static func shouldCompress(_ data: Data) -> Bool {

        guard data.count >= compressionThreshold else { return false }

        let uniqueByteCount = Set(data).count
        let sampleSize = min(data.count, 256)
        let uniqueByteRatio = Double(uniqueByteCount) / Double(sampleSize)
        return uniqueByteRatio < 0.9
    }
}
