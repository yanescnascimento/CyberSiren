import Foundation
import SwiftUI

public enum V2VStrings {

    public static func vehicleLabel(_ type: VehicleType) -> String {
        switch type {
        case .ambulance: return localized("v2v_vehicle_ambulance", default: "Ambulance")
        case .fireTruck: return localized("v2v_vehicle_fire", default: "Fire Truck")
        case .policeCar: return localized("v2v_vehicle_police", default: "Police")
        case .emergency: return localized("v2v_vehicle_emergency", default: "Emergency")
        }
    }

    public static func directionLabel(_ direction: String) -> String {
        switch direction.lowercased() {
        case "ahead":  return localized("v2v_dir_ahead", default: "ahead")
        case "behind": return localized("v2v_dir_behind", default: "behind")
        case "left":   return localized("v2v_dir_left", default: "left")
        case "right":  return localized("v2v_dir_right", default: "right")
        default:       return localized("v2v_dir_unknown", default: "—")
        }
    }

    public static func peersUnit(_ count: Int) -> String {
        let key = count == 1 ? "v2v_peers_unit_one" : "v2v_peers_unit_other"
        let fallback = count == 1 ? "1 peer" : "\(count) peers"
        let template = localized(key, default: fallback)
        return template.contains("%d")
            ? String(format: template, count)
            : template.replacingOccurrences(of: "{count}", with: "\(count)")
    }

    public static func settingsTitle() -> String { localized("v2v_settings_title", default: "Settings") }
    public static func closeButton() -> String   { localized("close_plain", default: "Close") }

    public static func mockLabel() -> String    { localized("v2v_settings_mock_label", default: "Mock mode") }
    public static func mockHint() -> String     { localized("v2v_settings_mock_hint", default: "Generate fake alerts for demos") }
    public static func silentLabel() -> String  { localized("v2v_settings_silent_label", default: "Silent mode") }
    public static func silentHint() -> String   { localized("v2v_settings_silent_hint", default: "No sound, haptics, or banners — logs only") }
    public static func languageLabel() -> String { localized("v2v_settings_language_label", default: "Language") }
    public static func langEN() -> String       { localized("v2v_lang_en", default: "English") }
    public static func langES() -> String       { localized("v2v_lang_es", default: "Español") }
    public static func langPT() -> String       { localized("v2v_lang_pt", default: "Português") }

    public static func logsBtn() -> String      { localized("v2v_logs_btn", default: "Transport logs") }
    public static func logsTitle() -> String    { localized("v2v_transport_logs_title", default: "Transport logs") }
    public static func logsEmpty() -> String    { localized("v2v_logs_empty", default: "No transport events yet") }
    public static func logsClear() -> String    { localized("v2v_logs_clear", default: "Clear") }

    public static func receiverTitleAttention() -> String { localized("v2v_receiver_title_attention", default: "Heads up") }
    public static func receiverTitleListening() -> String { localized("v2v_receiver_title_listening", default: "All clear") }
    public static func receiverSubtitleNone() -> String   { localized("v2v_receiver_subtitle_none", default: "Listening for nearby emergencies") }
    public static func receiverSubtitleOne() -> String    { localized("v2v_receiver_subtitle_one", default: "1 emergency vehicle nearby") }
    public static func receiverSubtitleMany(_ n: Int) -> String {
        let template = localized("v2v_receiver_subtitle_many", default: "%d emergency vehicles nearby")
        return template.contains("%d") ? String(format: template, n) : "\(n) vehicles nearby"
    }
    public static func receiverOtherAlerts() -> String   { localized("v2v_receiver_other_alerts", default: "Other alerts") }
    public static func receiverClearTitle() -> String    { localized("v2v_receiver_clear_title", default: "All clear") }
    public static func receiverClearSubtitle() -> String { localized("v2v_receiver_clear_subtitle", default: "No emergency vehicles within range") }
    public static func receiverListeningChip() -> String { localized("v2v_receiver_listening_chip", default: "Listening on BLE + Cloud") }

    public static func senderTitleActive() -> String  { localized("v2v_sender_title_active", default: "Broadcasting") }
    public static func senderTitleReady() -> String   { localized("v2v_sender_title_ready", default: "Ready to broadcast") }
    public static func senderSubtitleActivePrefix() -> String  { localized("v2v_sender_subtitle_active_prefix", default: "Transmitting as ") }
    public static func senderSubtitleActiveSuffix() -> String  { localized("v2v_sender_subtitle_active_suffix", default: " over BLE + Cloud") }
    public static func senderSubtitleReadyPrefix() -> String   { localized("v2v_sender_subtitle_ready_prefix", default: "Selected vehicle: ") }
    public static func senderSubtitleReadySuffix() -> String   { localized("v2v_sender_subtitle_ready_suffix", default: ". Tap the button to start the alert.") }

    public static func btnActivate() -> String { localized("v2v_car_btn_activate", default: "ACTIVATE") }
    public static func btnStop() -> String     { localized("v2v_car_btn_stop", default: "STOP") }
    public static func selected() -> String    { localized("v2v_car_grid_selected", default: "Selected") }
    public static func tapToUse() -> String    { localized("v2v_car_grid_tap_to_use", default: "Tap to use") }

    public static func notifTitleNearby(vehicle: String) -> String {
        let template = localized("v2v_notif_title_nearby", default: "%@ nearby")
        return String(format: template, vehicle)
    }

    public static func localized(_ key: String, default fallback: String) -> String {
        let value = V2VLocalePrefs.shared.string(forKey: key)
        return value.isEmpty ? fallback : value
    }
}
