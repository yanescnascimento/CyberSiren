import SwiftUI
import CoreLocation

public struct V2VStatusDisplay: View {
    public let location: CLLocation?
    public let speed: Float
    public let heading: Float
    public let connectedPeers: Int
    public let accentColor: Color

    public init(location: CLLocation?, speed: Float, heading: Float, connectedPeers: Int, accentColor: Color) {
        self.location = location
        self.speed = speed
        self.heading = heading
        self.connectedPeers = connectedPeers
        self.accentColor = accentColor
    }

    public var body: some View {
        HStack(alignment: .center, spacing: 14) {
            metric(title: "km/h", value: "\(Int(speed * 3.6))", color: accentColor)
            divider
            metric(title: "Heading", value: compass(for: heading))
            divider
            metric(title: "Peers", value: "\(connectedPeers)")
        }
        .padding(.vertical, 14)
        .padding(.horizontal, 16)
        .background(V2VColors.surfaceLight)
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(V2VColors.borderLight, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var divider: some View {
        Rectangle()
            .fill(V2VColors.borderLight)
            .frame(width: 1, height: 28)
    }

    private func metric(title: String, value: String, color: Color = V2VColors.ink) -> some View {
        VStack(alignment: .center, spacing: 4) {
            Text(value)
                .font(.system(size: 18, weight: .bold, design: .rounded))
                .foregroundColor(color)
            Text(title.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(V2VColors.muted)
                .tracking(1.2)
        }
        .frame(maxWidth: .infinity)
    }

    private func compass(for heading: Float) -> String {
        let h = Double(heading)
        switch h {
        case 337.5..., 0..<22.5: return "N"
        case 22.5..<67.5: return "NE"
        case 67.5..<112.5: return "E"
        case 112.5..<157.5: return "SE"
        case 157.5..<202.5: return "S"
        case 202.5..<247.5: return "SW"
        case 247.5..<292.5: return "W"
        case 292.5..<337.5: return "NW"
        default: return "?"
        }
    }
}
