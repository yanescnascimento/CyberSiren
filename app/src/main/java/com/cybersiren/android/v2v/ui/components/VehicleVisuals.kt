package com.cybersiren.android.v2v.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import com.cybersiren.android.v2v.model.VehicleType

fun iconFor(type: VehicleType): ImageVector = when (type) {
    VehicleType.AMBULANCE -> Icons.Filled.LocalHospital
    VehicleType.FIRE_TRUCK -> Icons.Filled.LocalFireDepartment
    VehicleType.POLICE_CAR -> Icons.Filled.LocalPolice
    VehicleType.EMERGENCY -> Icons.Filled.WarningAmber
}
