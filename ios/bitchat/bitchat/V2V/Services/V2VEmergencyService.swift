import Foundation
import CoreLocation
import BitFoundation

public protocol V2VEmergencyDelegate: AnyObject {
    func onEmergencyAlertReceived(_ alert: ReceivedAlert)
    func onEmergencyBroadcastStarted(vehicleType: VehicleType)
    func onEmergencyBroadcastStopped()
}

public protocol V2VMeshBroadcaster: AnyObject {

    var myPeerId: String { get }

    func broadcastEmergencyAlert(payload: Data, ttl: UInt8)
}

public final class V2VEmergencyService: NSObject, CLLocationManagerDelegate {

    private static let broadcastIntervalMs: Int64 = 1_000
    private static let locationUpdateMinMeters: CLLocationDistance = 1
    private static let emergencyTtl: UInt8 = 7

    public weak var delegate: V2VEmergencyDelegate?

    private let meshBroadcaster: V2VMeshBroadcaster
    private let firebaseTransport: FirebaseTransport
    private let logger = TransportLogRepository.shared
    private let deduplicator = AlertDeduplicationService()

    private let locationManager = CLLocationManager()
    public private(set) var currentLocation: CLLocation?
    public private(set) var currentSpeed: Float = 0
    public private(set) var currentHeading: Float = 0

    public var onLocationUpdate: ((CLLocation) -> Void)?

    public private(set) var currentVehicleType: VehicleType = .ambulance
    public private(set) var isEmergencyActive = false
    public private(set) var receivedAlerts: [ReceivedAlert] = []

    private var broadcastTimer: DispatchSourceTimer?
    private let broadcastQueue = DispatchQueue(label: "v2v.broadcast")

    public init(meshBroadcaster: V2VMeshBroadcaster, firebaseTransport: FirebaseTransport = .shared) {
        self.meshBroadcaster = meshBroadcaster
        self.firebaseTransport = firebaseTransport
        super.init()
        setupLocation()
    }

    deinit {
        shutdown()
    }

    public func shutdown() {
        stopEmergencyBroadcast()
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
    }

    private func setupLocation() {
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = Self.locationUpdateMinMeters
        locationManager.activityType = .automotiveNavigation

        locationManager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() {
            locationManager.startUpdatingHeading()
        }
    }

    public func setVehicleType(_ type: VehicleType) {
        currentVehicleType = type
    }

    public func startEmergencyBroadcast(vehicleType: VehicleType? = nil) {
        guard !isEmergencyActive else { return }
        if let vt = vehicleType { currentVehicleType = vt }
        isEmergencyActive = true

        let timer = DispatchSource.makeTimerSource(queue: broadcastQueue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(Int(Self.broadcastIntervalMs)))
        timer.setEventHandler { [weak self] in
            guard let self = self, self.isEmergencyActive else { return }
            guard let location = self.currentLocation else { return }
            self.broadcastEmergencyAlert(from: location)
        }
        timer.resume()
        broadcastTimer = timer

        delegate?.onEmergencyBroadcastStarted(vehicleType: currentVehicleType)
    }

    public func stopEmergencyBroadcast() {
        guard isEmergencyActive else { return }
        isEmergencyActive = false
        broadcastTimer?.cancel()
        broadcastTimer = nil
        delegate?.onEmergencyBroadcastStopped()
    }

    public func toggleEmergencyBroadcast() {
        if isEmergencyActive { stopEmergencyBroadcast() } else { startEmergencyBroadcast() }
    }

    private func broadcastEmergencyAlert(from location: CLLocation) {
        let alertType: AlertType
        switch Float(location.speed) {
        case ..<1:  alertType = .stationary
        case ..<5:  alertType = .passing
        default:    alertType = .approaching
        }
        let alert = V2VEmergencyAlert(
            vehicleType: currentVehicleType,
            alertType: alertType,
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            speed: Float(max(location.speed, 0)),
            heading: Float(max(location.course, 0)),
            senderPeerId: meshBroadcaster.myPeerId
        )

        let bleStart = Date()
        let payload = alert.toPayload()
        meshBroadcaster.broadcastEmergencyAlert(payload: payload, ttl: Self.emergencyTtl)
        let bleLatency = Int64(Date().timeIntervalSince(bleStart) * 1000)
        logger.logSend(
            transport: .ble,
            messageId: alert.messageId,
            latencyMs: bleLatency,
            payloadBytes: payload.count,
            details: "peer=\(meshBroadcaster.myPeerId.prefix(8))"
        )

        Task {
            let fbStart = Date()
            do {
                guard firebaseTransport.isAvailable else { return }
                let cloudAlert = FirebaseEmergencyAlert(from: alert)
                try await firebaseTransport.sendEmergencyAlert(cloudAlert)
                let fbLatency = Int64(Date().timeIntervalSince(fbStart) * 1000)
                self.logger.logSend(
                    transport: .firebase,
                    messageId: alert.messageId,
                    latencyMs: fbLatency,
                    payloadBytes: cloudAlert.toBytes().count,
                    details: "channel=emergency"
                )
            } catch {
                self.logger.logFailure(
                    transport: .firebase,
                    direction: .send,
                    messageId: alert.messageId,
                    details: "Firebase send error: \(error.localizedDescription)"
                )
            }
        }
    }

    @discardableResult
    public func processIncomingPayload(
        _ payload: Data,
        fromPeerId: String,
        sentAtMs: Int64? = nil,
        transport: TransportType = .ble
    ) -> V2VEmergencyAlert? {
        let receiveTimestamp = Int64(Date().timeIntervalSince1970 * 1000)
        guard let alert = V2VEmergencyAlert.fromPayload(payload) else {
            logger.logFailure(
                transport: transport,
                direction: .receive,
                messageId: "unknown",
                details: "Decode failed from \(fromPeerId)"
            )
            return nil
        }
        guard deduplicator.checkAndMark(alert.messageId) else { return nil }

        let latency = receiveTimestamp - (sentAtMs ?? alert.timestamp)
        logger.logReceive(
            transport: transport,
            messageId: alert.messageId,
            latencyMs: latency,
            payloadBytes: payload.count,
            details: "from=\(fromPeerId.prefix(8))"
        )

        let distance: Float
        let direction: String
        if let me = currentLocation {
            let alertLoc = CLLocation(latitude: alert.latitude, longitude: alert.longitude)
            distance = Float(me.distance(from: alertLoc))
            direction = relativeDirection(myLocation: me, alert: alert)
        } else {
            distance = Float.greatestFiniteMagnitude
            direction = ""
        }

        let received = ReceivedAlert(
            alert: alert,
            distanceMeters: distance,
            relativeDirection: direction
        )
        addReceivedAlert(received)
        delegate?.onEmergencyAlertReceived(received)
        return alert
    }

    public func cleanupExpiredAlerts() {
        receivedAlerts.removeAll { !$0.isValid }
    }

    public func getDeduplicationService() -> AlertDeduplicationService { deduplicator }

    private func addReceivedAlert(_ alert: ReceivedAlert) {
        receivedAlerts.removeAll { !$0.isValid }

        receivedAlerts.removeAll { $0.alert.senderPeerId == alert.alert.senderPeerId }
        receivedAlerts.append(alert)
        receivedAlerts.sort { $0.distanceMeters < $1.distanceMeters }
    }

    private func relativeDirection(myLocation: CLLocation, alert: V2VEmergencyAlert) -> String {
        let bearing = computeBearing(
            lat1: myLocation.coordinate.latitude,
            lon1: myLocation.coordinate.longitude,
            lat2: alert.latitude,
            lon2: alert.longitude
        )
        let myHeading = Double(currentHeading)
        var relative = bearing - myHeading
        if relative < 0 { relative += 360 }
        if relative > 180 { relative -= 360 }
        switch relative {
        case -45...45:    return "ahead"
        case 45...135:    return "right"
        case -135 ... -45: return "left"
        default:          return "behind"
        }
    }

    private func computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
        let lat1Rad = lat1 * .pi / 180
        let lat2Rad = lat2 * .pi / 180
        let dLon = (lon2 - lon1) * .pi / 180
        let x = sin(dLon) * cos(lat2Rad)
        let y = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        var bearing = atan2(x, y) * 180 / .pi
        if bearing < 0 { bearing += 360 }
        return bearing
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        currentLocation = location
        currentSpeed = Float(max(location.speed, 0))

        if location.course >= 0 {
            currentHeading = Float(location.course)
        }
        onLocationUpdate?(location)
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        if newHeading.trueHeading >= 0 {
            currentHeading = Float(newHeading.trueHeading)
        }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {

    }
}
