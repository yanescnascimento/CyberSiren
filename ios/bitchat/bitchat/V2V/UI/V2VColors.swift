import SwiftUI

public enum V2VColors {

    public static let emergencyRed = Color(red: 1.00, green: 0.23, blue: 0.19)
    public static let warningOrange = Color(red: 1.00, green: 0.58, blue: 0.00)
    public static let safeGreen = Color(red: 0.20, green: 0.78, blue: 0.35)

    public static let urgencyCritical = emergencyRed
    public static let urgencyHigh = warningOrange
    public static let urgencyMedium = Color(red: 1.00, green: 0.80, blue: 0.00)
    public static let urgencyLow = Color(red: 0.00, green: 0.48, blue: 1.00)

    public static let accent = Color(red: 1.00, green: 0.35, blue: 0.12)
    public static let accentDark = Color(red: 0.90, green: 0.29, blue: 0.06)
    public static let accentSoft = Color(red: 1.00, green: 0.95, blue: 0.92)

    public static let ink = Color(red: 0.06, green: 0.07, blue: 0.07)
    public static let inkSoft = Color(red: 0.23, green: 0.25, blue: 0.28)
    public static let muted = Color(red: 0.54, green: 0.56, blue: 0.60)

    public static let backgroundLight = Color(red: 0.98, green: 0.98, blue: 0.99)
    public static let surfaceLight = Color.white
    public static let borderLight = Color(red: 0.90, green: 0.91, blue: 0.93)
    public static let onAccent = Color.white

    public static let safe = Color(red: 0.06, green: 0.72, blue: 0.51)
    public static let safeSoft = Color(red: 0.93, green: 0.99, blue: 0.96)

    public static let vehicleAmbulance = Color(red: 0.15, green: 0.39, blue: 0.92)
    public static let vehicleFire = Color(red: 0.86, green: 0.15, blue: 0.15)
    public static let vehiclePolice = Color(red: 0.12, green: 0.23, blue: 0.54)
    public static let vehicleEmergency = Color(red: 0.96, green: 0.62, blue: 0.04)

    public static func accentFor(_ type: VehicleType) -> Color {
        switch type {
        case .ambulance: return vehicleAmbulance
        case .fireTruck: return vehicleFire
        case .policeCar: return vehiclePolice
        case .emergency: return vehicleEmergency
        }
    }

    public static func urgencyColor(_ level: UrgencyLevel) -> Color {
        switch level {
        case .critical: return urgencyCritical
        case .high:     return urgencyHigh
        case .medium:   return urgencyMedium
        case .low:      return urgencyLow
        }
    }
}
