import SwiftUI

public struct V2VEmergencyButton: View {
    public let isActive: Bool
    public let vehicleType: VehicleType
    public let enabled: Bool
    public let onTap: () -> Void

    @State private var pulse: CGFloat = 1

    public init(isActive: Bool, vehicleType: VehicleType, enabled: Bool = true, onTap: @escaping () -> Void) {
        self.isActive = isActive
        self.vehicleType = vehicleType
        self.enabled = enabled
        self.onTap = onTap
    }

    public var body: some View {
        let accent = V2VColors.accentFor(vehicleType)
        Button(action: onTap) {
            ZStack {
                if isActive {
                    Circle()
                        .fill(accent.opacity(0.15))
                        .frame(width: 220, height: 220)
                        .scaleEffect(pulse)
                        .opacity(2 - Double(pulse))
                }
                Circle()
                    .fill(isActive ? accent : V2VColors.ink)
                    .frame(width: 180, height: 180)
                    .shadow(color: accent.opacity(isActive ? 0.45 : 0.1), radius: isActive ? 28 : 12, x: 0, y: 8)
                VStack(spacing: 8) {
                    Text(vehicleType.emoji).font(.system(size: 44))
                    Text(isActive ? V2VStrings.btnStop() : V2VStrings.btnActivate())
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(.white)
                        .tracking(2)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .onAppear { if isActive { startPulse() } }
        .onChange(of: isActive) { active in
            if active { startPulse() } else { pulse = 1 }
        }
    }

    private func startPulse() {
        withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: false)) {
            pulse = 1.35
        }
    }
}
