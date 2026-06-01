package com.cybersiren.android.v2v.model

import android.content.Context
import com.cybersiren.android.R
import com.cybersiren.android.v2v.ui.localized

enum class VehicleType(val code: Int, val displayName: String, val emoji: String) {
    AMBULANCE(1, "Ambulância", "\uD83D\uDE91"),
    FIRE_TRUCK(2, "Bombeiros", "\uD83D\uDE92"),
    POLICE_CAR(3, "Polícia", "\uD83D\uDE93"),
    EMERGENCY(4, "Emergência", "\u26A0\uFE0F");

    fun localizedName(context: Context): String = context.localized(
        when (this) {
            AMBULANCE -> R.string.v2v_vehicle_ambulance
            FIRE_TRUCK -> R.string.v2v_vehicle_fire
            POLICE_CAR -> R.string.v2v_vehicle_police
            EMERGENCY -> R.string.v2v_vehicle_emergency
        }
    )

    companion object {
        fun fromCode(code: Int): VehicleType? = values().find { it.code == code }
    }
}

enum class AlertType(val code: Int, val displayName: String) {
    APPROACHING(1, "Aproximando"),
    PASSING(2, "Passando"),
    STATIONARY(3, "Parado"),
    LEAVING(4, "Afastando");

    companion object {
        fun fromCode(code: Int): AlertType? = values().find { it.code == code }
    }
}

enum class AlertMode {
    SENDER,
    RECEIVER
}
