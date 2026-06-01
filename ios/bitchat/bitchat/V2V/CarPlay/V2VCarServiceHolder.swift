import Foundation
import CoreLocation
import Combine

public protocol V2VCarService: AnyObject {
    func getActiveAlerts() -> [ReceivedAlert]
    func getAlertMode() -> AlertMode
    func isEmergencyActive() -> Bool
    func getConnectedPeers() -> Int
    func getSelectedVehicleType() -> VehicleType
    func getCurrentLatitude() -> Double?
    func getCurrentLongitude() -> Double?
    func getCurrentSpeedKmh() -> Float
    func getCurrentHeadingDegrees() -> Float

    func setMode(_ mode: AlertMode)
    func selectVehicleType(_ type: VehicleType)
    func startEmergencyBroadcast(vehicleType: VehicleType)
    func stopEmergencyBroadcast()
    func toggleEmergencyBroadcast()
}

public final class V2VCarServiceHolder {
    public static let shared = V2VCarServiceHolder()
    private let lock = NSLock()
    private weak var service: V2VCarService?
    private init() {}

    public func setService(_ service: V2VCarService?) {
        lock.lock(); defer { lock.unlock() }
        self.service = service
        NotificationCenter.default.post(name: .v2vCarServiceChanged, object: nil)
    }

    public func getService() -> V2VCarService? {
        lock.lock(); defer { lock.unlock() }
        return service
    }
}

public extension Notification.Name {
    static let v2vCarServiceChanged = Notification.Name("v2v.carServiceChanged")
}
