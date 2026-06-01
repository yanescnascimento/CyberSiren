public enum FileTransferLimits {

    public static let maxPayloadBytes: Int = 1 * 1024 * 1024

    public static let maxVoiceNoteBytes: Int = 512 * 1024

    public static let maxImageBytes: Int = 512 * 1024

    public static let maxFramedFileBytes: Int = {
        let maxMetadataBytes = Int(UInt16.max) * 2
        let tlvEnvelopeOverhead = 18 + maxMetadataBytes
        let binaryEnvelopeOverhead = BinaryProtocol.v2HeaderSize
            + BinaryProtocol.senderIDSize
            + BinaryProtocol.recipientIDSize
            + BinaryProtocol.signatureSize
        return maxPayloadBytes + tlvEnvelopeOverhead + binaryEnvelopeOverhead
    }()

    public static func isValidPayload(_ size: Int) -> Bool {
        size <= maxPayloadBytes
    }
}
