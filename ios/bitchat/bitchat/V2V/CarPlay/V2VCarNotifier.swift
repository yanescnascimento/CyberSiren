import Foundation
import UserNotifications

public final class V2VCarNotifier {

    public static let shared = V2VCarNotifier()
    private init() {}

    private static let categoryId = "v2v_emergency_alerts"

    private var activeIds: [String: String] = [:]

    public func ensureCategory() {
        let category = UNNotificationCategory(
            identifier: Self.categoryId,
            actions: [],
            intentIdentifiers: [],
            hiddenPreviewsBodyPlaceholder: nil,
            options: [.allowInCarPlay]
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    public func notifyAlert(_ alert: ReceivedAlert, alertUser: Bool = true) {
        let center = UNUserNotificationCenter.current()
        let peer = alert.alert.senderPeerId
        let identifier = activeIds[peer] ?? "v2v-\(peer)"
        activeIds[peer] = identifier

        let content = UNMutableNotificationContent()
        content.title = V2VStrings.notifTitleNearby(vehicle: V2VStrings.vehicleLabel(alert.alert.vehicleType))
        content.body = composeBody(for: alert)
        content.categoryIdentifier = Self.categoryId
        content.sound = alertUser ? .defaultCritical : nil
        content.userInfo = [
            "v2v_alert": true,
            "peerId": peer,
            "lat": alert.alert.latitude,
            "lon": alert.alert.longitude,
            "messageId": alert.alert.messageId
        ]
        if #available(iOS 15.0, *) {
            content.interruptionLevel = alertUser ? .timeSensitive : .passive
            content.relevanceScore = 1.0
        }

        if alertUser {
            center.removePendingNotificationRequests(withIdentifiers: [identifier])
            center.removeDeliveredNotifications(withIdentifiers: [identifier])
        }

        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        center.add(request, withCompletionHandler: nil)
    }

    public func cancelAlert(senderPeerId: String) {
        guard let id = activeIds.removeValue(forKey: senderPeerId) else { return }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.removeDeliveredNotifications(withIdentifiers: [id])
    }

    public func syncWithActive(_ activeAlerts: [ReceivedAlert]) {
        let active = Set(activeAlerts.map { $0.alert.senderPeerId })
        let stale = activeIds.keys.filter { !active.contains($0) }
        for peer in stale { cancelAlert(senderPeerId: peer) }
    }

    public func cancelAll() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: Array(activeIds.values))
        center.removeDeliveredNotifications(withIdentifiers: Array(activeIds.values))
        activeIds.removeAll()
    }

    private func composeBody(for alert: ReceivedAlert) -> String {
        var parts: [String] = [alert.distanceDisplay]
        let dir = V2VStrings.directionLabel(alert.relativeDirection)
        if !dir.isEmpty, dir != "—" { parts.append(dir) }
        let kmh = Int(alert.alert.speedKmh)
        if kmh > 0 { parts.append("\(kmh) km/h") }
        return parts.joined(separator: " · ")
    }
}
