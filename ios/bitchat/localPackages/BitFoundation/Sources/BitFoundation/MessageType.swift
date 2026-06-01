public enum MessageType: UInt8 {

    case announce = 0x01
    case message = 0x02
    case leave = 0x03
    case requestSync = 0x21

    case noiseHandshake = 0x10
    case noiseEncrypted = 0x11

    case fragment = 0x20
    case fileTransfer = 0x22

    case emergencyAlert = 0x30

    public var description: String {
        switch self {
        case .announce: return "announce"
        case .message: return "message"
        case .leave: return "leave"
        case .requestSync: return "requestSync"
        case .noiseHandshake: return "noiseHandshake"
        case .noiseEncrypted: return "noiseEncrypted"
        case .fragment: return "fragment"
        case .fileTransfer: return "fileTransfer"
        case .emergencyAlert: return "emergencyAlert"
        }
    }
}
