import SwiftUI
import CoreLocation

public struct ReceiverModeView: View {
    public let activeAlerts: [ReceivedAlert]
    public let currentLocation: CLLocation?
    public let connectedPeers: Int

    public init(activeAlerts: [ReceivedAlert], currentLocation: CLLocation?, connectedPeers: Int) {
        self.activeAlerts = activeAlerts
        self.currentLocation = currentLocation
        self.connectedPeers = connectedPeers
    }

    private var sorted: [ReceivedAlert] {
        activeAlerts.sorted { $0.distanceMeters < $1.distanceMeters }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            Spacer().frame(height: 16)

            if sorted.isEmpty {
                emptyState
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        V2VAlertHighlightCard(alert: sorted[0])
                        if sorted.count > 1 {
                            Text(V2VStrings.receiverOtherAlerts())
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(V2VColors.muted)
                                .padding(.top, 6)
                            ForEach(sorted.dropFirst(), id: \.alert.messageId) { alert in
                                V2VAlertCompactRow(alert: alert)
                            }
                        }
                    }
                }
            }

            if let loc = currentLocation { locationFooter(loc) }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .background(V2VColors.backgroundLight)
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(sorted.isEmpty ? V2VStrings.receiverTitleListening() : V2VStrings.receiverTitleAttention())
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(V2VColors.ink)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundColor(V2VColors.muted)
            }
            Spacer()
            peersChip
        }
        .padding(.vertical, 4)
    }

    private var subtitle: String {
        switch sorted.count {
        case 0:  return V2VStrings.receiverSubtitleNone()
        case 1:  return V2VStrings.receiverSubtitleOne()
        default: return V2VStrings.receiverSubtitleMany(sorted.count)
        }
    }

    private var peersChip: some View {
        let connected = connectedPeers > 0
        let color = connected ? V2VColors.accent : V2VColors.muted
        return HStack(spacing: 6) {
            Image(systemName: "wifi")
                .font(.system(size: 13, weight: .semibold))
            Text(V2VStrings.peersUnit(connectedPeers))
                .font(.system(size: 13, weight: .semibold))
        }
        .foregroundColor(color)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(connected ? V2VColors.accent.opacity(0.12) : V2VColors.surfaceLight)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(connected ? V2VColors.accent.opacity(0.25) : V2VColors.borderLight, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var emptyState: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle().fill(V2VColors.safeSoft).frame(width: 110, height: 110)
                Image(systemName: "checkmark.circle.fill")
                    .resizable().scaledToFit()
                    .frame(width: 56, height: 56)
                    .foregroundColor(V2VColors.safe)
            }
            VStack(spacing: 6) {
                Text(V2VStrings.receiverClearTitle())
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(V2VColors.ink)
                Text(V2VStrings.receiverClearSubtitle())
                    .font(.system(size: 13))
                    .foregroundColor(V2VColors.muted)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: 8) {
                Image(systemName: "waveform")
                    .font(.system(size: 13))
                    .foregroundColor(V2VColors.inkSoft)
                Text(V2VStrings.receiverListeningChip())
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(V2VColors.inkSoft)
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

    private func locationFooter(_ location: CLLocation) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "location.fill")
                .font(.system(size: 12))
                .foregroundColor(V2VColors.muted)
            Text(String(format: "Lat %.4f   Lon %.4f", location.coordinate.latitude, location.coordinate.longitude))
                .font(.system(size: 12))
                .foregroundColor(V2VColors.muted)
        }
        .padding(.top, 10)
        .padding(.bottom, 12)
    }
}
