import SwiftUI

public struct V2VVehicleSelector: View {
    public let selectedType: VehicleType
    public let enabled: Bool
    public let onTypeSelected: (VehicleType) -> Void

    public init(selectedType: VehicleType, enabled: Bool = true, onTypeSelected: @escaping (VehicleType) -> Void) {
        self.selectedType = selectedType
        self.enabled = enabled
        self.onTypeSelected = onTypeSelected
    }

    public var body: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            ForEach(VehicleType.allCases, id: \.rawValue) { type in
                vehicleTile(type)
            }
        }
    }

    @ViewBuilder
    private func vehicleTile(_ type: VehicleType) -> some View {
        let isSelected = selectedType == type
        let accent = V2VColors.accentFor(type)
        Button {
            guard enabled else { return }
            onTypeSelected(type)
        } label: {
            HStack(spacing: 12) {
                Text(type.emoji).font(.system(size: 26))
                VStack(alignment: .leading, spacing: 2) {
                    Text(V2VStrings.vehicleLabel(type))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(V2VColors.ink)
                    Text(isSelected ? V2VStrings.selected() : V2VStrings.tapToUse())
                        .font(.system(size: 11))
                        .foregroundColor(isSelected ? accent : V2VColors.muted)
                }
                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(isSelected ? accent.opacity(0.10) : V2VColors.surfaceLight)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isSelected ? accent : V2VColors.borderLight, lineWidth: isSelected ? 2 : 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
