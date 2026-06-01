import Foundation
import CoreBluetooth
import BitFoundation

enum NoisePayloadType: UInt8 {

    case privateMessage = 0x01
    case readReceipt = 0x02
    case delivered = 0x03

    case verifyChallenge = 0x10
    case verifyResponse  = 0x11

    var description: String {
        switch self {
        case .privateMessage: return "privateMessage"
        case .readReceipt: return "readReceipt"
        case .delivered: return "delivered"
        case .verifyChallenge: return "verifyChallenge"
        case .verifyResponse: return "verifyResponse"
        }
    }
}

enum LazyHandshakeState {
    case none
    case handshakeQueued
    case handshaking
    case established
    case failed(Error)
}

protocol BitchatDelegate: AnyObject {
    func didReceiveMessage(_ message: BitchatMessage)
    func didConnectToPeer(_ peerID: PeerID)
    func didDisconnectFromPeer(_ peerID: PeerID)
    func didUpdatePeerList(_ peers: [PeerID])

    func isFavorite(fingerprint: String) -> Bool

    func didUpdateMessageDeliveryStatus(_ messageID: String, status: DeliveryStatus)

    func didReceiveNoisePayload(from peerID: PeerID, type: NoisePayloadType, payload: Data, timestamp: Date)

    func didUpdateBluetoothState(_ state: CBManagerState)
    func didReceivePublicMessage(from peerID: PeerID, nickname: String, content: String, timestamp: Date, messageID: String?)
}

extension BitchatDelegate {
    func isFavorite(fingerprint: String) -> Bool {
        return false
    }

    func didUpdateMessageDeliveryStatus(_ messageID: String, status: DeliveryStatus) {

    }

    func didReceiveNoisePayload(from peerID: PeerID, type: NoisePayloadType, payload: Data, timestamp: Date) {

    }

    func didReceivePublicMessage(from peerID: PeerID, nickname: String, content: String, timestamp: Date, messageID: String?) {

    }
}
