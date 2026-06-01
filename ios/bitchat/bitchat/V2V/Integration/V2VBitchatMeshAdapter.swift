import Foundation
import BitFoundation

public final class V2VBitchatMeshAdapter: V2VMeshBroadcaster {

    public let myPeerId: String
    private let broadcast: (Data, UInt8) -> Void

    public init(myPeerId: String, broadcast: @escaping (Data, UInt8) -> Void) {
        self.myPeerId = myPeerId
        self.broadcast = broadcast
    }

    public func broadcastEmergencyAlert(payload: Data, ttl: UInt8) {
        broadcast(payload, ttl)
    }
}

public enum V2VInboundDispatcher {

    @MainActor
    public static func dispatch(
        payload: Data,
        fromPeerId: String,
        sentAtMs: Int64?,
        viewModel: V2VViewModel
    ) {
        viewModel.processIncomingPayload(
            payload,
            fromPeerId: fromPeerId,
            sentAtMs: sentAtMs,
            transport: .ble
        )
    }
}
