import struct Foundation.Data

public extension Data {
    func hexEncodedString() -> String {
        if self.isEmpty {
            return ""
        }
        return self.map { String(format: "%02x", $0) }.joined()
    }

    init?(hexString: String) {
        var hex = hexString.trimmed

        if hex.hasPrefix("0x") || hex.hasPrefix("0X") {
            hex = String(hex.dropFirst(2))
        }

        guard hex.count % 2 == 0 else {
            return nil
        }

        guard !hex.isEmpty else {
            self = Data()
            return
        }

        let len = hex.count / 2
        var data = Data(capacity: len)
        var index = hex.startIndex

        for _ in 0..<len {
            let nextIndex = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(String(hex[index..<nextIndex]), radix: 16) else {
                return nil
            }
            data.append(byte)
            index = nextIndex
        }

        self = data
    }
}
