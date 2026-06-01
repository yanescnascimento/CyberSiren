package com.cybersiren.android.v2v.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cybersiren.android.R
import com.cybersiren.android.v2v.model.VehicleType

@Composable
fun vehicleLabel(type: VehicleType): String = when (type) {
    VehicleType.AMBULANCE -> stringResource(R.string.v2v_vehicle_ambulance)
    VehicleType.FIRE_TRUCK -> stringResource(R.string.v2v_vehicle_fire)
    VehicleType.POLICE_CAR -> stringResource(R.string.v2v_vehicle_police)
    VehicleType.EMERGENCY -> stringResource(R.string.v2v_vehicle_emergency)
}

@Composable
fun directionLabel(direction: String): String = when (direction.lowercase()) {
    "ahead" -> stringResource(R.string.v2v_dir_ahead)
    "behind" -> stringResource(R.string.v2v_dir_behind)
    "left" -> stringResource(R.string.v2v_dir_left)
    "right" -> stringResource(R.string.v2v_dir_right)
    else -> stringResource(R.string.v2v_dir_unknown)
}

@Composable
fun peersUnit(count: Int): String =
    pluralStringResource(R.plurals.v2v_peers_unit, count, count)
