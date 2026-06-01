import Foundation

struct NoisePayload {
    let type: NoisePayloadType
    let data: Data

    func encode() -> Data {
        var encoded = Data()
        encoded.append(type.rawValue)
        encoded.append(data)
        return encoded
    }

    static func decode(_ data: Data) -> NoisePayload? {

        guard !data.isEmpty else {
            return nil
        }

        let firstByte = data[data.startIndex]
        guard let type = NoisePayloadType(rawValue: firstByte) else {
            return nil
        }

        let payloadData = data.count > 1 ? Data(data.dropFirst()) : Data()
        return NoisePayload(type: type, data: payloadData)
    }
}
