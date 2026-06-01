import Foundation

public final class V2VCarAdapter: V2VCarService {
    private weak var viewModel: V2VViewModel?

    public init(viewModel: V2VViewModel) {
        self.viewModel = viewModel
    }

    public func getActiveAlerts() -> [ReceivedAlert] {
        viewModel?.activeAlerts ?? []
    }
    public func getAlertMode() -> AlertMode {
        viewModel?.alertMode ?? .receiver
    }
    public func isEmergencyActive() -> Bool {
        viewModel?.isEmergencyActive ?? false
    }
    public func getConnectedPeers() -> Int {
        viewModel?.connectedPeers ?? 0
    }
    public func getSelectedVehicleType() -> VehicleType {
        viewModel?.selectedVehicleType ?? .ambulance
    }
    public func getCurrentLatitude() -> Double? {
        viewModel?.currentLocation?.coordinate.latitude
    }
    public func getCurrentLongitude() -> Double? {
        viewModel?.currentLocation?.coordinate.longitude
    }
    public func getCurrentSpeedKmh() -> Float {
        (viewModel?.currentSpeed ?? 0) * 3.6
    }
    public func getCurrentHeadingDegrees() -> Float {
        viewModel?.currentHeading ?? 0
    }

    public func setMode(_ mode: AlertMode) {
        Task { @MainActor in self.viewModel?.setMode(mode) }
    }
    public func selectVehicleType(_ type: VehicleType) {
        Task { @MainActor in self.viewModel?.selectVehicleType(type) }
    }
    public func startEmergencyBroadcast(vehicleType: VehicleType) {
        Task { @MainActor in
            self.viewModel?.selectVehicleType(vehicleType)
            self.viewModel?.startEmergencyBroadcast()
        }
    }
    public func stopEmergencyBroadcast() {
        Task { @MainActor in self.viewModel?.stopEmergencyBroadcast() }
    }
    public func toggleEmergencyBroadcast() {
        Task { @MainActor in self.viewModel?.toggleEmergencyBroadcast() }
    }
}
