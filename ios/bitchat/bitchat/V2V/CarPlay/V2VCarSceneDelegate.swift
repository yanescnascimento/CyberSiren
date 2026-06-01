#if canImport(CarPlay)
import Foundation
import CarPlay

public final class V2VCarSceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {

    public var interfaceController: CPInterfaceController?
    private var refreshTimer: Timer?
    private var serviceObserver: NSObjectProtocol?
    private var localeObserver: NSObjectProtocol?

    public func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        presentInitialTemplate()

        refreshTimer = Timer.scheduledTimer(withTimeInterval: 1.2, repeats: true) { [weak self] _ in
            self?.refreshCurrentTemplate()
        }
        serviceObserver = NotificationCenter.default.addObserver(
            forName: .v2vCarServiceChanged, object: nil, queue: .main
        ) { [weak self] _ in self?.presentInitialTemplate() }

        localeObserver = NotificationCenter.default.addObserver(
            forName: .v2vLocaleChanged, object: nil, queue: .main
        ) { [weak self] _ in self?.presentInitialTemplate() }
    }

    public func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnect interfaceController: CPInterfaceController
    ) {
        refreshTimer?.invalidate(); refreshTimer = nil
        serviceObserver.map(NotificationCenter.default.removeObserver)
        localeObserver.map(NotificationCenter.default.removeObserver)
        self.interfaceController = nil
    }

    private func presentInitialTemplate() {
        guard let controller = interfaceController else { return }
        let template = makeRootTemplate()
        controller.setRootTemplate(template, animated: false, completion: nil)
    }

    private func refreshCurrentTemplate() {
        guard let controller = interfaceController else { return }
        let template = makeRootTemplate()
        controller.setRootTemplate(template, animated: false, completion: nil)
    }

    private func makeRootTemplate() -> CPTemplate {
        let service = V2VCarServiceHolder.shared.getService()
        guard let service = service else {
            return CPInformationTemplate(
                title: V2VStrings.localized("v2v_car_unavailable_title", default: "V2V not ready"),
                layout: .leading,
                items: [
                    CPInformationItem(
                        title: V2VStrings.localized("v2v_car_unavailable_msg", default: "Open CyberSiren on the phone to enable the in-car screen."),
                        detail: nil
                    )
                ],
                actions: []
            )
        }
        switch service.getAlertMode() {
        case .receiver:
            return V2VCarTemplates.receiver(service: service)
        case .sender:
            return V2VCarTemplates.sender(service: service)
        }
    }
}

enum V2VCarTemplates {

    static func receiver(service: V2VCarService) -> CPTemplate {
        let alerts = service.getActiveAlerts()
        let title = alerts.isEmpty
            ? V2VStrings.receiverTitleListening()
            : V2VStrings.receiverTitleAttention()
        let summary = alerts.isEmpty
            ? V2VStrings.receiverSubtitleNone()
            : (alerts.count == 1
                ? V2VStrings.receiverSubtitleOne()
                : V2VStrings.receiverSubtitleMany(alerts.count))

        let items: [CPListItem] = alerts.isEmpty
            ? [CPListItem(text: V2VStrings.receiverClearTitle(), detailText: V2VStrings.receiverClearSubtitle())]
            : alerts.map { alert in
                let item = CPListItem(
                    text: "\(V2VStrings.vehicleLabel(alert.alert.vehicleType)) · \(alert.distanceDisplay)",
                    detailText: "\(V2VStrings.directionLabel(alert.relativeDirection)) · \(Int(alert.alert.speedKmh)) km/h"
                )
                item.handler = { _, completion in completion() }
                return item
            }

        let section = CPListSection(items: items, header: summary, sectionIndexTitle: nil)
        let list = CPListTemplate(title: title, sections: [section])

        let toggleAction = CPBarButton(title: V2VStrings.localized("v2v_car_mode_to_sender", default: "Send")) { _ in
            service.setMode(.sender)
        }
        list.leadingNavigationBarButtons = [toggleAction]
        return list
    }

    static func sender(service: V2VCarService) -> CPTemplate {
        let isActive = service.isEmergencyActive()
        let vehicle = service.getSelectedVehicleType()

        let actionTitle = isActive
            ? V2VStrings.btnStop()
            : V2VStrings.btnActivate()

        let actionGrid = VehicleType.allCases.map { type -> CPGridButton in
            let title = V2VStrings.vehicleLabel(type)
            let image = symbolImage(for: type)
            return CPGridButton(titleVariants: [title], image: image) { _ in
                service.selectVehicleType(type)
                if isActive { return }
                service.startEmergencyBroadcast(vehicleType: type)
            }
        }

        let footerTitle = "\(V2VStrings.vehicleLabel(vehicle)) · \(actionTitle)"
        let grid = CPGridTemplate(title: footerTitle, gridButtons: actionGrid)
        let stop = CPBarButton(title: actionTitle) { _ in
            service.toggleEmergencyBroadcast()
        }
        grid.trailingNavigationBarButtons = [stop]

        let backToReceiver = CPBarButton(title: V2VStrings.localized("v2v_car_mode_to_receiver", default: "Receive")) { _ in
            service.setMode(.receiver)
        }
        grid.leadingNavigationBarButtons = [backToReceiver]
        return grid
    }

    private static func symbolImage(for type: VehicleType) -> UIImage {
        let symbol: String
        switch type {
        case .ambulance:  symbol = "cross.case.fill"
        case .fireTruck:  symbol = "flame.fill"
        case .policeCar:  symbol = "shield.lefthalf.filled"
        case .emergency:  symbol = "exclamationmark.triangle.fill"
        }
        let config = UIImage.SymbolConfiguration(pointSize: 28, weight: .semibold)
        return UIImage(systemName: symbol, withConfiguration: config) ?? UIImage()
    }
}
#endif
