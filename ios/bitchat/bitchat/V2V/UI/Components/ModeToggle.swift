import SwiftUI

public struct V2VModeToggle: View {
    @Binding public var currentMode: AlertMode
    public let enabled: Bool
    public let onModeChange: (AlertMode) -> Void

    public init(currentMode: Binding<AlertMode>, enabled: Bool = true, onModeChange: @escaping (AlertMode) -> Void) {
        self._currentMode = currentMode
        self.enabled = enabled
        self.onModeChange = onModeChange
    }

    public var body: some View {
        HStack(spacing: 0) {
            segment(.receiver, label: "RX", systemImage: "antenna.radiowaves.left.and.right")
            segment(.sender, label: "TX", systemImage: "megaphone.fill")
        }
        .padding(4)
        .background(V2VColors.surfaceLight)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(V2VColors.borderLight, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .opacity(enabled ? 1 : 0.5)
    }

    @ViewBuilder
    private func segment(_ mode: AlertMode, label: String, systemImage: String) -> some View {
        let selected = currentMode == mode
        Button {
            guard enabled else { return }
            onModeChange(mode)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(size: 14, weight: .semibold))
                Text(label)
                    .font(.system(size: 13, weight: .semibold))
            }
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(selected ? V2VColors.accent : Color.clear)
            .foregroundColor(selected ? .white : V2VColors.ink)
            .clipShape(RoundedRectangle(cornerRadius: 9))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
