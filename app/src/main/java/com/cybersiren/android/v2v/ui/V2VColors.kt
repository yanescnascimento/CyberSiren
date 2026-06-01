package com.cybersiren.android.v2v.ui

import androidx.compose.ui.graphics.Color

object V2VColors {

    val EmergencyRed = Color(0xFFFF3B30)
    val EmergencyRedDark = Color(0xFFCC2F26)
    val WarningOrange = Color(0xFFFF9500)
    val WarningOrangeDark = Color(0xFFCC7700)
    val SafeGreen = Color(0xFF34C759)
    val SafeGreenDark = Color(0xFF2A9F47)

    val AmbulanceBlue = Color(0xFF007AFF)
    val FireRed = Color(0xFFFF2D55)
    val PoliceBlue = Color(0xFF5856D6)
    val HazardYellow = Color(0xFFFFCC00)

    val UrgencyCritical = Color(0xFFFF3B30)
    val UrgencyHigh = Color(0xFFFF9500)
    val UrgencyMedium = Color(0xFFFFCC00)
    val UrgencyLow = Color(0xFF007AFF)

    val DarkBackground = Color(0xFF1C1C1E)
    val CardBackground = Color(0xFF2C2C2E)
    val CardBackgroundLight = Color(0xFF3C3C3E)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFAEAEB2)
    val TextMuted = Color(0xFF8E8E93)

    val SenderModeColor = EmergencyRed
    val ReceiverModeColor = SafeGreen

    val StatusActive = SafeGreen
    val StatusInactive = TextMuted
    val StatusConnected = AmbulanceBlue

    val Accent = Color(0xFFFF5A1F)
    val AccentDark = Color(0xFFE64A10)
    val AccentSoft = Color(0xFFFFF1EA)

    val Ink = Color(0xFF0F1113)
    val InkSoft = Color(0xFF3A3F46)
    val Muted = Color(0xFF8A8F98)

    val BackgroundLight = Color(0xFFFBFBFC)
    val SurfaceLight = Color(0xFFFFFFFF)
    val BorderLight = Color(0xFFE6E8EC)

    val OnAccent = Color(0xFFFFFFFF)

    val Safe = Color(0xFF10B981)
    val SafeSoft = Color(0xFFECFDF5)

    val VehicleAmbulance = Color(0xFF2563EB)
    val VehicleFire = Color(0xFFDC2626)
    val VehiclePolice = Color(0xFF1E3A8A)
    val VehicleEmergency = Color(0xFFF59E0B)

    fun accentFor(type: com.cybersiren.android.v2v.model.VehicleType): Color = when (type) {
        com.cybersiren.android.v2v.model.VehicleType.AMBULANCE -> VehicleAmbulance
        com.cybersiren.android.v2v.model.VehicleType.FIRE_TRUCK -> VehicleFire
        com.cybersiren.android.v2v.model.VehicleType.POLICE_CAR -> VehiclePolice
        com.cybersiren.android.v2v.model.VehicleType.EMERGENCY -> VehicleEmergency
    }
}
