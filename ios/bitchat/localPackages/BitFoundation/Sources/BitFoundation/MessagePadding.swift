import struct Foundation.Data

struct MessagePadding {

    static let blockSizes = [256, 512, 1024, 2048]

    static func pad(_ data: Data, toSize targetSize: Int) -> Data {
        guard data.count < targetSize else { return data }

        let paddingNeeded = targetSize - data.count

        guard paddingNeeded > 0 && paddingNeeded <= 255 else { return data }

        var padded = data

        padded.append(contentsOf: Array(repeating: UInt8(paddingNeeded), count: paddingNeeded))
        return padded
    }

    static func unpad(_ data: Data) -> Data {
        guard !data.isEmpty else { return data }
        let last = data.last!
        let paddingLength = Int(last)

        guard paddingLength > 0 && paddingLength <= data.count else { return data }

        let start = data.count - paddingLength
        let tail = data[start...]
        for b in tail { if b != last { return data } }
        return Data(data[..<start])
    }

    static func optimalBlockSize(for dataSize: Int) -> Int {

        let totalSize = dataSize + 16

        for blockSize in blockSizes {
            if totalSize <= blockSize {
                return blockSize
            }
        }

        return dataSize
    }
}
