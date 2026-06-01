import SwiftUI

public struct V2VView: View {
    @ObservedObject public var viewModel: V2VViewModel
    @ObservedObject private var localePrefs = V2VLocalePrefs.shared

    @State private var showSettings = false
    @State private var showLogs = false

    public init(viewModel: V2VViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(spacing: 0) {
            header
            content
        }
        .background(V2VColors.backgroundLight.ignoresSafeArea())
        .sheet(isPresented: $showSettings) { settingsSheet }
        .sheet(isPresented: $showLogs) { logsSheet }
    }

    private var header: some View {
        HStack(spacing: 10) {
            V2VModeToggle(
                currentMode: Binding(
                    get: { viewModel.alertMode },
                    set: { viewModel.setMode($0) }
                ),
                enabled: !viewModel.isEmergencyActive,
                onModeChange: { viewModel.setMode($0) }
            )
            Button {
                showSettings = true
            } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(V2VColors.ink)
                    .padding(10)
                    .background(V2VColors.surfaceLight)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(V2VColors.borderLight, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.alertMode {
        case .sender:
            SenderModeView(
                isEmergencyActive: viewModel.isEmergencyActive,
                selectedVehicleType: viewModel.selectedVehicleType,
                currentLocation: viewModel.currentLocation,
                currentSpeed: viewModel.currentSpeed,
                currentHeading: viewModel.currentHeading,
                connectedPeers: viewModel.connectedPeers,
                onVehicleTypeSelected: { viewModel.selectVehicleType($0) },
                onEmergencyToggle: { viewModel.toggleEmergencyBroadcast() }
            )
        case .receiver:
            ReceiverModeView(
                activeAlerts: viewModel.activeAlerts,
                currentLocation: viewModel.currentLocation,
                connectedPeers: viewModel.connectedPeers
            )
        }
    }

    private var settingsSheet: some View {
        NavigationView {
            Form {
                Section {
                    Toggle(isOn: Binding(
                        get: { viewModel.mockEnabled },
                        set: { viewModel.setMockEnabled($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(V2VStrings.mockLabel())
                            Text(V2VStrings.mockHint())
                                .font(.caption).foregroundColor(V2VColors.muted)
                        }
                    }
                    Toggle(isOn: Binding(
                        get: { viewModel.silentMode },
                        set: { viewModel.setSilentMode($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(V2VStrings.silentLabel())
                            Text(V2VStrings.silentHint())
                                .font(.caption).foregroundColor(V2VColors.muted)
                        }
                    }
                }
                Section(header: Text(V2VStrings.languageLabel())) {
                    HStack(spacing: 10) {
                        languageButton(.en, label: V2VStrings.langEN())
                        languageButton(.es, label: V2VStrings.langES())
                        languageButton(.pt, label: V2VStrings.langPT())
                    }
                }
                Section {
                    Button {
                        showSettings = false
                        showLogs = true
                    } label: {
                        Label("\u{1F4CA}  \(V2VStrings.logsBtn())", systemImage: "")
                    }
                }
            }
            .navigationTitle(V2VStrings.settingsTitle())
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(V2VStrings.closeButton()) { showSettings = false }
                }
            }
        }
    }

    private func languageButton(_ locale: V2VLocale, label: String) -> some View {
        let isSelected = localePrefs.current == locale
        return Button {
            localePrefs.setLocale(locale)
        } label: {
            Text(label)
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(isSelected ? .white : V2VColors.ink)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(isSelected ? V2VColors.accent : V2VColors.accentSoft)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var logsSheet: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    metricCard(
                        label: "BLE",
                        avg: viewModel.bleAvgLatency,
                        loss: viewModel.bleLossPercent,
                        send: viewModel.bleSendCount,
                        recv: viewModel.bleRecvCount
                    )
                    metricCard(
                        label: "Firebase",
                        avg: viewModel.firebaseAvgLatency,
                        loss: viewModel.firebaseLossPercent,
                        send: viewModel.firebaseSendCount,
                        recv: viewModel.firebaseRecvCount
                    )
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)

                if let path = viewModel.sessionLogPath {
                    Text("\u{1F4C1} \(path)")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(V2VColors.muted)
                        .padding(.horizontal, 16)
                        .padding(.top, 6)
                }

                Divider().padding(.top, 8)

                if viewModel.transportLogs.isEmpty {
                    Spacer()
                    Text(V2VStrings.logsEmpty())
                        .foregroundColor(V2VColors.muted)
                        .frame(maxWidth: .infinity, alignment: .center)
                    Spacer()
                } else {
                    let now = Date()
                    List(viewModel.transportLogs) { entry in
                        transportRow(entry, now: now)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(V2VStrings.logsTitle())
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(V2VStrings.logsClear()) {
                        viewModel.clearTransportLogs()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(V2VStrings.closeButton()) { showLogs = false }
                }
            }
        }
    }

    private func metricCard(label: String, avg: Int64, loss: Float, send: Int, recv: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.system(size: 12, weight: .bold))
            Text("\u{2300} \(avg)ms")
                .font(.system(size: 11))
                .foregroundColor(avg > 500 ? V2VColors.emergencyRed : V2VColors.muted)
            Text(String(format: "loss %.1f%%", loss))
                .font(.system(size: 11))
                .foregroundColor(loss > 10 ? V2VColors.emergencyRed : V2VColors.muted)
            Text("\u{2191}\(send) \u{2193}\(recv)")
                .font(.system(size: 11))
                .foregroundColor(V2VColors.muted)
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(V2VColors.surfaceLight)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(V2VColors.borderLight, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func transportRow(_ entry: TransportLogEntry, now: Date) -> some View {
        let transportColor: Color = (entry.transport == .ble)
            ? Color(red: 0.31, green: 0.76, blue: 0.97)
            : Color(red: 1.00, green: 0.72, blue: 0.30)
        let statusColor: Color = entry.success
            ? Color(red: 0.51, green: 0.78, blue: 0.52)
            : Color(red: 0.90, green: 0.45, blue: 0.45)
        let latency = entry.latencyMs.map { "\($0)ms" } ?? "—"
        return HStack(spacing: 6) {
            Text(String(entry.transport.label.prefix(3)))
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(transportColor)
            Text(entry.direction.symbol).font(.system(size: 12))
            Text(entry.success ? "" : "")
                .font(.system(size: 12))
                .foregroundColor(statusColor)
            Text(latency)
                .font(.system(size: 11, design: .monospaced))
                .frame(width: 60, alignment: .leading)
            Text(String(entry.details.prefix(28)))
                .font(.system(size: 10))
                .foregroundColor(V2VColors.muted)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(entry.ageText(now: now))
                .font(.system(size: 10))
                .foregroundColor(V2VColors.muted)
        }
    }
}
