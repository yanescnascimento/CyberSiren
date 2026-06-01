import SwiftUI
import CoreLocation

public struct SenderModeView: View {
    public let isEmergencyActive: Bool
    public let selectedVehicleType: VehicleType
    public let currentLocation: CLLocation?
    public let currentSpeed: Float
    public let currentHeading: Float
    public let connectedPeers: Int
    public let onVehicleTypeSelected: (VehicleType) -> Void
    public let onEmergencyToggle: () -> Void

    public init(
        isEmergencyActive: Bool,
        selectedVehicleType: VehicleType,
        currentLocation: CLLocation?,
        currentSpeed: Float,
        currentHeading: Float,
        connectedPeers: Int,
        onVehicleTypeSelected: @escaping (VehicleType) -> Void,
        onEmergencyToggle: @escaping () -> Void
    ) {
        self.isEmergencyActive = isEmergencyActive
        self.selectedVehicleType = selectedVehicleType
        self.currentLocation = currentLocation
        self.currentSpeed = currentSpeed
        self.currentHeading = currentHeading
        self.connectedPeers = connectedPeers
        self.onVehicleTypeSelected = onVehicleTypeSelected
        self.onEmergencyToggle = onEmergencyToggle
    }

    public var body: some View {
        let accent = V2VColors.accentFor(selectedVehicleType)
        let vehicleName = V2VStrings.vehicleLabel(selectedVehicleType)

        VStack(alignment: .leading, spacing: 0) {
            Text(isEmergencyActive ? V2VStrings.senderTitleActive() : V2VStrings.senderTitleReady())
                .font(.system(size: 26, weight: .bold))
                .foregroundColor(V2VColors.ink)
                .padding(.top, 4)

            subtitle(accent: accent, vehicleName: vehicleName)
                .padding(.top, 6)
                .padding(.bottom, 24)

            V2VVehicleSelector(
                selectedType: selectedVehicleType,
                enabled: !isEmergencyActive,
                onTypeSelected: onVehicleTypeSelected
            )

            Spacer()

            HStack {
                Spacer()
                V2VEmergencyButton(
                    isActive: isEmergencyActive,
                    vehicleType: selectedVehicleType,
                    onTap: onEmergencyToggle
                )
                Spacer()
            }

            Spacer()

            V2VStatusDisplay(
                location: currentLocation,
                speed: currentSpeed,
                heading: currentHeading,
                connectedPeers: connectedPeers,
                accentColor: accent
            )
            .padding(.bottom, 12)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .background(V2VColors.backgroundLight)
    }

    @ViewBuilder
    private func subtitle(accent: Color, vehicleName: String) -> some View {
        if isEmergencyActive {
            (
                Text(V2VStrings.senderSubtitleActivePrefix())
                + Text(vehicleName.lowercased())
                    .foregroundColor(accent)
                    .fontWeight(.semibold)
                + Text(V2VStrings.senderSubtitleActiveSuffix())
            )
            .font(.system(size: 14))
            .foregroundColor(V2VColors.muted)
        } else {
            (
                Text(V2VStrings.senderSubtitleReadyPrefix())
                + Text(vehicleName)
                    .foregroundColor(accent)
                    .fontWeight(.semibold)
                + Text(V2VStrings.senderSubtitleReadySuffix())
            )
            .font(.system(size: 14))
            .foregroundColor(V2VColors.muted)
        }
    }
}
