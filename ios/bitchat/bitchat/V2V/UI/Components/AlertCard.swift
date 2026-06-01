import SwiftUI

public struct V2VAlertHighlightCard: View {
    public let alert: ReceivedAlert

    public init(alert: ReceivedAlert) { self.alert = alert }

    public var body: some View {
        let urgency = alert.urgencyLevel
        let color = V2VColors.urgencyColor(urgency)
        let accent = V2VColors.accentFor(alert.alert.vehicleType)
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(alert.alert.vehicleType.emoji).font(.system(size: 36))
                VStack(alignment: .leading, spacing: 4) {
                    Text(V2VStrings.vehicleLabel(alert.alert.vehicleType))
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(V2VColors.ink)
                    Text(V2VStrings.directionLabel(alert.relativeDirection))
                        .font(.system(size: 13))
                        .foregroundColor(accent)
                }
                Spacer()
                urgencyBadge(urgency: urgency, color: color)
            }
            HStack(alignment: .firstTextBaseline) {
                Text(alert.distanceDisplay)
                    .font(.system(size: 36, weight: .bold, design: .rounded))
                    .foregroundColor(color)
                Spacer()
                Text("\(Int(alert.alert.speedKmh)) km/h")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(V2VColors.inkSoft)
            }
        }
        .padding(18)
        .background(color.opacity(0.06))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(color.opacity(0.4), lineWidth: 1.5)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private func urgencyBadge(urgency: UrgencyLevel, color: Color) -> some View {
        let label: String
        switch urgency {
        case .critical: label = "CRITICAL"
        case .high:     label = "HIGH"
        case .medium:   label = "MED"
        case .low:      label = "LOW"
        }
        return Text(label)
            .font(.system(size: 11, weight: .bold))
            .foregroundColor(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(color)
            .clipShape(Capsule())
    }
}

public struct V2VAlertCompactRow: View {
    public let alert: ReceivedAlert

    public init(alert: ReceivedAlert) { self.alert = alert }

    public var body: some View {
        HStack(spacing: 12) {
            Text(alert.alert.vehicleType.emoji).font(.system(size: 22))
            VStack(alignment: .leading, spacing: 2) {
                Text(V2VStrings.vehicleLabel(alert.alert.vehicleType))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(V2VColors.ink)
                Text(V2VStrings.directionLabel(alert.relativeDirection))
                    .font(.system(size: 11))
                    .foregroundColor(V2VColors.muted)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(alert.distanceDisplay)
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .foregroundColor(V2VColors.urgencyColor(alert.urgencyLevel))
                Text("\(Int(alert.alert.speedKmh)) km/h")
                    .font(.system(size: 10))
                    .foregroundColor(V2VColors.muted)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(V2VColors.surfaceLight)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(V2VColors.borderLight, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
